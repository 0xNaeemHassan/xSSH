/*
 * xSSH — TunnelManager: process-scoped registry of active port forwards.
 *
 * Responsibilities:
 *   • Open, own, and reuse one [SshSession] per SSH connectionId so multiple
 *     tunnels that share a destination host reuse a single TCP+SSH transport.
 *   • Start LOCAL / REMOTE / DYNAMIC (SOCKS5) forwards on demand and stop them.
 *   • Publish live [TunnelRuntime] state (running / error / bound port) as a
 *     StateFlow so the UI can observe it.
 *   • Notify [BackgroundActivityController] so the foreground-service
 *     notification tracks the true count of active tunnels.
 *
 * Threading: every SSH-touching call bounces to [Dispatchers.IO]. State updates
 * are done via [MutableStateFlow] so recompositions on the main thread stay
 * consistent. All handles are stored in a ConcurrentHashMap keyed by tunnel id.
 *
 * Lifecycle: this class is a @Singleton — tunnels intentionally outlive
 * screens; only the process (or an explicit stopAll()) tears them down.
 */
package com.xssh.feature.tunnels

import com.xssh.core.data.dao.TunnelDao
import com.xssh.core.ssh.EphemeralKnownHostStore
import com.xssh.core.ssh.InteractiveHostKeyVerifier
import com.xssh.core.ssh.KnownHostStore
import com.xssh.core.ssh.Socks5Server
import com.xssh.core.ssh.SshSession
import com.xssh.core.ssh.Tunnel
import com.xssh.feature.connections.ConnectionRepository
import com.xssh.feature.connections.ConnectionRuntimeCoordinator
import com.xssh.feature.session.BackgroundActivityController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Live runtime state of a single tunnel. */
data class TunnelRuntime(
    val id: String,
    val starting: Boolean = false,
    val running: Boolean = false,
    val boundPort: Int? = null,
    val error: String? = null,
)

data class TunnelHostKeyPrompt(
    val tunnelId: String,
    val key: InteractiveHostKeyVerifier.UnknownKey,
)

data class TunnelVerificationEvent(
    val tunnelId: String,
    val event: InteractiveHostKeyVerifier.VerificationEvent,
)

data class TunnelKeyboardPrompt(
    val tunnelId: String,
    val prompts: List<String>,
    private val responder: CompletableDeferred<List<String>>,
) {
    fun respond(answers: List<String>) {
        responder.complete(answers)
    }

    fun cancel() {
        responder.complete(emptyList())
    }
}

@Singleton
class TunnelManager
    @Inject
    constructor(
        private val connectionRepo: ConnectionRepository,
        private val knownHostStore: KnownHostStore,
        private val background: BackgroundActivityController,
        private val tunnelDao: TunnelDao,
    ) : ConnectionRuntimeCoordinator {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** connectionId → shared SshSession (reference-counted by active tunnel ids). */
        private data class Shared(val session: SshSession, val refs: MutableSet<String>)

        private val sessions = ConcurrentHashMap<String, Shared>()
        private val connectMutex = Mutex()
        private val sessionLock = Any()

        /** tunnelId → the Closeable that stops that tunnel when closed. */
        private val handles = ConcurrentHashMap<String, Closeable>()
        private val handleLock = Any()
        private val startJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
        private val monitorJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
        private val pendingVerifiers = ConcurrentHashMap<String, InteractiveHostKeyVerifier>()

        private val _runtimes = MutableStateFlow<Map<String, TunnelRuntime>>(emptyMap())
        val runtimes: StateFlow<Map<String, TunnelRuntime>> = _runtimes.asStateFlow()
        private val _hostKeyPrompt = MutableStateFlow<TunnelHostKeyPrompt?>(null)
        val hostKeyPrompt: StateFlow<TunnelHostKeyPrompt?> = _hostKeyPrompt.asStateFlow()
        private val _verificationEvent = MutableStateFlow<TunnelVerificationEvent?>(null)
        val verificationEvent: StateFlow<TunnelVerificationEvent?> = _verificationEvent.asStateFlow()
        private val _keyboardPrompt = MutableStateFlow<TunnelKeyboardPrompt?>(null)
        val keyboardPrompt: StateFlow<TunnelKeyboardPrompt?> = _keyboardPrompt.asStateFlow()

        /** Count of currently-running tunnels — mirrors what the notification shows. */
        val activeCount: Int get() = handles.size

        fun runtimeOf(id: String): TunnelRuntime? = _runtimes.value[id]

        /** Start a tunnel. Idempotent: calling start on a running tunnel is a no-op. */
        fun start(
            tunnel: Tunnel,
            allowHostKeyPrompt: Boolean = true,
        ) {
            if (handles.containsKey(tunnel.id) || startJobs.containsKey(tunnel.id)) return
            lateinit var job: kotlinx.coroutines.Job
            job =
                scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        doStart(tunnel, allowHostKeyPrompt)
                    } finally {
                        startJobs.remove(tunnel.id, job)
                    }
                }
            val prior = startJobs.putIfAbsent(tunnel.id, job)
            if (prior != null) job.cancel() else job.start()
        }

        private suspend fun doStart(
            t: Tunnel,
            allowHostKeyPrompt: Boolean,
        ) {
            updateRuntime(t.id) { it.copy(starting = true, running = false, error = null) }
            val session =
                try {
                    acquireSession(t, allowHostKeyPrompt)
                } catch (_: CancellationException) {
                    updateRuntime(t.id) { it.copy(starting = false, running = false, boundPort = null, error = null) }
                    releaseSession(t.id, t.connectionId)
                    return
                } catch (e: Throwable) {
                    updateRuntime(t.id) { it.copy(starting = false, error = "Connect failed: ${e.message}") }
                    return
                }

            try {
                val forward: Pair<Closeable, Int> =
                    when (t.kind) {
                        Tunnel.Kind.LOCAL -> {
                            val h =
                                session.openLocalForward(
                                    bindHost = t.bindHost,
                                    bindPort = t.bindPort,
                                    destHost = requireNotNull(t.destHost),
                                    destPort = requireNotNull(t.destPort),
                                )
                            h to h.boundPort
                        }
                        Tunnel.Kind.REMOTE -> {
                            val h =
                                session.openRemoteForward(
                                    remoteHost = t.bindHost,
                                    remotePort = t.bindPort,
                                    localHost = requireNotNull(t.destHost),
                                    localPort = requireNotNull(t.destPort),
                                )
                            h to t.bindPort
                        }
                        Tunnel.Kind.DYNAMIC -> {
                            val server = Socks5Server(session, t.bindHost, t.bindPort)
                            server.start()
                            server to server.boundPort
                        }
                    }
                val (handle, boundPort) = forward
                val registered =
                    synchronized(handleLock) {
                        if (startJobs[t.id]?.isActive != true) {
                            false
                        } else {
                            handles[t.id] = handle
                            background.bumpTunnels(+1)
                            updateRuntime(t.id) {
                                it.copy(starting = false, running = true, boundPort = boundPort, error = null)
                            }
                            true
                        }
                    }
                if (!registered) {
                    runCatching { handle.close() }
                    releaseSession(t.id, t.connectionId)
                } else {
                    monitorTunnel(t, session, handle)
                }
            } catch (_: CancellationException) {
                updateRuntime(t.id) { it.copy(starting = false, running = false, boundPort = null, error = null) }
                releaseSession(t.id, t.connectionId)
            } catch (e: Throwable) {
                updateRuntime(
                    t.id,
                ) { it.copy(starting = false, running = false, error = e.message ?: "Failed to bind") }
                releaseSession(t.id, t.connectionId)
            }
        }

        /** Stop a tunnel. Idempotent. */
        fun stop(
            tunnelId: String,
            connectionId: String,
        ) {
            // Keep a cancelled startup registered until its finally block runs so
            // a rapid stop/start cannot overlap two generations of the same id.
            startJobs[tunnelId]?.cancel()
            monitorJobs.remove(tunnelId)?.cancel()
            pendingVerifiers.remove(tunnelId)?.cancel()
            if (_hostKeyPrompt.value?.tunnelId == tunnelId) _hostKeyPrompt.value = null
            if (_keyboardPrompt.value?.tunnelId == tunnelId) {
                _keyboardPrompt.value?.cancel()
                _keyboardPrompt.value = null
            }
            val handle =
                synchronized(handleLock) {
                    handles.remove(tunnelId).also { removed ->
                        updateRuntime(tunnelId) {
                            it.copy(starting = false, running = false, boundPort = null)
                        }
                        if (removed != null) background.bumpTunnels(-1)
                    }
                }
            if (handle == null) {
                return
            }
            runCatching { handle.close() }
            releaseSession(tunnelId, connectionId)
        }

        /** Stop everything and disconnect all shared sessions. Called on app shutdown. */
        fun stopAll() {
            startJobs.values.forEach { it.cancel() }
            startJobs.clear()
            monitorJobs.values.forEach { it.cancel() }
            monitorJobs.clear()
            pendingVerifiers.values.forEach { it.cancel() }
            pendingVerifiers.clear()
            _hostKeyPrompt.value = null
            _keyboardPrompt.value?.cancel()
            _keyboardPrompt.value = null
            val stopped =
                synchronized(handleLock) {
                    handles.keys.toList().count { id ->
                        val h = handles.remove(id)
                        if (h != null) {
                            runCatching { h.close() }
                            updateRuntime(id) { it.copy(starting = false, running = false, boundPort = null) }
                            true
                        } else {
                            false
                        }
                    }
                }
            val sessionsToClose =
                synchronized(sessionLock) {
                    sessions.values.map { it.session }.also { sessions.clear() }
                }
            sessionsToClose.forEach { runCatching { it.close() } }
            if (stopped > 0) background.bumpTunnels(-stopped)
        }

        override suspend fun stopBeforeDelete(connectionId: String) {
            tunnelDao.listForConnection(connectionId).forEach { tunnel ->
                stop(tunnel.id, connectionId)
            }
        }

        private fun monitorTunnel(
            tunnel: Tunnel,
            session: SshSession,
            handle: Closeable,
        ) {
            lateinit var monitor: kotlinx.coroutines.Job
            monitor =
                scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        while (true) {
                            delay(2_000)
                            if (handles[tunnel.id] !== handle) return@launch
                            val forwardAlive =
                                when (handle) {
                                    is SshSession.LocalForwardHandle -> handle.isRunning
                                    is Socks5Server -> handle.isRunning
                                    else -> true
                                }
                            if (!session.isConnected || !forwardAlive) {
                                val removed =
                                    synchronized(handleLock) {
                                        if (handles.remove(tunnel.id, handle)) {
                                            background.bumpTunnels(-1)
                                            updateRuntime(tunnel.id) {
                                                it.copy(
                                                    starting = false,
                                                    running = false,
                                                    boundPort = null,
                                                    error = "The SSH connection ended. Start the tunnel to reconnect.",
                                                )
                                            }
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                if (removed) {
                                    runCatching { handle.close() }
                                    releaseSession(tunnel.id, tunnel.connectionId)
                                }
                                return@launch
                            }
                        }
                    } finally {
                        monitorJobs.remove(tunnel.id, monitor)
                    }
                }
            monitorJobs.put(tunnel.id, monitor)?.cancel()
            monitor.start()
        }

        // --- session sharing ------------------------------------------------------

        private suspend fun acquireSession(
            t: Tunnel,
            allowHostKeyPrompt: Boolean,
        ): SshSession =
            withContext(Dispatchers.IO) {
                // Fast path: a session for this connection is already up.
                retireDisconnectedSession(t.connectionId)
                retainExistingSession(t.connectionId, t.id)?.let { return@withContext it }
                connectMutex.withLock {
                    // Recheck after waiting: another tunnel may have created the session.
                    retireDisconnectedSession(t.connectionId)
                    retainExistingSession(t.connectionId, t.id)?.let { return@withLock it }
                    val profile = requireNotNull(connectionRepo.get(t.connectionId)) { "Profile not found" }
                    if (!allowHostKeyPrompt) {
                        val hostPort = InteractiveHostKeyVerifier.canonicalHostPort(profile.host, profile.port)
                        check(knownHostStore.get(hostPort) != null) {
                            "Auto-start needs host verification. Open Tunnels and start this tunnel once."
                        }
                    }
                    val verifier =
                        InteractiveHostKeyVerifier(
                            if (profile.ephemeral) EphemeralKnownHostStore(knownHostStore) else knownHostStore,
                        )
                    pendingVerifiers[t.id] = verifier
                    val promptCollector =
                        scope.launch {
                            verifier.pendingPrompt.collect { key ->
                                _hostKeyPrompt.value = key?.let { TunnelHostKeyPrompt(t.id, it) }
                            }
                        }
                    val eventCollector =
                        scope.launch {
                            verifier.events.collect { event ->
                                if (event != null && event !is InteractiveHostKeyVerifier.VerificationEvent.Unknown) {
                                    _verificationEvent.value = TunnelVerificationEvent(t.id, event)
                                }
                            }
                        }
                    val newSession =
                        SshSession(profile, verifier) {
                            connectionRepo.credentialFor(t.connectionId) { prompts ->
                                val deferred = CompletableDeferred<List<String>>()
                                _keyboardPrompt.value = TunnelKeyboardPrompt(t.id, prompts, deferred)
                                try {
                                    deferred.await()
                                } finally {
                                    if (_keyboardPrompt.value?.tunnelId == t.id) _keyboardPrompt.value = null
                                }
                            }
                        }
                    try {
                        val result = newSession.connect()
                        val verificationFailure = verifier.events.value
                        if (
                            verificationFailure != null &&
                            verificationFailure !is InteractiveHostKeyVerifier.VerificationEvent.Unknown
                        ) {
                            _verificationEvent.value = TunnelVerificationEvent(t.id, verificationFailure)
                        }
                        result.getOrThrow()
                    } catch (t: Throwable) {
                        newSession.close()
                        throw t
                    } finally {
                        promptCollector.cancel()
                        eventCollector.cancel()
                        pendingVerifiers.remove(t.id)
                        if (_hostKeyPrompt.value?.tunnelId == t.id) _hostKeyPrompt.value = null
                    }
                    val refs = ConcurrentHashMap.newKeySet<String>().apply { add(t.id) }
                    synchronized(sessionLock) {
                        sessions[t.connectionId] = Shared(newSession, refs)
                    }
                    newSession
                }
            }

        private fun retainExistingSession(
            connectionId: String,
            tunnelId: String,
        ): SshSession? =
            synchronized(sessionLock) {
                sessions[connectionId]
                    ?.takeIf { it.session.isConnected }
                    ?.also { it.refs += tunnelId }
                    ?.session
            }

        private fun retireDisconnectedSession(connectionId: String) {
            val stale =
                synchronized(sessionLock) {
                    sessions[connectionId]
                        ?.takeIf { !it.session.isConnected }
                        ?.also { sessions.remove(connectionId, it) }
                } ?: return
            stale.refs.toList().forEach { tunnelId ->
                monitorJobs.remove(tunnelId)?.cancel()
                val handle =
                    synchronized(handleLock) {
                        handles.remove(tunnelId).also { removed ->
                            if (removed != null) {
                                background.bumpTunnels(-1)
                                updateRuntime(tunnelId) {
                                    it.copy(
                                        starting = false,
                                        running = false,
                                        boundPort = null,
                                        error = "The SSH connection ended. Start the tunnel to reconnect.",
                                    )
                                }
                            }
                        }
                    }
                runCatching { handle?.close() }
            }
            runCatching { stale.session.close() }
        }

        private fun releaseSession(
            tunnelId: String,
            connectionId: String,
        ) {
            val toClose =
                synchronized(sessionLock) {
                    val shared = sessions[connectionId] ?: return@synchronized null
                    shared.refs -= tunnelId
                    if (shared.refs.isEmpty() && sessions.remove(connectionId, shared)) shared.session else null
                }
            if (toClose != null) runCatching { toClose.close() }
        }

        private fun updateRuntime(
            id: String,
            f: (TunnelRuntime) -> TunnelRuntime,
        ) {
            _runtimes.update { current ->
                current.toMutableMap().also {
                    it[id] = f(it[id] ?: TunnelRuntime(id))
                }
            }
        }

        fun acceptHostKey(
            tunnelId: String,
            key: InteractiveHostKeyVerifier.UnknownKey,
        ) {
            pendingVerifiers[tunnelId]?.acceptPending(key)
        }

        fun rejectHostKey(
            tunnelId: String,
            key: InteractiveHostKeyVerifier.UnknownKey,
        ) {
            pendingVerifiers[tunnelId]?.rejectPending(key)
        }

        fun clearVerificationEvent() {
            _verificationEvent.value = null
        }

        fun forgetHostKey(
            tunnelId: String,
            hostPort: String,
        ) {
            scope.launch {
                knownHostStore.delete(hostPort)
                _verificationEvent.value = null
                updateRuntime(tunnelId) {
                    it.copy(error = "Old host key removed. Start again and verify the new fingerprint carefully.")
                }
            }
        }
    }

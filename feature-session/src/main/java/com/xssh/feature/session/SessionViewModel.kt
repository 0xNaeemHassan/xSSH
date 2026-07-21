package com.xssh.feature.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.termux.terminal.TerminalSession
import com.xssh.core.data.dao.SnippetDao
import com.xssh.core.data.entity.SnippetEntity
import com.xssh.core.ssh.EphemeralKnownHostStore
import com.xssh.core.ssh.InteractiveHostKeyVerifier
import com.xssh.core.ssh.KnownHostStore
import com.xssh.core.ssh.SshSession
import com.xssh.core.terminal.ShellIo
import com.xssh.core.terminal.SpecialKey
import com.xssh.core.terminal.feedRemoteBytes
import com.xssh.core.terminal.toBytes
import com.xssh.feature.connections.ConnectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.OutputStream
import javax.inject.Inject

data class SessionUiState(
    val title: String = "",
    val endpoint: String = "",
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val statusMessage: String? = null,
    val error: String? = null,
    val ctrlArmed: Boolean = false,
    val altArmed: Boolean = false,
)

data class KeyboardInteractivePrompt(
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

@HiltViewModel
class SessionViewModel
    @Inject
    constructor(
        private val repo: ConnectionRepository,
        private val knownHostStore: KnownHostStore,
        private val snippetDao: SnippetDao,
        private val background: BackgroundActivityController,
    ) : ViewModel() {
        private val _state = MutableStateFlow(SessionUiState())
        val state: StateFlow<SessionUiState> = _state.asStateFlow()

        private var verifier = InteractiveHostKeyVerifier(knownHostStore)
        private val _hostKeyPrompt = MutableStateFlow<InteractiveHostKeyVerifier.UnknownKey?>(null)
        val hostKeyPrompt: StateFlow<InteractiveHostKeyVerifier.UnknownKey?> = _hostKeyPrompt.asStateFlow()
        private val _hostKeyEvents = MutableStateFlow<InteractiveHostKeyVerifier.VerificationEvent?>(null)
        val hostKeyEvents: StateFlow<InteractiveHostKeyVerifier.VerificationEvent?> = _hostKeyEvents.asStateFlow()

        private val keyboardInteractivePromptState = MutableStateFlow<KeyboardInteractivePrompt?>(null)
        val kbiPrompt: StateFlow<KeyboardInteractivePrompt?> = keyboardInteractivePromptState.asStateFlow()

        val snippets: StateFlow<List<SnippetEntity>> =
            snippetDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        private var session: SshSession? = null
        private var shell: Session.Shell? = null
        private var out: OutputStream? = null
        private var reader: Job? = null
        private var connectJob: Job? = null

        @Volatile private var terminalSession: TerminalSession? = null
        private var counted: Boolean = false
        private val resourceLock = Any()
        private val modifierLock = Any()
        private var generation: Long = 0

        private data class PendingInput(val output: OutputStream, val bytes: ByteArray)

        private val inputQueue = Channel<PendingInput>(capacity = 64)
        private val resizeQueue = Channel<Pair<Int, Int>>(capacity = Channel.CONFLATED)
        private val writer =
            viewModelScope.launch(Dispatchers.IO) {
                for (pending in inputQueue) {
                    try {
                        pending.output.write(pending.bytes)
                        pending.output.flush()
                    } catch (t: Throwable) {
                        val isCurrentOutput = synchronized(resourceLock) { out === pending.output }
                        if (isCurrentOutput) {
                            _state.update {
                                it.copy(
                                    error =
                                        t.message?.let {
                                                message ->
                                            "Input failed: $message"
                                        } ?: "Input failed.",
                                )
                            }
                        }
                    } finally {
                        pending.bytes.fill(0)
                    }
                }
            }
        private val resizeWorker =
            viewModelScope.launch(Dispatchers.IO) {
                for ((cols, rows) in resizeQueue) {
                    val pair =
                        synchronized(resourceLock) {
                            val activeSession = session ?: return@synchronized null
                            val activeShell = shell ?: return@synchronized null
                            activeSession to activeShell
                        } ?: continue
                    pair.first.resizePty(pair.second, cols, rows)
                }
            }

        fun onSessionCreated(s: TerminalSession) {
            terminalSession = s
        }

        // Keep the connection state transition linear so every acquired resource has one visible cleanup path.
        @Suppress("LongMethod", "CyclomaticComplexMethod")
        fun start(
            connectionId: String,
            cols: Int = 100,
            rows: Int = 30,
        ) {
            val token =
                synchronized(resourceLock) {
                    if (session != null || connectJob?.isActive == true) return
                    ++generation
                }
            _state.update { it.copy(connecting = true, error = null, statusMessage = null) }
            connectJob =
                viewModelScope.launch(Dispatchers.IO) {
                    val profile =
                        repo.get(connectionId) ?: run {
                            if (isCurrent(token)) {
                                _state.update { it.copy(connecting = false, error = "Profile not found") }
                            }
                            return@launch
                        }
                    if (!isCurrent(token)) return@launch
                    _state.update {
                        it.copy(
                            title = "${profile.username}@${profile.host}",
                            endpoint = "${profile.host}:${profile.port}",
                            error = null,
                        )
                    }

                    val attemptVerifier =
                        InteractiveHostKeyVerifier(
                            if (profile.ephemeral) EphemeralKnownHostStore(knownHostStore) else knownHostStore,
                        )
                    verifier = attemptVerifier
                    val promptCollector =
                        viewModelScope.launch {
                            attemptVerifier.pendingPrompt.collect { _hostKeyPrompt.value = it }
                        }
                    val eventCollector =
                        viewModelScope.launch {
                            attemptVerifier.events.collect { _hostKeyEvents.value = it }
                        }

                    val s =
                        SshSession(profile, attemptVerifier) {
                            repo.credentialFor(connectionId) { prompts ->
                                val deferred = CompletableDeferred<List<String>>()
                                keyboardInteractivePromptState.value = KeyboardInteractivePrompt(prompts, deferred)
                                try {
                                    deferred.await()
                                } finally {
                                    keyboardInteractivePromptState.value = null
                                }
                            }
                        }
                    val registered =
                        synchronized(resourceLock) {
                            if (generation == token) {
                                session = s
                                true
                            } else {
                                false
                            }
                        }
                    if (!registered) {
                        promptCollector.cancel()
                        eventCollector.cancel()
                        s.close()
                        return@launch
                    }
                    val r =
                        try {
                            s.connect()
                        } finally {
                            if (isCurrent(token)) _hostKeyEvents.value = attemptVerifier.events.value
                            promptCollector.cancel()
                            eventCollector.cancel()
                        }
                    r.onFailure {
                        val cleaned = cleanupSessionState(expected = s, preserveError = true)
                        if (cleaned && isCurrent(token)) {
                            val verificationFailure = attemptVerifier.events.value != null
                            _state.update { state ->
                                state.copy(
                                    connecting = false,
                                    error = if (verificationFailure) null else "Connect failed: ${it.message}",
                                )
                            }
                        }
                        return@launch
                    }
                    val stillCurrent = synchronized(resourceLock) { generation == token && session === s }
                    if (!stillCurrent) {
                        s.close()
                        return@launch
                    }
                    val sh =
                        try {
                            s.openShell(cols = cols, rows = rows)
                        } catch (cancelled: CancellationException) {
                            cleanupSessionState(expected = s, preserveError = true)
                            throw cancelled
                        } catch (t: Throwable) {
                            val cleaned = cleanupSessionState(expected = s, preserveError = true)
                            if (cleaned && isCurrent(token)) {
                                _state.update {
                                    it.copy(
                                        connecting = false,
                                        error = "Unable to open terminal: ${t.message}",
                                    )
                                }
                            }
                            return@launch
                        }
                    val activated =
                        synchronized(resourceLock) {
                            if (generation != token || session !== s) {
                                false
                            } else {
                                shell = sh
                                out = sh.outputStream
                                if (!counted) {
                                    background.bumpSessions(+1)
                                    counted = true
                                }
                                _state.update { it.copy(connecting = false, connected = true, statusMessage = null) }
                                true
                            }
                        }
                    if (!activated) {
                        runCatching { sh.close() }
                        s.close()
                        return@launch
                    }
                    if (!profile.ephemeral) repo.touch(connectionId)
                    val readerJob =
                        viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                            val buf = ByteArray(4096)
                            val input = sh.inputStream
                            try {
                                while (synchronized(resourceLock) { session === s }) {
                                    val n =
                                        try {
                                            input.read(buf)
                                        } catch (_: Throwable) {
                                            -1
                                        }
                                    if (n <= 0) break
                                    terminalSession?.let { ts -> feedRemoteBytes(ts, buf, n) }
                                }
                            } finally {
                                buf.fill(0)
                            }
                            if (cleanupSessionState(expected = s, preserveError = true)) {
                                _state.update {
                                    it.copy(
                                        connecting = false,
                                        connected = false,
                                        statusMessage = "The remote session ended.",
                                    )
                                }
                            }
                        }
                    val keepReader =
                        synchronized(resourceLock) {
                            if (generation == token && session === s) {
                                reader = readerJob
                                true
                            } else {
                                false
                            }
                        }
                    if (keepReader) readerJob.start() else readerJob.cancel()
                }
        }

        val shellIo =
            object : ShellIo {
                override fun onUserInput(bytes: ByteArray) {
                    val payload =
                        synchronized(modifierLock) {
                            when {
                                _state.value.ctrlArmed && bytes.isNotEmpty() -> {
                                    _state.update { it.copy(ctrlArmed = false) }
                                    val first = bytes[0].toInt() and 0xff
                                    if (first in 0x20..0x7e) {
                                        byteArrayOf(first.and(0x1f).toByte()) + bytes.copyOfRange(1, bytes.size)
                                    } else {
                                        bytes
                                    }
                                }
                                _state.value.altArmed && bytes.isNotEmpty() -> {
                                    _state.update { it.copy(altArmed = false) }
                                    byteArrayOf(0x1b) + bytes
                                }
                                else -> bytes
                            }
                        }
                    if (payload !== bytes) bytes.fill(0)
                    writeAsync(payload)
                }

                override fun onResize(
                    cols: Int,
                    rows: Int,
                ) {
                    if (cols > 0 && rows > 0) resizeQueue.trySend(cols to rows)
                }

                override fun onTitleChanged(title: String) {
                    if (title.isNotBlank()) _state.update { it.copy(title = title.take(160)) }
                }
            }

        fun writeSpecial(key: SpecialKey) {
            val modifiers =
                synchronized(modifierLock) {
                    val current = _state.value
                    _state.update { it.copy(ctrlArmed = false, altArmed = false) }
                    current.ctrlArmed to current.altArmed
                }
            writeAsync(key.toBytes(terminalSession, ctrl = modifiers.first, alt = modifiers.second))
        }

        fun pasteSnippet(
            body: String,
            appendNewline: Boolean = false,
        ) {
            if (!_state.value.connected) return
            val payload = if (appendNewline && !body.endsWith('\n')) body + '\n' else body
            writeAsync(payload.toByteArray(Charsets.UTF_8))
        }

        fun pasteSnippet(snippet: SnippetEntity) = pasteSnippet(snippet.body, appendNewline = snippet.executeOnPaste)

        fun toggleCtrl() {
            _state.update { it.copy(ctrlArmed = !it.ctrlArmed) }
        }

        fun toggleAlt() {
            _state.update { it.copy(altArmed = !it.altArmed) }
        }

        fun acceptHostKey(key: InteractiveHostKeyVerifier.UnknownKey) = verifier.acceptPending(key)

        fun rejectHostKey(key: InteractiveHostKeyVerifier.UnknownKey) {
            verifier.rejectPending(key)
            disconnect()
        }

        fun clearError() {
            _state.update { it.copy(error = null) }
        }

        fun clearHostKeyEvent() {
            verifier.clearEvent()
            _hostKeyEvents.value = null
        }

        fun forgetHostKey(hostPort: String) {
            viewModelScope.launch(Dispatchers.IO) {
                knownHostStore.delete(hostPort)
                verifier.clearEvent()
                _hostKeyEvents.value = null
                _state.update {
                    it.copy(
                        error = null,
                        statusMessage = "Old host key removed. Reconnect and verify the new fingerprint carefully.",
                    )
                }
            }
        }

        fun disconnect() {
            connectJob?.cancel()
            connectJob = null
            val expected =
                synchronized(resourceLock) {
                    generation++
                    session
                }
            _state.update { it.copy(connecting = false, connected = false) }
            viewModelScope.launch(Dispatchers.IO) {
                cleanupSessionState(expected = expected, preserveError = true)
            }
        }

        override fun onCleared() {
            connectJob?.cancel()
            connectJob = null
            synchronized(resourceLock) { generation++ }
            inputQueue.close()
            while (true) {
                inputQueue.tryReceive().getOrNull()?.bytes?.fill(0) ?: break
            }
            resizeQueue.close()
            writer.cancel()
            resizeWorker.cancel()
            cleanupSessionState(preserveError = true)
            super.onCleared()
        }

        private data class SessionResources(
            val reader: Job?,
            val output: OutputStream?,
            val shell: Session.Shell?,
            val session: SshSession?,
            val wasCounted: Boolean,
        )

        private fun cleanupSessionState(
            expected: SshSession? = null,
            preserveError: Boolean,
        ): Boolean {
            val resources =
                synchronized(resourceLock) {
                    if (expected != null && session !== expected) return false
                    SessionResources(reader, out, shell, session, counted).also {
                        reader = null
                        out = null
                        shell = null
                        session = null
                        counted = false
                    }
                }
            resources.reader?.cancel()
            keyboardInteractivePromptState.value?.cancel()
            keyboardInteractivePromptState.value = null
            runCatching { resources.output?.flush() }
            runCatching { resources.output?.close() }
            runCatching { resources.shell?.close() }
            runCatching { resources.session?.close() }
            verifier.cancel()
            _hostKeyPrompt.value = null
            if (resources.wasCounted) {
                background.bumpSessions(-1)
            }
            if (!preserveError) {
                _state.update { it.copy(error = null) }
            }
            return true
        }

        private fun writeAsync(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            if (bytes.size > MAX_INPUT_BYTES) {
                bytes.fill(0)
                _state.update { it.copy(error = "Paste is too large. Limit input to 1 MiB at a time.") }
                return
            }
            val output =
                synchronized(resourceLock) { out } ?: run {
                    bytes.fill(0)
                    return
                }
            if (inputQueue.trySend(PendingInput(output, bytes)).isFailure) {
                bytes.fill(0)
                _state.update { it.copy(error = "Terminal input is busy. Try again in a moment.") }
            }
        }

        private fun isCurrent(token: Long): Boolean = synchronized(resourceLock) { generation == token }

        private companion object {
            const val MAX_INPUT_BYTES = 1024 * 1024
        }
    }

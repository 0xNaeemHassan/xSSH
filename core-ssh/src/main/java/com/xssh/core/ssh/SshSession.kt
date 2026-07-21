/*
 * xSSH — SshSession: one long-lived sshj SSHClient per user session.
 *
 * Responsibilities:
 *   - Enforce our modern-algorithm allowlist (post-filter sshj defaults).
 *   - Wire the interactive host-key verifier.
 *   - Provide coroutine-flavored connect / shell / sftp / tunnel operations.
 *   - Support app-managed agent-style key authentication.
 *
 * sshj is blocking. Every method here bounces to Dispatchers.IO so callers
 * can safely invoke from Compose without freezing the frame.
 *
 * Tunnels (LOCAL / REMOTE):
 *   sshj's LocalPortForwarder.listen() blocks the caller until the underlying
 *   ServerSocket is closed. We start it on a dedicated daemon thread and hand
 *   back a Closeable [LocalForwardHandle] whose close() unblocks the accept
 *   loop and joins the thread. REMOTE forwards return a [RemoteForwardHandle]
 *   that calls `remotePortForwarder.cancel(forward)` on close.
 */
package com.xssh.core.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket

class SshSession(
    private val profile: SshConnectionProfile,
    private val verifier: HostKeyVerifier,
    private val credentialProvider: suspend (SshConnectionProfile) -> Credential,
) : Closeable {
    private val client: SSHClient =
        SSHClient(buildSecureConfig(profile.options)).apply {
            addHostKeyVerifier(verifier)
            connectTimeout = profile.options.connectTimeoutMs
            // sshj's `timeout` is the established socket read timeout, not the
            // connection timeout. Reusing connectTimeoutMs here disconnected idle
            // shells after a few seconds; zero deliberately means no read timeout.
            timeout = profile.options.readTimeoutMs
            if (profile.options.compression) useCompression()
        }

    /** Expose the underlying sshj client for advanced integrations (SOCKS5). */
    internal val underlying: SSHClient get() = client

    /** Public accessor used by [Socks5Server] to open direct-tcpip channels. */
    fun sshClient(): SSHClient = client

    val isConnected: Boolean get() = client.isConnected && client.isAuthenticated

    suspend fun connect(): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                require(profile.username.isNotBlank()) { "Add a username before connecting." }
                client.connect(profile.host, profile.port)
                when (val cred = credentialProvider(profile)) {
                    is Credential.Password -> {
                        try {
                            client.authPassword(profile.username, cred.password)
                        } finally {
                            java.util.Arrays.fill(cred.password, '\u0000')
                        }
                    }
                    is Credential.PrivateKey ->
                        client.authPublickey(profile.username, cred.keyProvider)
                    is Credential.Agent ->
                        client.auth(
                            profile.username,
                            AgentBackedAuthMethod(
                                keyProvider = cred.keyProvider,
                                signer = InProcessAgentSigner(cred.keyProvider),
                                label = cred.label,
                            ),
                        )
                    is Credential.Interactive ->
                        client.auth(
                            profile.username,
                            PromptListKeyboardInteractiveAuth(cred.respond),
                        )
                }
                client.connection.keepAlive.keepAliveInterval = profile.options.keepAliveSeconds
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
        }

    suspend fun openShell(
        cols: Int,
        rows: Int,
        term: String = "xterm-256color",
        env: Map<String, String> = emptyMap(),
    ): Session.Shell =
        withContext(Dispatchers.IO) {
            val s = client.startSession()
            try {
                s.allocatePTY(term, cols, rows, 0, 0, emptyMap())
                env.forEach { (k, v) -> runCatching { s.setEnvVar(k, v) } }
                s.startShell()
            } catch (t: Throwable) {
                runCatching { s.close() }
                throw t
            }
        }

    suspend fun resizePty(
        shell: Session.Shell,
        cols: Int,
        rows: Int,
    ) = withContext(Dispatchers.IO) {
        runCatching { shell.changeWindowDimensions(cols, rows, 0, 0) }
    }

    suspend fun openSftp(): SFTPClient = withContext(Dispatchers.IO) { client.newSFTPClient() }

    // --- Port forwarding -----------------------------------------------------

    /**
     * LOCAL forward (`ssh -L bindHost:bindPort:destHost:destPort`).
     *
     * Returns a [LocalForwardHandle] that owns both the bound [ServerSocket]
     * and the accept-loop thread. Callers must invoke [LocalForwardHandle.close]
     * to stop the forward — closing the ServerSocket unblocks the accept loop.
     */
    suspend fun openLocalForward(
        bindHost: String,
        bindPort: Int,
        destHost: String,
        destPort: Int,
    ): LocalForwardHandle =
        withContext(Dispatchers.IO) {
            val params = Parameters(bindHost, bindPort, destHost, destPort)
            val ss =
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(bindHost, bindPort))
                }
            try {
                val fwd = client.newLocalPortForwarder(params, ss)
                val loop =
                    Thread(
                        {
                            runCatching { fwd.listen() } // returns when ss.close() is called
                        },
                        "xssh-local-fwd-$bindPort",
                    ).apply {
                        isDaemon = true
                        start()
                    }
                LocalForwardHandle(ss, loop)
            } catch (t: Throwable) {
                runCatching { ss.close() }
                throw t
            }
        }

    /** Handle to a running LOCAL forward. Close it to stop accepting new sockets. */
    class LocalForwardHandle internal constructor(
        private val serverSocket: ServerSocket,
        private val thread: Thread,
    ) : Closeable {
        val boundPort: Int get() = serverSocket.localPort
        val isRunning: Boolean get() = !serverSocket.isClosed && thread.isAlive

        override fun close() {
            runCatching { serverSocket.close() }
            runCatching { thread.join(500) }
        }
    }

    /**
     * REMOTE forward (`ssh -R remoteHost:remotePort:localHost:localPort`).
     *
     * The remote SSH server listens on remoteHost:remotePort and dials back
     * through this session to localHost:localPort on the phone side.
     *
     * Note: many OpenSSH deployments require `GatewayPorts yes` on the server
     * to bind anything other than the loopback interface remotely. If the
     * server refuses, sshj raises an exception which surfaces as a Result
     * failure to the caller of TunnelManager.
     */
    suspend fun openRemoteForward(
        remoteHost: String,
        remotePort: Int,
        localHost: String,
        localPort: Int,
    ): RemoteForwardHandle =
        withContext(Dispatchers.IO) {
            val forward = RemotePortForwarder.Forward(remoteHost, remotePort)
            val listener = SocketForwardingConnectListener(InetSocketAddress(localHost, localPort))
            val bound = client.remotePortForwarder.bind(forward, listener)
            RemoteForwardHandle(client, bound)
        }

    /** Handle to a running REMOTE forward. Close it to release the remote listener. */
    class RemoteForwardHandle internal constructor(
        private val client: SSHClient,
        private val forward: RemotePortForwarder.Forward,
    ) : Closeable {
        override fun close() {
            runCatching { client.remotePortForwarder.cancel(forward) }
        }
    }

    override fun close() {
        runCatching { client.close() }
    }
}

/**
 * Fail-closed transport policy. SSHJ intentionally ships a compatibility-wide
 * default list that still includes CBC, RC4, MD5/SHA-1 MACs, DSA, and SHA-1
 * RSA. Filtering from explicit allowlists prevents a newly added legacy
 * factory from silently becoming negotiable after a dependency upgrade.
 */
internal fun buildSecureConfig(options: TransportOptions): DefaultConfig =
    DefaultConfig().apply {
        cipherFactories = cipherFactories.secureOnly(SECURE_CIPHERS, options)
        macFactories = macFactories.secureOnly(SECURE_MACS, options)
        keyExchangeFactories = keyExchangeFactories.secureOnly(SECURE_KEY_EXCHANGES, options)
        keyAlgorithms = keyAlgorithms.secureOnly(SECURE_KEY_ALGORITHMS, options)
        check(cipherFactories.isNotEmpty()) { "No approved SSH ciphers are available" }
        check(macFactories.isNotEmpty()) { "No approved SSH MACs are available" }
        check(keyExchangeFactories.isNotEmpty()) { "No approved SSH key exchanges are available" }
        check(keyAlgorithms.isNotEmpty()) { "No approved SSH host-key algorithms are available" }
    }

private fun <T : net.schmizz.sshj.common.Factory.Named<*>> List<T>.secureOnly(
    allowed: List<String>,
    options: TransportOptions,
): List<T> {
    val available = associateBy { it.name }
    return allowed.mapNotNull { name ->
        available[name]?.takeUnless { name in options.disabledAlgorithms }
    }
}

internal val SECURE_CIPHERS =
    listOf(
        "chacha20-poly1305@openssh.com",
        "aes256-gcm@openssh.com",
        "aes128-gcm@openssh.com",
        "aes256-ctr",
        "aes192-ctr",
        "aes128-ctr",
    )

internal val SECURE_MACS =
    listOf(
        "hmac-sha2-512-etm@openssh.com",
        "hmac-sha2-256-etm@openssh.com",
        "hmac-sha2-512",
        "hmac-sha2-256",
    )

internal val SECURE_KEY_EXCHANGES =
    listOf(
        "curve25519-sha256",
        "curve25519-sha256@libssh.org",
        "diffie-hellman-group-exchange-sha256",
        "ecdh-sha2-nistp256",
        "ecdh-sha2-nistp384",
        "ecdh-sha2-nistp521",
        "diffie-hellman-group14-sha256",
        "diffie-hellman-group15-sha512",
        "diffie-hellman-group16-sha512",
        "diffie-hellman-group17-sha512",
        "diffie-hellman-group18-sha512",
        "ext-info-c",
    )

internal val SECURE_KEY_ALGORITHMS =
    listOf(
        "ssh-ed25519-cert-v01@openssh.com",
        "ssh-ed25519",
        "ecdsa-sha2-nistp256-cert-v01@openssh.com",
        "ecdsa-sha2-nistp384-cert-v01@openssh.com",
        "ecdsa-sha2-nistp521-cert-v01@openssh.com",
        "ecdsa-sha2-nistp256",
        "ecdsa-sha2-nistp384",
        "ecdsa-sha2-nistp521",
        "rsa-sha2-256",
        "rsa-sha2-512",
    )

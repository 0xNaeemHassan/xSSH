/*
 * xSSH — Minimal SOCKS5 server that tunnels each accepted TCP CONNECT through
 * an SshSession using sshj's direct-tcpip channel. Our `ssh -D` equivalent.
 *
 * Supported:
 *   - SOCKS5 no-auth method (0x00)
 *   - CONNECT command (0x01)
 *   - Address types: IPv4, DOMAINNAME, IPv6
 *   - Bidirectional pump using two IO threads (blocking is fine — sshj is too)
 *
 * NOT supported (intentionally kept small):
 *   - BIND / UDP ASSOCIATE
 *   - Username/password auth (bind to loopback and firewall externally)
 *
 * RFC 1928. bindHost defaults to loopback.
 *
 * Implementation notes:
 *   • sshj exposes `SSHClient.newDirectConnection(host, port)` for exactly
 *     this use case — a client-driven `direct-tcpip` channel that the SOCKS
 *     server pumps bytes through. That returns a Session-like Closeable with
 *     getInputStream()/getOutputStream(). Do NOT poke at
 *     `client.connection.openChannel(...)` — it is not the public API and
 *     signatures drift between sshj minor releases.
 *   • Each accepted socket runs on two dedicated Java threads (one per
 *     direction). Blocking IO everywhere: sshj's channel streams are
 *     blocking, so pushing them onto coroutines buys nothing but a thread
 *     hop per byte.
 *   • serverSocket.close() unblocks the accept() call — the accept loop
 *     drops out cleanly on stop().
 */
package com.xssh.core.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.schmizz.sshj.SSHClient
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class Socks5Server(
    private val client: SSHClient,
    private val bindHost: String = "127.0.0.1",
    private val bindPort: Int,
) : Closeable {
    /** Convenience overload: bind a SOCKS5 server to a running [SshSession]. */
    constructor(session: SshSession, bindHost: String = "127.0.0.1", bindPort: Int) :
        this(session.sshClient(), bindHost, bindPort)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    @Volatile private var serverSocket: ServerSocket? = null
    private val activeResources = ConcurrentHashMap.newKeySet<Closeable>()
    private val activeClients = AtomicInteger(0)

    /** Public bind port after start() (mirrors ServerSocket.getLocalPort). */
    val boundPort: Int get() = serverSocket?.localPort ?: bindPort
    val isRunning: Boolean get() = running.get() && serverSocket?.isClosed == false

    /**
     * Bind synchronously on the caller thread (so port errors surface immediately),
     * then launch the accept loop on [scope]. Returns the accept-loop Job.
     */
    fun start(): Job {
        require(running.compareAndSet(false, true)) { "Already running" }
        val ss =
            try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(bindHost, bindPort))
                }
            } catch (t: Throwable) {
                running.set(false)
                throw t
            }
        serverSocket = ss
        return scope.launch {
            try {
                while (running.get()) {
                    val s =
                        try {
                            ss.accept()
                        } catch (_: Throwable) {
                            break
                        }
                    if (activeClients.incrementAndGet() > MAX_CLIENTS) {
                        activeClients.decrementAndGet()
                        runCatching { s.close() }
                    } else {
                        activeResources += s
                        val enteredHandler = AtomicBoolean(false)
                        launch {
                            enteredHandler.set(true)
                            handle(s)
                        }.invokeOnCompletion {
                            // A cancelled parent can reject the child before
                            // its body runs, so close the accepted socket here.
                            if (!enteredHandler.get()) {
                                activeResources -= s
                                activeClients.decrementAndGet()
                                runCatching { s.close() }
                            }
                        }
                    }
                }
            } finally {
                running.set(false)
                runCatching { ss.close() }
                if (serverSocket === ss) serverSocket = null
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        activeResources.toList().forEach { runCatching { it.close() } }
        activeResources.clear()
        scope.cancel()
    }

    /** [Closeable] shim so a SOCKS server can live in a use-block. */
    override fun close() = stop()

    // SOCKS5 parsing is guard-driven: each invalid frame must terminate the client immediately.
    @Suppress("ReturnCount")
    private fun handle(sock: Socket) {
        try {
            sock.use {
                // stop() can race with accept() handing this socket to a child.
                if (!running.get()) return
                sock.soTimeout = HANDSHAKE_TIMEOUT_MS
                val ins = DataInputStream(sock.getInputStream())
                val outs = DataOutputStream(sock.getOutputStream())

                // ---- SOCKS5 greeting (RFC 1928 §3) -----------------------------
                if (ins.readByte().toInt() != 0x05) return
                val nMethods = ins.readByte().toInt() and 0xff
                val methods = ByteArray(nMethods).also { ins.readFully(it) }
                // Only "no auth required" (0x00) is offered. If the client did not
                // list it, return 0xFF as required by the RFC.
                if (methods.none { it.toInt() == 0x00 }) {
                    outs.write(byteArrayOf(0x05, 0xFF.toByte()))
                    outs.flush()
                    return
                }
                outs.write(byteArrayOf(0x05, 0x00))
                outs.flush()

                // ---- SOCKS5 request (RFC 1928 §4) ------------------------------
                val ver = ins.readByte().toInt()
                val cmd = ins.readByte().toInt()
                ins.readByte() // RSV
                val atyp = ins.readByte().toInt() and 0xff
                if (ver != 0x05 || cmd != 0x01) {
                    writeSocksReply(outs, 0x07)
                    return // command not supported
                }
                val host: String =
                    when (atyp) {
                        0x01 -> { // IPv4
                            val b = ByteArray(4).also { ins.readFully(it) }
                            "${b[0].toInt() and 0xff}.${b[1].toInt() and 0xff}." +
                                "${b[2].toInt() and 0xff}.${b[3].toInt() and 0xff}"
                        }
                        0x03 -> { // DOMAINNAME
                            val len = ins.readByte().toInt() and 0xff
                            val b = ByteArray(len).also { ins.readFully(it) }
                            String(b, Charsets.US_ASCII)
                        }
                        0x04 -> { // IPv6
                            val b = ByteArray(16).also { ins.readFully(it) }
                            java.net.Inet6Address.getByAddress(b).hostAddress
                        }
                        else -> {
                            writeSocksReply(outs, 0x08)
                            return
                        } // address not supported
                    }
                val port = ins.readUnsignedShort()

                // ---- Open the SSH direct-tcpip channel ------------------------
                //
                // sshj's public API for "please give me a byte-stream channel from
                // this SSH session to hostname:port on the far side". This is what
                // OpenSSH does when the local client is playing SOCKS.
                val direct =
                    try {
                        client.newDirectConnection(host, port)
                    } catch (_: Throwable) {
                        writeSocksReply(outs, 0x05)
                        return // connection refused / general failure
                    }
                activeResources += direct
                // If stop() ran between opening and registering the SSH channel,
                // its resource snapshot could not have closed this channel.
                if (!running.get()) {
                    activeResources -= direct
                    runCatching { direct.close() }
                    return
                }
                sock.soTimeout = 0

                // Success reply — BND.ADDR/BND.PORT of 0.0.0.0:0 (per RFC, most
                // clients ignore these anyway).
                outs.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                outs.flush()

                // ---- Bidirectional pump ---------------------------------------
                val chIn: InputStream = direct.inputStream
                val chOut: OutputStream = direct.outputStream
                val t1 =
                    Thread {
                        try {
                            runCatching { copy(sock.getInputStream(), chOut) }
                        } finally {
                            // Forward local EOF so a server waiting for the complete
                            // request can respond instead of deadlocking both joins.
                            runCatching { chOut.close() }
                        }
                    }
                        .apply {
                            name = "xssh-socks-out-$port"
                            isDaemon = true
                        }
                val t2 =
                    Thread {
                        try {
                            runCatching { copy(chIn, sock.getOutputStream()) }
                        } finally {
                            runCatching { sock.shutdownOutput() }
                        }
                    }
                        .apply {
                            name = "xssh-socks-in-$port"
                            isDaemon = true
                        }
                try {
                    t1.start()
                    t2.start()
                    t1.join()
                    t2.join()
                } finally {
                    activeResources -= direct
                    runCatching { direct.close() }
                }
            }
        } finally {
            activeResources -= sock
            activeClients.decrementAndGet()
        }
    }

    private fun copy(
        input: InputStream,
        output: OutputStream,
    ) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
            output.flush()
        }
    }

    private fun writeSocksReply(
        out: DataOutputStream,
        rep: Int,
    ) {
        out.write(byteArrayOf(0x05, rep.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 30_000
        const val MAX_CLIENTS = 128
    }
}

/*
 * xSSH — remote-terminal bridge for Termux's terminal emulator.
 *
 * Newer Termux releases make `TerminalSession` final, so we cannot subclass it
 * to intercept writes. Instead its tiny helper subprocess copies stdin into an
 * app-private FIFO. A single reader owns that FIFO and forwards every byte to
 * [ShellIo.onUserInput]. This avoids competing with Termux's own output-writer
 * thread (which made keystrokes disappear nondeterministically).
 *
 * This keeps xSSH on the maintained Termux engine without reviving an embedded
 * custom keyboard or forking the emulator stack.
 */
package com.xssh.core.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File
import java.io.FileInputStream
import java.util.ArrayDeque
import java.util.UUID
import java.util.WeakHashMap

interface ShellIo {
    /** User typed something — bytes go to the remote PTY over SSH. */
    fun onUserInput(bytes: ByteArray)

    /** Terminal wants a new size (rotation, split-pane resize). */
    fun onResize(
        cols: Int,
        rows: Int,
    ) {}

    fun onBell() {}

    fun onTitleChanged(title: String) {}
}

private const val SINK_SHELL = "/system/bin/sh"
private val DEFAULT_ENV = arrayOf("TERM=xterm-256color")

class RemoteTerminalSession(
    context: Context,
    private val io: ShellIo,
    transcriptRows: Int = 5000,
    client: TerminalSessionClient,
) {
    private val inputFifo =
        File(context.cacheDir, "terminal-input-${UUID.randomUUID()}.fifo").apply {
            runCatching { delete() }
            Os.mkfifo(absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        }
    private val sinkArgs =
        arrayOf(
            SINK_SHELL,
            "-c",
            "cat > \"\$1\"",
            "xssh-terminal-input",
            inputFifo.absolutePath,
        )
    val session: TerminalSession =
        TerminalSession(
            SINK_SHELL,
            "/",
            sinkArgs,
            DEFAULT_ENV,
            transcriptRows,
            client,
        )

    init {
        RemoteOutputDispatcher.register(session, client)
    }

    private val pumpThread =
        Thread(
            {
                val buffer = ByteArray(4096)
                try {
                    FileInputStream(inputFifo).use { input ->
                        while (!Thread.currentThread().isInterrupted) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            io.onUserInput(buffer.copyOf(read))
                        }
                    }
                } catch (_: Throwable) {
                    // Session shutdown closes the writer side and ends this pump.
                } finally {
                    runCatching { inputFifo.delete() }
                }
            },
            "xssh-terminal-ime-pump",
        ).apply {
            isDaemon = true
            start()
        }

    fun finish() {
        RemoteOutputDispatcher.clear(session)
        session.finishIfRunning()
        pumpThread.interrupt()
        // If layout never initialized the helper process, release a reader
        // blocked opening the FIFO. O_NONBLOCK is essential: a normal writer
        // open can itself wait forever if the reader exited in the meantime.
        runCatching {
            val descriptor =
                Os.open(
                    inputFifo.absolutePath,
                    OsConstants.O_WRONLY or OsConstants.O_NONBLOCK,
                    0,
                )
            Os.close(descriptor)
        }
    }
}

/**
 * Push bytes received from the SSH channel into the emulator.
 *
 * Termux requires all emulator mutation and callbacks on the main thread. SSH
 * readers run on Dispatchers.IO, so output is copied into a per-session queue,
 * drained in bounded batches, and followed by TerminalSession's protected
 * redraw notification. The short retry covers output arriving before the view
 * has established its first terminal size.
 */
fun feedRemoteBytes(
    session: TerminalSession,
    bytes: ByteArray,
    len: Int = bytes.size,
) {
    RemoteOutputDispatcher.enqueue(session, bytes.copyOf(len.coerceIn(0, bytes.size)))
}

private object RemoteOutputDispatcher {
    private const val MAX_BATCH_BYTES = 256 * 1024
    private const val MAX_PENDING_BYTES = 8 * 1024 * 1024
    private const val INITIALIZATION_RETRY_MS = 16L
    private val THROTTLED_MARKER =
        "\r\n[xSSH: terminal output was throttled to protect memory]\r\n"
            .toByteArray(Charsets.UTF_8)

    private data class Pending(
        val chunks: ArrayDeque<ByteArray> = ArrayDeque(),
        var queuedBytes: Int = 0,
        var throttleMarkerQueued: Boolean = false,
        var scheduled: Boolean = false,
    )

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val pending = WeakHashMap<TerminalSession, Pending>()
    private val clients = WeakHashMap<TerminalSession, TerminalSessionClient>()

    fun register(
        session: TerminalSession,
        client: TerminalSessionClient,
    ) {
        synchronized(lock) { clients[session] = client }
    }

    fun enqueue(
        session: TerminalSession,
        bytes: ByteArray,
    ) {
        if (bytes.isEmpty()) return
        val schedule =
            synchronized(lock) {
                val state = pending.getOrPut(session) { Pending() }
                var dropped = false
                while (
                    state.chunks.isNotEmpty() &&
                    state.queuedBytes + bytes.size +
                    (if (state.throttleMarkerQueued) 0 else THROTTLED_MARKER.size) > MAX_PENDING_BYTES
                ) {
                    val removed = state.chunks.removeFirst()
                    state.queuedBytes -= removed.size
                    if (removed === THROTTLED_MARKER) {
                        state.throttleMarkerQueued = false
                    } else {
                        removed.fill(0)
                    }
                    dropped = true
                }
                if (state.queuedBytes + bytes.size > MAX_PENDING_BYTES) return@synchronized false
                if (dropped && !state.throttleMarkerQueued) {
                    state.chunks.addLast(THROTTLED_MARKER)
                    state.queuedBytes += THROTTLED_MARKER.size
                    state.throttleMarkerQueued = true
                }
                state.chunks.addLast(bytes)
                state.queuedBytes += bytes.size
                if (state.scheduled) {
                    false
                } else {
                    state.scheduled = true
                    true
                }
            }
        if (schedule) main.post { drain(session) }
    }

    fun clear(session: TerminalSession) {
        synchronized(lock) {
            pending.remove(session)?.chunks?.forEach { chunk ->
                if (chunk !== THROTTLED_MARKER) chunk.fill(0)
            }
            clients.remove(session)
        }
    }

    private fun drain(session: TerminalSession) {
        val emulator = session.emulator
        if (emulator == null) {
            val stillPending = synchronized(lock) { pending.containsKey(session) }
            if (stillPending) main.postDelayed({ drain(session) }, INITIALIZATION_RETRY_MS)
            return
        }

        val batch = mutableListOf<ByteArray>()
        synchronized(lock) {
            val state = pending[session] ?: return
            var bytes = 0
            while (state.chunks.isNotEmpty() && (bytes < MAX_BATCH_BYTES || batch.isEmpty())) {
                state.chunks.removeFirst().also {
                    batch += it
                    bytes += it.size
                    state.queuedBytes -= it.size
                    if (it === THROTTLED_MARKER) state.throttleMarkerQueued = false
                }
            }
        }
        batch.forEach {
            emulator.append(it, it.size)
            if (it !== THROTTLED_MARKER) it.fill(0)
        }
        val client = synchronized(lock) { clients[session] }
        runCatching { client?.onTextChanged(session) }

        val again =
            synchronized(lock) {
                val state = pending[session] ?: return
                if (state.chunks.isEmpty()) {
                    pending.remove(session)
                    false
                } else {
                    true
                }
            }
        if (again) main.post { drain(session) }
    }
}

/*
 * xSSH — SFTP transfer helpers over sshj SFTPClient.
 */
package com.xssh.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.xfer.LocalFileFilter
import net.schmizz.sshj.xfer.LocalSourceFile
import net.schmizz.sshj.xfer.TransferListener
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class SftpEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
)

class SftpBridge(private val sftp: SFTPClient) {
    /** sshj's SFTP client and FileTransfer listener are not safe for overlapping calls. */
    private val operations = Mutex()

    suspend fun list(path: String): List<SftpEntry> =
        withContext(Dispatchers.IO) {
            operations.withLock {
                sftp.ls(path)
                    .filter { it.name != "." && it.name != ".." }
                    .map { r ->
                        SftpEntry(
                            name = r.name,
                            isDir = r.attributes.type == FileMode.Type.DIRECTORY,
                            size = r.attributes.size,
                            mtime = r.attributes.mtime * 1000L,
                        )
                    }
            }
        }

    suspend fun mkdir(path: String) =
        withContext(Dispatchers.IO) {
            operations.withLock { sftp.mkdir(path) }
        }

    suspend fun rename(
        from: String,
        to: String,
    ) = withContext(Dispatchers.IO) {
        operations.withLock { sftp.rename(from, to) }
    }

    suspend fun remove(path: String) =
        withContext(Dispatchers.IO) {
            operations.withLock {
                val attrs = sftp.stat(path)
                if (attrs.type == FileMode.Type.DIRECTORY) sftp.rmdir(path) else sftp.rm(path)
            }
        }

    suspend fun stat(path: String): SftpEntry? =
        withContext(Dispatchers.IO) {
            operations.withLock {
                try {
                    val a = sftp.stat(path)
                    SftpEntry(
                        name = path.substringAfterLast('/'),
                        isDir = a.type == FileMode.Type.DIRECTORY,
                        size = a.size,
                        mtime = a.mtime * 1000L,
                    )
                } catch (failure: SFTPException) {
                    if (
                        failure.statusCode == Response.StatusCode.NO_SUCH_FILE ||
                        failure.statusCode == Response.StatusCode.NO_SUCH_PATH
                    ) {
                        null
                    } else {
                        throw failure
                    }
                }
            }
        }

    suspend fun download(
        remotePath: String,
        output: OutputStream,
        onProgress: (bytesTransferred: Long, total: Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        operations.withLock { downloadBlocking(remotePath, output, onProgress) }
    }

    private fun downloadBlocking(
        remotePath: String,
        output: OutputStream,
        onProgress: (bytesTransferred: Long, total: Long) -> Unit,
    ) {
        val handle: RemoteFile = sftp.open(remotePath)
        try {
            val total = handle.length()
            val stream = handle.RemoteFileInputStream()
            val buf = ByteArray(256 * 1024) // 256 KiB buffer for 2x-4x throughput
            var moved = 0L
            var lastReported = 0L
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                output.write(buf, 0, n)
                moved += n
                if (moved - lastReported >= 128 * 1024 || moved >= total) {
                    lastReported = moved
                    onProgress(moved, total)
                }
            }
            output.flush()
            onProgress(moved, total)
        } finally {
            runCatching { handle.close() }
            runCatching { output.close() }
        }
    }

    suspend fun upload(
        remotePath: String,
        input: InputStream,
        length: Long,
        onProgress: (bytesTransferred: Long, total: Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        operations.withLock { uploadBlocking(remotePath, input, length, onProgress) }
    }

    private fun uploadBlocking(
        remotePath: String,
        input: InputStream,
        length: Long,
        onProgress: (bytesTransferred: Long, total: Long) -> Unit,
    ) {
        var lastReported = 0L
        sftp.fileTransfer.transferListener =
            object : TransferListener {
                override fun directory(name: String?): TransferListener = this

                override fun file(
                    name: String?,
                    size: Long,
                ): net.schmizz.sshj.common.StreamCopier.Listener =
                    net.schmizz.sshj.common.StreamCopier.Listener { moved ->
                        if (moved - lastReported >= 128 * 1024 || moved >= size) {
                            lastReported = moved
                            onProgress(moved, size)
                        }
                    }
            }

        val src =
            object : LocalSourceFile {
                override fun getName(): String = remotePath.substringAfterLast('/')

                override fun getLength(): Long = length

                override fun getInputStream(): InputStream = input

                override fun getPermissions(): Int = 0b110_100_100

                override fun isFile(): Boolean = true

                override fun isDirectory(): Boolean = false

                override fun getChildren(filter: LocalFileFilter?): Iterable<LocalSourceFile> = emptyList()

                override fun providesAtimeMtime(): Boolean = false

                override fun getLastAccessTime(): Long = 0

                override fun getLastModifiedTime(): Long = 0
            }

        try {
            sftp.fileTransfer.upload(src, remotePath)
        } finally {
            sftp.fileTransfer.transferListener = null
            runCatching { input.close() }
        }
    }

    /**
     * Upload to a sibling temporary path and replace the destination only
     * after every byte succeeds. A cancelled or failed transfer therefore
     * leaves an existing remote file intact.
     */
    suspend fun uploadAtomically(
        remotePath: String,
        input: InputStream,
        length: Long,
        onProgress: (bytesTransferred: Long, total: Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        operations.withLock {
            val slash = remotePath.lastIndexOf('/')
            val parent = if (slash >= 0) remotePath.substring(0, slash + 1) else ""
            val temporary = "$parent.xssh-upload-${UUID.randomUUID()}.tmp"
            try {
                uploadBlocking(temporary, input, length, onProgress)
                val moved =
                    runCatching {
                        sftp.rename(temporary, remotePath, setOf(RenameFlags.OVERWRITE, RenameFlags.ATOMIC))
                    }.recoverCatching {
                        sftp.rename(temporary, remotePath, setOf(RenameFlags.OVERWRITE))
                    }.recoverCatching {
                        sftp.rename(temporary, remotePath)
                    }
                moved.getOrThrow()
            } finally {
                runCatching { sftp.rm(temporary) }
            }
        }
    }

    fun close() {
        runCatching { sftp.close() }
    }
}

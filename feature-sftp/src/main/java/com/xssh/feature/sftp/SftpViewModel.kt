package com.xssh.feature.sftp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xssh.core.data.dao.SftpTransferDao
import com.xssh.core.data.entity.SftpTransferEntity
import com.xssh.core.ssh.EphemeralKnownHostStore
import com.xssh.core.ssh.InteractiveHostKeyVerifier
import com.xssh.core.ssh.KnownHostStore
import com.xssh.core.ssh.SftpBridge
import com.xssh.core.ssh.SftpEntry
import com.xssh.core.ssh.SshSession
import com.xssh.feature.connections.ConnectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

data class SftpState(
    val endpoint: String? = null,
    val connected: Boolean = false,
    val path: String = "/",
    val entries: List<SftpEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val transfer: TransferState? = null,
    val queue: List<QueuedTransfer> = emptyList(),
    val editor: TextEditorState? = null,
    val externalEditorPreparing: Boolean = false,
    val externalEditor: ExternalEditorState? = null,
)

data class TransferState(
    val label: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
)

enum class TransferStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class QueuedTransfer(
    val id: String,
    val label: String,
    val direction: Direction,
    val remotePath: String,
    val localUri: String,
    val totalBytes: Long,
    val status: TransferStatus,
    val bytesTransferred: Long = 0L,
    val error: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
) {
    enum class Direction { UPLOAD, DOWNLOAD }
}

data class TextEditorState(
    val entryName: String,
    val remotePath: String,
    val originalSize: Long,
    val originalModifiedEpochMs: Long,
    val originalSha256: String,
    val originalText: String,
    val text: String,
    val saving: Boolean = false,
    val error: String? = null,
)

data class ExternalEditorState(
    val entryName: String,
    val remotePath: String,
    val localPath: String,
    val originalSize: Long,
    val originalModifiedEpochMs: Long,
    val originalSha256: String,
    val launched: Boolean = false,
    val returned: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

data class SftpKeyboardPrompt(
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

private fun QueuedTransfer.toEntity(connectionId: String): SftpTransferEntity =
    SftpTransferEntity(
        id = id,
        connectionId = connectionId,
        label = label,
        direction = direction.name,
        remotePath = remotePath,
        localUri = localUri,
        totalBytes = totalBytes,
        status = status.name,
        bytesTransferred = bytesTransferred,
        error = error,
        createdAtEpochMs = createdAtEpochMs,
    )

private fun SftpTransferEntity.toQueuedTransfer(): QueuedTransfer =
    QueuedTransfer(
        id = id,
        label = label,
        direction = QueuedTransfer.Direction.valueOf(direction),
        remotePath = remotePath,
        localUri = localUri,
        totalBytes = totalBytes,
        status = TransferStatus.valueOf(status),
        bytesTransferred = bytesTransferred,
        error = error,
        createdAtEpochMs = createdAtEpochMs,
    )

private sealed interface TransferJob {
    val id: String
    val label: String
    val remotePath: String
    val localUri: String
    val totalBytes: Long

    data class Upload(
        override val id: String,
        override val label: String,
        override val remotePath: String,
        override val localUri: String,
        override val totalBytes: Long,
    ) : TransferJob

    data class Download(
        override val id: String,
        override val label: String,
        override val remotePath: String,
        override val localUri: String,
        override val totalBytes: Long,
    ) : TransferJob
}

private fun QueuedTransfer.toJob(): TransferJob =
    when (direction) {
        QueuedTransfer.Direction.UPLOAD ->
            TransferJob.Upload(id, label, remotePath, localUri, totalBytes)
        QueuedTransfer.Direction.DOWNLOAD ->
            TransferJob.Download(id, label, remotePath, localUri, totalBytes)
    }

private class ExternalEditFileStore(cacheDirectory: File) {
    private val directory = File(cacheDirectory, "external-edit")

    fun create(entryName: String): File {
        check(directory.isDirectory || directory.mkdirs()) {
            "Unable to create the secure external-edit cache."
        }
        val extension =
            entryName.substringAfterLast('.', "txt")
                .filter(Char::isLetterOrDigit)
                .take(10)
                .ifEmpty { "txt" }
        return File.createTempFile("xssh-edit-", ".$extension", directory)
    }

    fun checked(path: String): File {
        val safeDirectory = directory.canonicalFile
        val file = File(path).canonicalFile
        check(file.parentFile == safeDirectory) { "Invalid external-edit file path." }
        check(file.isFile) { "The temporary edit file no longer exists." }
        return file
    }

    fun delete(file: File) {
        val safeDirectory = directory.canonicalFile
        val candidate = file.canonicalFile
        if (candidate.parentFile == safeDirectory) candidate.delete()
    }

    fun cleanStale(maxAgeMs: Long) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) delete(file)
        }
    }
}

private fun joinPath(
    base: String,
    name: String,
): String {
    val normalizedBase = if (base.endsWith("/")) base else "$base/"
    return "$normalizedBase$name".replace("//", "/")
}

private fun validRemoteName(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isNotEmpty() &&
        trimmed != "." &&
        trimmed != ".." &&
        '/' !in name &&
        '\u0000' !in name &&
        name.toByteArray(Charsets.UTF_8).size <= 255
}

private class LimitedByteArrayOutputStream(private val limit: Int) : ByteArrayOutputStream() {
    fun wipe() {
        buf.fill(0)
        reset()
    }

    override fun write(value: Int) {
        ensureCapacityFor(1)
        super.write(value)
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        ensureCapacityFor(length)
        super.write(bytes, offset, length)
    }

    private fun ensureCapacityFor(additional: Int) {
        if (additional < 0 || count > limit - additional) {
            throw IOException("Remote file exceeded the 256 KiB quick-edit limit.")
        }
    }
}

private class DiscardingOutputStream : OutputStream() {
    override fun write(value: Int) = Unit

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) = Unit
}

private fun ByteArray.sha256Base64(): String =
    Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(this))

private suspend fun SftpBridge.remoteSha256(
    remotePath: String,
    maxBytes: Long,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DigestOutputStream(DiscardingOutputStream(), digest).use { output ->
        download(remotePath, output) { moved, _ ->
            check(moved <= maxBytes) { "The remote file exceeded the safe edit limit." }
        }
    }
    return Base64.getEncoder().encodeToString(digest.digest())
}

private class UriPermissionStore(private val context: Context) {
    fun persist(
        uri: Uri,
        permission: Int,
    ): Boolean =
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, permission)
        }.isSuccess

    fun release(
        uri: Uri,
        permission: Int,
    ) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, permission)
        }
    }

    fun revokeExternalEditor(
        fileStore: ExternalEditFileStore,
        localPath: String,
    ) {
        runCatching {
            val file = fileStore.checked(localPath)
            val uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            context.revokeUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }
}

private const val MAX_QUICK_EDIT_BYTES = 256 * 1024
private const val MAX_QUICK_EDIT_CHARS = 256 * 1024
private const val MAX_PENDING_UPLOAD_PREPARATIONS = 4
private const val PROGRESS_PERSIST_INTERVAL_MS = 750L
private const val MAX_EXTERNAL_EDIT_BYTES = 16L * 1024L * 1024L
private const val EXTERNAL_EDIT_STALE_MS = 24L * 60L * 60L * 1000L

private class TransferPersistence(
    private val dao: SftpTransferDao,
    private val scope: CoroutineScope,
    private val connectionId: () -> String?,
) {
    private val progressWriteTimes = ConcurrentHashMap<String, Long>()

    fun upsert(row: QueuedTransfer) {
        val activeConnectionId = connectionId() ?: return
        scope.launch(Dispatchers.IO) { dao.upsert(row.toEntity(activeConnectionId)) }
    }

    suspend fun upsertNow(row: QueuedTransfer) {
        val activeConnectionId = connectionId() ?: return
        dao.upsert(row.toEntity(activeConnectionId))
    }

    fun updateProgress(
        id: String,
        moved: Long,
        total: Long,
    ) {
        val now = SystemClock.elapsedRealtime()
        val previous = progressWriteTimes[id]
        if (previous == null || now - previous >= PROGRESS_PERSIST_INTERVAL_MS || moved >= total) {
            progressWriteTimes[id] = now
            scope.launch(Dispatchers.IO) {
                dao.updateProgress(id, moved, if (total >= 0L) total else moved)
            }
        }
    }

    fun finished(id: String) {
        progressWriteTimes.remove(id)
    }
}

data class UploadOverwritePrompt(
    val name: String,
    private val decision: CompletableDeferred<Boolean>,
) {
    fun replace() {
        decision.complete(true)
    }

    fun cancel() {
        decision.complete(false)
    }
}

@HiltViewModel
class SftpViewModel
    @Inject
    constructor(
        private val repo: ConnectionRepository,
        private val knownHostStore: KnownHostStore,
        private val transferDao: SftpTransferDao,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val _state = MutableStateFlow(SftpState())
        val state: StateFlow<SftpState> = _state.asStateFlow()

        @Volatile private var session: SshSession? = null

        @Volatile private var bridge: SftpBridge? = null
        private var attachJob: Job? = null
        private val resourceLock = Any()
        private var generation: Long = 0
        private var verifier = InteractiveHostKeyVerifier(knownHostStore)
        private val _hostKeyPrompt = MutableStateFlow<InteractiveHostKeyVerifier.UnknownKey?>(null)
        val hostKeyPrompt: StateFlow<InteractiveHostKeyVerifier.UnknownKey?> = _hostKeyPrompt.asStateFlow()
        private val _hostKeyEvents = MutableStateFlow<InteractiveHostKeyVerifier.VerificationEvent?>(null)
        val hostKeyEvents: StateFlow<InteractiveHostKeyVerifier.VerificationEvent?> = _hostKeyEvents.asStateFlow()
        private val _keyboardPrompt = MutableStateFlow<SftpKeyboardPrompt?>(null)
        val keyboardPrompt: StateFlow<SftpKeyboardPrompt?> = _keyboardPrompt.asStateFlow()
        private val _overwritePrompt = MutableStateFlow<UploadOverwritePrompt?>(null)
        val overwritePrompt: StateFlow<UploadOverwritePrompt?> = _overwritePrompt.asStateFlow()
        private val overwritePromptMutex = Mutex()
        private val pendingUploadPreparations = AtomicInteger(0)

        private val jobs = Channel<TransferJob>(capacity = 32)
        private var worker: Job? = null
        private var queueObserver: Job? = null
        private var activeConnectionId: String? = null

        @Volatile private var activeTransfer: Job? = null
        private var refreshJob: Job? = null
        private val currentJobId = AtomicReference<String?>(null)
        private val activeResource = AtomicReference<Closeable?>(null)
        private val externalEditFiles = ExternalEditFileStore(context.cacheDir)
        private val uriPermissions = UriPermissionStore(context)
        private val transferPersistence = TransferPersistence(transferDao, viewModelScope) { activeConnectionId }

        init {
            viewModelScope.launch(Dispatchers.IO) {
                externalEditFiles.cleanStale(EXTERNAL_EDIT_STALE_MS)
            }
        }

        fun attach(connectionId: String) {
            observeTransferQueue(connectionId)
            val token =
                synchronized(resourceLock) {
                    if (session != null || attachJob?.isActive == true) return
                    ++generation
                }
            attachJob =
                viewModelScope.launch(Dispatchers.IO) {
                    _state.update { it.copy(loading = true, error = null) }
                    val profile =
                        repo.get(connectionId) ?: run {
                            if (isCurrent(token)) {
                                _state.update { it.copy(loading = false, error = "Profile not found") }
                            }
                            return@launch
                        }
                    if (!isCurrent(token)) return@launch
                    _state.update { it.copy(endpoint = "${profile.host}:${profile.port}") }
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
                                _keyboardPrompt.value = SftpKeyboardPrompt(prompts, deferred)
                                try {
                                    deferred.await()
                                } finally {
                                    _keyboardPrompt.value = null
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
                    val res =
                        try {
                            s.connect()
                        } finally {
                            if (isCurrent(token)) _hostKeyEvents.value = attemptVerifier.events.value
                            promptCollector.cancel()
                            eventCollector.cancel()
                        }
                    res.onFailure {
                        synchronized(resourceLock) { if (session === s) session = null }
                        s.close()
                        if (isCurrent(token)) {
                            val verificationFailure = attemptVerifier.events.value != null
                            _state.update { state ->
                                state.copy(
                                    loading = false,
                                    error = if (verificationFailure) null else it.message ?: "Connect failed",
                                )
                            }
                        }
                        return@launch
                    }
                    if (!isCurrent(token) || synchronized(resourceLock) { session !== s }) {
                        s.close()
                        return@launch
                    }
                    val sftp =
                        try {
                            s.openSftp()
                        } catch (cancelled: CancellationException) {
                            synchronized(resourceLock) { if (session === s) session = null }
                            s.close()
                            throw cancelled
                        } catch (t: Throwable) {
                            synchronized(resourceLock) { if (session === s) session = null }
                            s.close()
                            if (isCurrent(token)) {
                                _state.update { it.copy(loading = false, error = t.message ?: "Unable to open SFTP") }
                            }
                            return@launch
                        }
                    val activated =
                        synchronized(resourceLock) {
                            if (generation == token && session === s) {
                                bridge = SftpBridge(sftp)
                                true
                            } else {
                                false
                            }
                        }
                    if (!activated) {
                        runCatching { sftp.close() }
                        s.close()
                        return@launch
                    }
                    _state.update { it.copy(connected = true) }
                    if (!profile.ephemeral) repo.touch(connectionId)
                    val startDir = runCatching { sftp.canonicalize(".") }.getOrDefault("/")
                    refresh(startDir)
                    startWorker()
                }
        }

        private fun startWorker() {
            if (worker != null) return
            worker =
                viewModelScope.launch(Dispatchers.IO) {
                    for (job in jobs) {
                        val transferTask = launch { runTransfer(job) }
                        activeTransfer = transferTask
                        transferTask.join()
                        activeTransfer = null
                    }
                }
        }

        private suspend fun runTransfer(job: TransferJob) {
            currentJobId.set(job.id)
            var transferBridge: SftpBridge? = null
            var stream: Closeable? = null
            val resource =
                Closeable {
                    runCatching { stream?.close() }
                    runCatching { transferBridge?.close() }
                }
            activeResource.set(resource)
            markQueuePersisted(job.id) { it.copy(status = TransferStatus.RUNNING, error = null) }
            _state.update { it.copy(transfer = TransferState(job.label, 0, job.totalBytes)) }
            try {
                // Each transfer gets its own SFTP channel. Browsing and quick-edit
                // stay responsive, and sshj's mutable FileTransfer listener cannot
                // be overwritten by an overlapping operation.
                val activeSession =
                    synchronized(resourceLock) { session }
                        ?: error("SFTP session is closed")
                val transfer = SftpBridge(activeSession.openSftp())
                transferBridge = transfer
                val localUri = Uri.parse(job.localUri)
                when (job) {
                    is TransferJob.Upload -> {
                        val input =
                            context.contentResolver.openInputStream(localUri)
                                ?: error("The selected local file is no longer available.")
                        stream = input
                        transfer.uploadAtomically(job.remotePath, input, job.totalBytes) { moved, total ->
                            _state.update { it.copy(transfer = TransferState(job.label, moved, total)) }
                            updateTransferProgress(job.id, moved, total)
                        }
                    }
                    is TransferJob.Download -> {
                        val output =
                            context.contentResolver.openOutputStream(localUri, "wt")
                                ?: error("The selected destination file is no longer available.")
                        stream = output
                        transfer.download(job.remotePath, output) { moved, total ->
                            _state.update { it.copy(transfer = TransferState(job.label, moved, total)) }
                            updateTransferProgress(job.id, moved, total)
                        }
                    }
                }
                markQueuePersisted(job.id) {
                    it.copy(
                        status = TransferStatus.DONE,
                        bytesTransferred = maxOf(it.bytesTransferred, job.totalBytes),
                        error = null,
                    )
                }
            } catch (_: CancellationException) {
                markQueuePersisted(job.id) { it.copy(status = TransferStatus.CANCELLED) }
            } catch (t: Throwable) {
                if (queueStatus(job.id) != TransferStatus.CANCELLED) {
                    val message = t.message ?: "Transfer failed"
                    markQueuePersisted(job.id) { it.copy(status = TransferStatus.FAILED, error = message) }
                    _state.update { it.copy(error = message) }
                }
            } finally {
                runCatching { resource.close() }
                transferPersistence.finished(job.id)
                currentJobId.set(null)
                activeResource.compareAndSet(resource, null)
                _state.update { it.copy(transfer = null) }
                if (job is TransferJob.Upload) refresh()
            }
        }

        fun refresh(path: String = _state.value.path) {
            refreshJob?.cancel()
            refreshJob =
                viewModelScope.launch(Dispatchers.IO) {
                    val b = bridge ?: return@launch
                    _state.update { it.copy(loading = true) }
                    try {
                        val entries = b.list(path)
                        _state.update { it.copy(loading = false, path = path, entries = entries, error = null) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        _state.update { it.copy(loading = false, error = t.message ?: "Unable to list this folder") }
                    }
                }
        }

        fun enter(name: String) = refresh(joinPath(_state.value.path, name))

        fun goUp() {
            val cur = _state.value.path
            if (cur == "/" || cur.isEmpty()) return
            val parent = cur.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
            refresh(parent)
        }

        fun mkdir(name: String) {
            if (!validRemoteName(name)) {
                _state.update { it.copy(error = "Enter a folder name without slashes.") }
                return
            }
            val path = _state.value.path
            viewModelScope.launch(Dispatchers.IO) {
                val b = bridge ?: return@launch
                runCatching { b.mkdir(joinPath(path, name)) }
                    .onSuccess { refresh() }
                    .onFailure { failure ->
                        if (failure is CancellationException) throw failure
                        _state.update { it.copy(error = failure.message ?: "Unable to create folder") }
                    }
            }
        }

        fun rename(
            entry: SftpEntry,
            newName: String,
        ) {
            if (!validRemoteName(newName)) {
                _state.update { it.copy(error = "Enter a name without slashes.") }
                return
            }
            if (newName == entry.name) return
            val path = _state.value.path
            viewModelScope.launch(Dispatchers.IO) {
                val b = bridge ?: return@launch
                runCatching {
                    val destination = joinPath(path, newName)
                    require(b.stat(destination) == null) {
                        "An item named '$newName' already exists. Choose another name."
                    }
                    b.rename(joinPath(path, entry.name), destination)
                }
                    .onSuccess { refresh() }
                    .onFailure { failure ->
                        if (failure is CancellationException) throw failure
                        _state.update { it.copy(error = failure.message ?: "Unable to rename entry") }
                    }
            }
        }

        fun delete(entry: SftpEntry) {
            val path = _state.value.path
            viewModelScope.launch(Dispatchers.IO) {
                val b = bridge ?: return@launch
                runCatching { b.remove(joinPath(path, entry.name)) }
                    .onSuccess { refresh() }
                    .onFailure { failure ->
                        if (failure is CancellationException) throw failure
                        _state.update { it.copy(error = failure.message ?: "Unable to delete entry") }
                    }
            }
        }

        fun download(
            entry: SftpEntry,
            destinationUri: Uri,
        ) {
            if (bridge == null || !_state.value.connected) {
                _state.update { it.copy(error = "Reconnect before downloading.") }
                return
            }
            if (!uriPermissions.persist(destinationUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
                runCatching { context.contentResolver.delete(destinationUri, null, null) }
                _state.update {
                    it.copy(error = "This document provider does not allow persistent write access.")
                }
                return
            }
            val connectionId = activeConnectionId ?: return
            val id = UUID.randomUUID().toString()
            val row =
                QueuedTransfer(
                    id = id,
                    label = "Download ${entry.name}",
                    direction = QueuedTransfer.Direction.DOWNLOAD,
                    remotePath = joinPath(_state.value.path, entry.name),
                    localUri = destinationUri.toString(),
                    totalBytes = entry.size,
                    status = TransferStatus.QUEUED,
                )
            enqueueTransfer(connectionId, row)
        }

        fun upload(
            name: String,
            sourceUri: Uri,
            length: Long,
        ) {
            if (!validRemoteName(name)) {
                _state.update { it.copy(error = "The selected file has an invalid remote name.") }
                return
            }
            if (pendingUploadPreparations.incrementAndGet() > MAX_PENDING_UPLOAD_PREPARATIONS) {
                pendingUploadPreparations.decrementAndGet()
                _state.update { it.copy(error = "Too many uploads are waiting for confirmation.") }
                return
            }
            if (!uriPermissions.persist(sourceUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
                pendingUploadPreparations.decrementAndGet()
                _state.update {
                    it.copy(error = "This document provider does not allow persistent read access.")
                }
                return
            }
            val remotePath = joinPath(_state.value.path, name)
            viewModelScope.launch(Dispatchers.IO) {
                var queued = false
                try {
                    val b =
                        bridge ?: run {
                            _state.update { it.copy(error = "Reconnect before uploading.") }
                            return@launch
                        }
                    val replace =
                        overwritePromptMutex.withLock {
                            if (b.stat(remotePath) == null) {
                                true
                            } else {
                                val decision = CompletableDeferred<Boolean>()
                                val prompt = UploadOverwritePrompt(name, decision)
                                _overwritePrompt.value = prompt
                                try {
                                    decision.await()
                                } finally {
                                    if (_overwritePrompt.value === prompt) _overwritePrompt.value = null
                                }
                            }
                        }
                    if (!replace) {
                        return@launch
                    }
                    val connectionId = activeConnectionId ?: return@launch
                    val id = UUID.randomUUID().toString()
                    val row =
                        QueuedTransfer(
                            id = id,
                            label = "Upload $name",
                            direction = QueuedTransfer.Direction.UPLOAD,
                            remotePath = remotePath,
                            localUri = sourceUri.toString(),
                            totalBytes = length,
                            status = TransferStatus.QUEUED,
                        )
                    enqueueTransfer(connectionId, row)
                    queued = true
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    _state.update { it.copy(error = failure.message ?: "Unable to prepare this upload.") }
                } finally {
                    if (!queued) {
                        uriPermissions.release(sourceUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    pendingUploadPreparations.decrementAndGet()
                }
            }
        }

        fun openTextEditor(entry: SftpEntry) {
            if (entry.isDir) return
            if (entry.size > 256 * 1024) {
                _state.update { it.copy(error = "Quick edit is limited to files ≤ 256 KiB.") }
                return
            }
            val path = _state.value.path
            viewModelScope.launch(Dispatchers.IO) {
                val b = bridge ?: return@launch
                val remote = joinPath(path, entry.name)
                val out = LimitedByteArrayOutputStream(MAX_QUICK_EDIT_BYTES)
                runCatching {
                    b.download(remote, out) { _, _ -> }
                    val decoder =
                        Charsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                    val encoded = out.toByteArray()
                    val originalSha256 = encoded.sha256Base64()
                    val text =
                        try {
                            decoder.decode(ByteBuffer.wrap(encoded)).toString()
                        } finally {
                            encoded.fill(0)
                            out.wipe()
                        }
                    _state.update {
                        it.copy(
                            editor =
                                TextEditorState(
                                    entryName = entry.name,
                                    remotePath = remote,
                                    originalSize = entry.size,
                                    originalModifiedEpochMs = entry.mtime,
                                    originalSha256 = originalSha256,
                                    originalText = text,
                                    text = text,
                                ),
                        )
                    }
                }.also {
                    out.wipe()
                }.onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    _state.update { it.copy(error = failure.message ?: "Unable to open this text file") }
                }
            }
        }

        fun updateEditorText(text: String) {
            if (text.length > MAX_QUICK_EDIT_CHARS) return
            _state.update { current ->
                val editor = current.editor ?: return@update current
                current.copy(editor = editor.copy(text = text, error = null))
            }
        }

        fun saveEditor() {
            val editor = _state.value.editor ?: return
            _state.update { current ->
                val active = current.editor
                if (active?.remotePath == editor.remotePath) {
                    current.copy(editor = active.copy(saving = true, error = null))
                } else {
                    current
                }
            }
            viewModelScope.launch(Dispatchers.IO) {
                val b =
                    bridge ?: run {
                        _state.update { current ->
                            current.copy(
                                editor =
                                    current.editor?.copy(
                                        saving = false,
                                        error = "Reconnect before saving.",
                                    ),
                            )
                        }
                        return@launch
                    }
                runCatching {
                    val bytes = editor.text.toByteArray(Charsets.UTF_8)
                    try {
                        require(bytes.size <= MAX_QUICK_EDIT_BYTES) {
                            "Edited text is larger than the 256 KiB quick-edit limit."
                        }
                        val current =
                            b.stat(editor.remotePath)
                                ?: error("The remote file no longer exists.")
                        require(
                            current.size == editor.originalSize &&
                                current.mtime == editor.originalModifiedEpochMs,
                        ) {
                            "The file changed on the server. Reopen it before saving to avoid overwriting newer work."
                        }
                        require(
                            b.remoteSha256(editor.remotePath, MAX_QUICK_EDIT_BYTES.toLong()) ==
                                editor.originalSha256,
                        ) {
                            "The file contents changed on the server. Reopen it before saving."
                        }
                        b.uploadAtomically(editor.remotePath, bytes.inputStream(), bytes.size.toLong()) { _, _ -> }
                    } finally {
                        bytes.fill(0)
                    }
                    _state.update { it.copy(editor = null) }
                    refresh()
                }.onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    val current = _state.value.editor ?: return@onFailure
                    _state.update { it.copy(editor = current.copy(saving = false, error = failure.message)) }
                }
            }
        }

        fun closeEditor() {
            _state.update { it.copy(editor = null) }
        }

        fun prepareExternalEditor(entry: SftpEntry) {
            if (entry.isDir) return
            if (entry.size > MAX_EXTERNAL_EDIT_BYTES) {
                _state.update { it.copy(error = "External edit is limited to files no larger than 16 MiB.") }
                return
            }
            if (_state.value.externalEditor != null || _state.value.externalEditorPreparing) {
                _state.update { it.copy(error = "Finish the current external edit first.") }
                return
            }
            val remotePath = joinPath(_state.value.path, entry.name)
            _state.update { it.copy(externalEditorPreparing = true, error = null) }
            viewModelScope.launch(Dispatchers.IO) {
                var localFile: File? = null
                try {
                    val b = bridge ?: error("Reconnect before opening an external editor.")
                    localFile = externalEditFiles.create(entry.name)
                    val digest = MessageDigest.getInstance("SHA-256")
                    DigestOutputStream(FileOutputStream(localFile), digest).use { output ->
                        b.download(remotePath, output) { moved, _ ->
                            check(moved <= MAX_EXTERNAL_EDIT_BYTES) {
                                "Remote file exceeded the 16 MiB external-edit limit."
                            }
                        }
                    }
                    _state.update {
                        it.copy(
                            externalEditorPreparing = false,
                            externalEditor =
                                ExternalEditorState(
                                    entryName = entry.name,
                                    remotePath = remotePath,
                                    localPath = localFile.absolutePath,
                                    originalSize = entry.size,
                                    originalModifiedEpochMs = entry.mtime,
                                    originalSha256 = Base64.getEncoder().encodeToString(digest.digest()),
                                ),
                        )
                    }
                } catch (failure: CancellationException) {
                    localFile?.let(externalEditFiles::delete)
                    _state.update { it.copy(externalEditorPreparing = false) }
                    throw failure
                } catch (failure: Throwable) {
                    localFile?.let(externalEditFiles::delete)
                    _state.update {
                        it.copy(
                            externalEditorPreparing = false,
                            error = failure.message ?: "Unable to prepare the external editor.",
                        )
                    }
                }
            }
        }

        fun externalEditorLaunched() {
            _state.update { state ->
                state.copy(externalEditor = state.externalEditor?.copy(launched = true))
            }
        }

        fun externalEditorReturned() {
            _state.value.externalEditor?.let { uriPermissions.revokeExternalEditor(externalEditFiles, it.localPath) }
            _state.update { state ->
                state.copy(externalEditor = state.externalEditor?.copy(returned = true))
            }
        }

        fun externalEditorLaunchFailed(message: String) {
            _state.value.externalEditor?.let { uriPermissions.revokeExternalEditor(externalEditFiles, it.localPath) }
            _state.update { state ->
                state.copy(
                    externalEditor =
                        state.externalEditor?.copy(
                            returned = true,
                            error = message,
                        ),
                )
            }
        }

        fun saveExternalEdit() {
            val edit = _state.value.externalEditor ?: return
            _state.update { state ->
                state.copy(externalEditor = state.externalEditor?.copy(saving = true, error = null))
            }
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val b = bridge ?: error("Reconnect before saving the edited file.")
                    val localFile = externalEditFiles.checked(edit.localPath)
                    check(localFile.length() <= MAX_EXTERNAL_EDIT_BYTES) {
                        "The edited file is larger than the 16 MiB external-edit limit."
                    }
                    val current = b.stat(edit.remotePath) ?: error("The remote file no longer exists.")
                    check(
                        current.size == edit.originalSize &&
                            current.mtime == edit.originalModifiedEpochMs,
                    ) {
                        "The file changed on the server. Reopen it before saving to avoid overwriting newer work."
                    }
                    check(
                        b.remoteSha256(edit.remotePath, MAX_EXTERNAL_EDIT_BYTES) == edit.originalSha256,
                    ) {
                        "The file contents changed on the server. Reopen it before saving."
                    }
                    FileInputStream(localFile).use { input ->
                        b.uploadAtomically(edit.remotePath, input, localFile.length()) { _, _ -> }
                    }
                    externalEditFiles.delete(localFile)
                    _state.update { it.copy(externalEditor = null) }
                    refresh()
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    _state.update { state ->
                        state.copy(
                            externalEditor =
                                state.externalEditor?.copy(
                                    saving = false,
                                    error = failure.message ?: "Unable to save external edits.",
                                ),
                        )
                    }
                }
            }
        }

        fun discardExternalEdit() {
            _state.value.externalEditor?.let { uriPermissions.revokeExternalEditor(externalEditFiles, it.localPath) }
            _state.value.externalEditor?.localPath?.let { path ->
                runCatching { externalEditFiles.delete(externalEditFiles.checked(path)) }
            }
            _state.update { it.copy(externalEditor = null, externalEditorPreparing = false) }
        }

        fun cancelCurrent() {
            val id = currentJobId.get() ?: return
            markQueue(id) { it.copy(status = TransferStatus.CANCELLED) }
            activeTransfer?.cancel()
            runCatching { activeResource.getAndSet(null)?.close() }
        }

        fun clearQueueEntry(id: String) {
            if (currentJobId.get() == id) return
            _state.update { it.copy(queue = it.queue.filterNot { row -> row.id == id }) }
            viewModelScope.launch(Dispatchers.IO) { transferDao.deleteById(id) }
        }

        fun clearFinishedQueue() {
            _state.update { state ->
                state.copy(
                    queue =
                        state.queue.filter {
                            it.status == TransferStatus.RUNNING || it.status == TransferStatus.QUEUED
                        },
                )
            }
            activeConnectionId?.let { connectionId ->
                viewModelScope.launch(Dispatchers.IO) { transferDao.clearFinished(connectionId) }
            }
        }

        fun retryTransfer(id: String) {
            if (bridge == null || !_state.value.connected) {
                _state.update { it.copy(error = "Reconnect before retrying this transfer.") }
                return
            }
            val connectionId = activeConnectionId ?: return
            val row =
                _state.value.queue.firstOrNull { it.id == id }
                    ?: return
            if (row.status !in setOf(TransferStatus.FAILED, TransferStatus.CANCELLED)) return
            viewModelScope.launch(Dispatchers.IO) {
                if (row.direction == QueuedTransfer.Direction.UPLOAD) {
                    val b = bridge ?: return@launch
                    val replace =
                        overwritePromptMutex.withLock {
                            if (b.stat(row.remotePath) == null) {
                                true
                            } else {
                                val decision = CompletableDeferred<Boolean>()
                                val prompt = UploadOverwritePrompt(row.remotePath.substringAfterLast('/'), decision)
                                _overwritePrompt.value = prompt
                                try {
                                    decision.await()
                                } finally {
                                    if (_overwritePrompt.value === prompt) _overwritePrompt.value = null
                                }
                            }
                        }
                    if (!replace) return@launch
                }
                enqueueTransfer(
                    connectionId,
                    row.copy(
                        status = TransferStatus.QUEUED,
                        bytesTransferred = 0L,
                        error = null,
                    ),
                )
            }
        }

        fun clearError() = _state.update { it.copy(error = null) }

        fun reportError(message: String) = _state.update { it.copy(error = message) }

        fun detach() {
            attachJob?.cancel()
            attachJob = null
            synchronized(resourceLock) { generation++ }
            viewModelScope.launch(Dispatchers.IO) {
                closeResources()
            }
        }

        fun reconnect(connectionId: String) {
            attachJob?.cancel()
            attachJob = null
            synchronized(resourceLock) { generation++ }
            viewModelScope.launch(Dispatchers.IO) {
                closeResources()
                withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { attach(connectionId) }
            }
        }

        override fun onCleared() {
            attachJob?.cancel()
            attachJob = null
            synchronized(resourceLock) { generation++ }
            queueObserver?.cancel()
            queueObserver = null
            discardExternalEdit()
            closeResources()
            super.onCleared()
        }

        private fun observeTransferQueue(connectionId: String) {
            if (activeConnectionId == connectionId && queueObserver?.isActive == true) return
            activeConnectionId = connectionId
            queueObserver?.cancel()
            queueObserver =
                viewModelScope.launch(Dispatchers.IO) {
                    transferDao.markInterrupted(connectionId)
                    transferDao.observeForConnection(connectionId).collect { persisted ->
                        _state.update { state ->
                            state.copy(queue = persisted.map { it.toQueuedTransfer() })
                        }
                    }
                }
        }

        private fun enqueueTransfer(
            connectionId: String,
            row: QueuedTransfer,
        ) {
            _state.update { state ->
                state.copy(queue = listOf(row) + state.queue.filterNot { it.id == row.id })
            }
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { transferDao.upsert(row.toEntity(connectionId)) }
                    .onFailure { failure ->
                        markQueue(row.id, persist = false) {
                            it.copy(
                                status = TransferStatus.FAILED,
                                error = failure.message ?: "Unable to persist this transfer.",
                            )
                        }
                        return@launch
                    }
                if (!jobs.trySend(row.toJob()).isSuccess) {
                    markQueue(row.id) {
                        it.copy(
                            status = TransferStatus.FAILED,
                            error = "Transfer queue is full",
                        )
                    }
                }
            }
        }

        private fun updateTransferProgress(
            id: String,
            moved: Long,
            total: Long,
        ) {
            markQueue(id, persist = false) {
                it.copy(
                    bytesTransferred = moved,
                    totalBytes = if (total >= 0L) total else it.totalBytes,
                )
            }
            transferPersistence.updateProgress(id, moved, total)
        }

        private fun markQueue(
            id: String,
            persist: Boolean = true,
            transform: (QueuedTransfer) -> QueuedTransfer,
        ): QueuedTransfer? {
            var updated: QueuedTransfer? = null
            _state.update { state ->
                state.copy(
                    queue =
                        state.queue.map {
                            if (it.id == id) {
                                transform(it).also { row -> updated = row }
                            } else {
                                it
                            }
                        },
                )
            }
            if (persist) updated?.let(transferPersistence::upsert)
            return updated
        }

        private suspend fun markQueuePersisted(
            id: String,
            transform: (QueuedTransfer) -> QueuedTransfer,
        ) {
            val row = markQueue(id, persist = false, transform = transform) ?: return
            transferPersistence.upsertNow(row)
        }

        private fun queueStatus(id: String): TransferStatus? = _state.value.queue.firstOrNull { it.id == id }?.status

        fun acceptHostKey(key: InteractiveHostKeyVerifier.UnknownKey) = verifier.acceptPending(key)

        fun rejectHostKey(key: InteractiveHostKeyVerifier.UnknownKey) = verifier.rejectPending(key)

        fun clearHostKeyEvent() {
            verifier.clearEvent()
            _hostKeyEvents.value = null
        }

        fun forgetHostKey(
            hostPort: String,
            connectionId: String,
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                knownHostStore.delete(hostPort)
                verifier.clearEvent()
                _hostKeyEvents.value = null
                withContext(Dispatchers.Main.immediate) { reconnect(connectionId) }
            }
        }

        private fun closeResources() {
            refreshJob?.cancel()
            refreshJob = null
            activeTransfer?.cancel()
            activeTransfer = null
            runCatching { activeResource.getAndSet(null)?.close() }
            worker?.cancel()
            worker = null
            while (true) {
                val queued = jobs.tryReceive().getOrNull() ?: break
                markQueue(queued.id) { it.copy(status = TransferStatus.CANCELLED) }
            }
            _keyboardPrompt.value?.cancel()
            _keyboardPrompt.value = null
            _overwritePrompt.value?.cancel()
            _overwritePrompt.value = null
            verifier.cancel()
            val resources =
                synchronized(resourceLock) {
                    (bridge to session).also {
                        bridge = null
                        session = null
                    }
                }
            runCatching { resources.first?.close() }
            runCatching { resources.second?.close() }
            currentJobId.set(null)
            _state.update {
                it.copy(
                    connected = false,
                    loading = false,
                    path = "/",
                    entries = emptyList(),
                    transfer = null,
                )
            }
        }

        private fun isCurrent(token: Long): Boolean = synchronized(resourceLock) { generation == token }
    }

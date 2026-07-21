package com.xssh.feature.sftp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xssh.core.ssh.EphemeralKnownHostStore
import com.xssh.core.ssh.InteractiveHostKeyVerifier
import com.xssh.core.ssh.KnownHostStore
import com.xssh.core.ssh.SftpBridge
import com.xssh.core.ssh.SftpEntry
import com.xssh.core.ssh.SshSession
import com.xssh.feature.connections.ConnectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
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
    val totalBytes: Long,
    val status: TransferStatus,
    val bytesTransferred: Long = 0L,
    val error: String? = null,
) {
    enum class Direction { UPLOAD, DOWNLOAD }
}

data class TextEditorState(
    val entryName: String,
    val remotePath: String,
    val originalSize: Long,
    val originalModifiedEpochMs: Long,
    val originalText: String,
    val text: String,
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

        private sealed interface TransferJob {
            val id: String
            val label: String
            val remotePath: String
            val totalBytes: Long

            data class Upload(
                override val id: String,
                override val label: String,
                override val remotePath: String,
                override val totalBytes: Long,
                val input: InputStream,
            ) : TransferJob

            data class Download(
                override val id: String,
                override val label: String,
                override val remotePath: String,
                override val totalBytes: Long,
                val output: OutputStream,
                val discardPartial: () -> Unit,
            ) : TransferJob
        }

        private val jobs = Channel<TransferJob>(capacity = 32)
        private var worker: Job? = null

        @Volatile private var activeTransfer: Job? = null
        private var refreshJob: Job? = null
        private val currentJobId = AtomicReference<String?>(null)
        private val activeResource = AtomicReference<Closeable?>(null)

        fun attach(connectionId: String) {
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
            val stream: Closeable =
                when (job) {
                    is TransferJob.Upload -> job.input
                    is TransferJob.Download -> job.output
                }
            var transferBridge: SftpBridge? = null
            val resource =
                Closeable {
                    runCatching { stream.close() }
                    runCatching { transferBridge?.close() }
                }
            activeResource.set(resource)
            markQueue(job.id) { it.copy(status = TransferStatus.RUNNING) }
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
                when (job) {
                    is TransferJob.Upload ->
                        transfer.uploadAtomically(job.remotePath, job.input, job.totalBytes) { moved, total ->
                            _state.update { it.copy(transfer = TransferState(job.label, moved, total)) }
                            markQueue(job.id) { it.copy(bytesTransferred = moved) }
                        }
                    is TransferJob.Download ->
                        transfer.download(job.remotePath, job.output) { moved, total ->
                            _state.update { it.copy(transfer = TransferState(job.label, moved, total)) }
                            markQueue(job.id) { it.copy(bytesTransferred = moved) }
                        }
                }
                markQueue(job.id) { it.copy(status = TransferStatus.DONE) }
            } catch (_: CancellationException) {
                markQueue(job.id) { it.copy(status = TransferStatus.CANCELLED) }
            } catch (t: Throwable) {
                if (queueStatus(job.id) != TransferStatus.CANCELLED) {
                    val message = t.message ?: "Transfer failed"
                    markQueue(job.id) { it.copy(status = TransferStatus.FAILED, error = message) }
                    _state.update { it.copy(error = message) }
                }
            } finally {
                runCatching { resource.close() }
                if (job is TransferJob.Download && queueStatus(job.id) != TransferStatus.DONE) {
                    runCatching { job.discardPartial() }
                }
                currentJobId.set(null)
                activeResource.compareAndSet(resource, null)
                _state.update { it.copy(transfer = null) }
                if (job is TransferJob.Upload) refresh()
            }
        }

        private fun closeJob(job: TransferJob) {
            when (job) {
                is TransferJob.Upload -> runCatching { job.input.close() }
                is TransferJob.Download -> {
                    runCatching { job.output.close() }
                    runCatching { job.discardPartial() }
                }
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
            output: OutputStream,
            discardPartial: () -> Unit = {},
        ) {
            if (bridge == null || !_state.value.connected) {
                runCatching { output.close() }
                runCatching { discardPartial() }
                _state.update { it.copy(error = "Reconnect before downloading.") }
                return
            }
            val id = UUID.randomUUID().toString()
            val row =
                QueuedTransfer(
                    id,
                    "Download ${entry.name}",
                    QueuedTransfer.Direction.DOWNLOAD,
                    joinPath(_state.value.path, entry.name),
                    entry.size,
                    TransferStatus.QUEUED,
                )
            _state.update { it.copy(queue = listOf(row) + it.queue) }
            val job = TransferJob.Download(id, row.label, row.remotePath, entry.size, output, discardPartial)
            val ok = jobs.trySend(job).isSuccess
            if (!ok) {
                closeJob(job)
                markQueue(id) { it.copy(status = TransferStatus.FAILED, error = "Transfer queue is full") }
            }
        }

        fun upload(
            name: String,
            input: InputStream,
            length: Long,
        ) {
            if (!validRemoteName(name)) {
                runCatching { input.close() }
                _state.update { it.copy(error = "The selected file has an invalid remote name.") }
                return
            }
            if (pendingUploadPreparations.incrementAndGet() > MAX_PENDING_UPLOAD_PREPARATIONS) {
                pendingUploadPreparations.decrementAndGet()
                runCatching { input.close() }
                _state.update { it.copy(error = "Too many uploads are waiting for confirmation.") }
                return
            }
            val remotePath = joinPath(_state.value.path, name)
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val b =
                        bridge ?: run {
                            runCatching { input.close() }
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
                        runCatching { input.close() }
                        return@launch
                    }
                    val id = UUID.randomUUID().toString()
                    val row =
                        QueuedTransfer(
                            id,
                            "Upload $name",
                            QueuedTransfer.Direction.UPLOAD,
                            remotePath,
                            length,
                            TransferStatus.QUEUED,
                        )
                    _state.update { it.copy(queue = listOf(row) + it.queue) }
                    val job = TransferJob.Upload(id, row.label, row.remotePath, length, input)
                    val ok = jobs.trySend(job).isSuccess
                    if (!ok) {
                        closeJob(job)
                        markQueue(id) { it.copy(status = TransferStatus.FAILED, error = "Transfer queue is full") }
                    }
                } catch (failure: CancellationException) {
                    runCatching { input.close() }
                    throw failure
                } catch (failure: Throwable) {
                    runCatching { input.close() }
                    _state.update { it.copy(error = failure.message ?: "Unable to prepare this upload.") }
                } finally {
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

        fun cancelCurrent() {
            val id = currentJobId.get() ?: return
            markQueue(id) { it.copy(status = TransferStatus.CANCELLED) }
            activeTransfer?.cancel()
            runCatching { activeResource.getAndSet(null)?.close() }
        }

        fun clearQueueEntry(id: String) {
            if (currentJobId.get() == id) return
            _state.update { it.copy(queue = it.queue.filterNot { row -> row.id == id }) }
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
        }

        fun clearError() {
            _state.update { it.copy(error = null) }
        }

        fun reportError(message: String) {
            _state.update { it.copy(error = message) }
        }

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
            closeResources()
            super.onCleared()
        }

        private fun markQueue(
            id: String,
            transform: (QueuedTransfer) -> QueuedTransfer,
        ) {
            _state.update { state ->
                state.copy(queue = state.queue.map { if (it.id == id) transform(it) else it })
            }
        }

        private fun queueStatus(id: String): TransferStatus? = _state.value.queue.firstOrNull { it.id == id }?.status

        private fun joinPath(
            base: String,
            name: String,
        ): String {
            val b = if (base.endsWith("/")) base else "$base/"
            return "$b$name".replace("//", "/")
        }

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
                closeJob(queued)
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

        private fun validRemoteName(name: String): Boolean {
            val trimmed = name.trim()
            return trimmed.isNotEmpty() &&
                trimmed != "." &&
                trimmed != ".." &&
                '/' !in name &&
                '\u0000' !in name &&
                name.toByteArray(Charsets.UTF_8).size <= 255
        }

        private fun isCurrent(token: Long): Boolean = synchronized(resourceLock) { generation == token }

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

        private companion object {
            const val MAX_QUICK_EDIT_BYTES = 256 * 1024
            const val MAX_QUICK_EDIT_CHARS = 256 * 1024
            const val MAX_PENDING_UPLOAD_PREPARATIONS = 4
        }
    }

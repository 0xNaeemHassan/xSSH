package com.xssh.feature.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.xssh.core.data.XSshDatabase
import com.xssh.core.data.dao.SnippetDao
import com.xssh.core.data.dao.TunnelDao
import com.xssh.core.data.entity.SnippetEntity
import com.xssh.core.data.entity.TunnelEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class TransferUiState(
    val busy: Boolean = false,
    val status: String = "",
    val details: List<String> = emptyList(),
)

@HiltViewModel
class ConnectionTransferViewModel
    @Inject
    constructor(
        private val repo: ConnectionRepository,
        private val tunnelDao: TunnelDao,
        private val snippetDao: SnippetDao,
        private val database: XSshDatabase,
    ) : ViewModel() {
        private val _state = MutableStateFlow(TransferUiState())
        val state: StateFlow<TransferUiState> = _state.asStateFlow()

        fun exportBundle(onReady: suspend (String) -> Unit) {
            if (!startTransfer()) return
            viewModelScope.launch {
                runTransfer("Exported xSSH bundle") {
                    val snapshot = snapshot()
                    onReady(ProfileTransferCodec.encodeBundle(snapshot))
                    ImportResult(
                        source = "xSSH bundle export",
                        importedConnections = snapshot.connections.size,
                        importedTunnels = snapshot.tunnels.size,
                        importedSnippets = snapshot.snippets.size,
                        warnings = listOf("Secrets were intentionally omitted from the export file."),
                    )
                }
            }
        }

        fun exportOpenSshConfig(onReady: suspend (String) -> Unit) {
            if (!startTransfer()) return
            viewModelScope.launch {
                runTransfer("Exported OpenSSH config") {
                    val connections = repo.observeAll().first().sortedBy { it.name.lowercase() }
                    onReady(ProfileTransferCodec.encodeOpenSshConfig(connections))
                    ImportResult(
                        source = "OpenSSH config export",
                        importedConnections = connections.size,
                        importedTunnels = 0,
                        importedSnippets = 0,
                        warnings = listOf("Passwords and private keys are never written to OpenSSH exports."),
                    )
                }
            }
        }

        fun importBundle(text: String) {
            if (!startTransfer()) return
            viewModelScope.launch {
                runTransfer("Imported xSSH bundle") {
                    val snapshot = ProfileTransferCodec.decodeBundle(text)
                    val currentConnectionIds = repo.observeAll().first().map { it.id }.toSet()
                    val currentTunnelIds = tunnelDao.observeAll().first().map { it.id }.toMutableSet()
                    val currentSnippetIds = snippetDao.observeAll().first().map { it.id }.toMutableSet()
                    val remappedConnectionIds =
                        snapshot.connections.associate { profile ->
                            profile.id to
                                if (profile.id in currentConnectionIds) {
                                    java.util.UUID.randomUUID().toString()
                                } else {
                                    profile.id
                                }
                        }
                    database.withTransaction {
                        snapshot.connections.forEach { profile ->
                            repo.upsert(profile.copy(id = remappedConnectionIds.getValue(profile.id)))
                        }
                        snapshot.tunnels
                            .filter { it.connectionId in remappedConnectionIds }
                            .forEach { tunnel ->
                                var targetId = tunnel.id
                                while (targetId in currentTunnelIds) targetId = java.util.UUID.randomUUID().toString()
                                currentTunnelIds += targetId
                                tunnelDao.upsert(
                                    tunnel.copy(
                                        id = targetId,
                                        connectionId = remappedConnectionIds.getValue(tunnel.connectionId),
                                    ),
                                )
                            }
                        snapshot.snippets.forEach { snippet ->
                            var targetId = snippet.id
                            while (targetId in currentSnippetIds) targetId = java.util.UUID.randomUUID().toString()
                            currentSnippetIds += targetId
                            snippetDao.upsert(snippet.copy(id = targetId))
                        }
                    }
                    val collisionCount = remappedConnectionIds.count { (source, target) -> source != target }
                    ImportResult(
                        source = "xSSH bundle",
                        importedConnections = snapshot.connections.size,
                        importedTunnels = snapshot.tunnels.size,
                        importedSnippets = snapshot.snippets.size,
                        warnings =
                            buildList {
                                add("Imported profiles require secrets to be re-entered before first use.")
                                if (collisionCount > 0) {
                                    add(
                                        "$collisionCount profile ID collision(s) were imported as new " +
                                            "copies to protect existing credentials.",
                                    )
                                }
                                snapshot.connectionSecrets.values
                                    .any { it.hasPassword || it.hasPrivateKey }
                                    .takeIf { it }
                                    ?.let {
                                        add(
                                            "Source bundle reported secret-backed profiles; the secret " +
                                                "material itself was not imported.",
                                        )
                                    }
                            },
                    )
                }
            }
        }

        fun importOpenSshConfig(text: String) {
            if (!startTransfer()) return
            viewModelScope.launch {
                runTransfer("Imported OpenSSH config") {
                    val (profiles, warnings) = ProfileTransferCodec.decodeOpenSshConfig(text)
                    val existing = repo.observeAll().first()
                    var authConflictCopies = 0
                    database.withTransaction {
                        profiles.forEach { parsed ->
                            val match =
                                existing.firstOrNull {
                                    it.name.equals(parsed.name, ignoreCase = true) &&
                                        it.host.equals(parsed.host, ignoreCase = true) &&
                                        it.port == parsed.port &&
                                        it.username == parsed.username
                                }
                            val merged =
                                when {
                                    match == null -> parsed
                                    match.auth == parsed.auth ->
                                        parsed.copy(
                                            id = match.id,
                                            lastUsedEpochMs = match.lastUsedEpochMs,
                                        )
                                    else -> {
                                        authConflictCopies++
                                        parsed
                                    }
                                }
                            repo.upsert(merged)
                        }
                    }
                    ImportResult(
                        source = "OpenSSH / JuiceSSH-style config",
                        importedConnections = profiles.size,
                        importedTunnels = 0,
                        importedSnippets = 0,
                        warnings =
                            warnings +
                                buildList {
                                    add(
                                        "Identity paths are metadata only on Android imports; " +
                                            "re-import the private key into xSSH if needed.",
                                    )
                                    if (authConflictCopies > 0) {
                                        add(
                                            "$authConflictCopies matching profile(s) used a different " +
                                                "authentication method and were imported as copies to " +
                                                "protect saved credentials.",
                                        )
                                    }
                                },
                    )
                }
            }
        }

        private suspend fun snapshot(): TransferSnapshot {
            val connections = repo.observeAll().first().sortedBy { it.name.lowercase() }
            val tunnels =
                tunnelDao.observeAll().first().sortedWith(
                    compareBy(TunnelEntity::connectionId, TunnelEntity::bindPort),
                )
            val snippets = snippetDao.observeAll().first().sortedBy(SnippetEntity::label)
            val secrets =
                connections.associate { profile ->
                    profile.id to
                        SecretPresence(
                            hasPassword = repo.hasPassword(profile.id),
                            hasPrivateKey = repo.hasPrivateKey(profile.id),
                        )
                }
            return TransferSnapshot(
                connections = connections,
                tunnels = tunnels,
                snippets = snippets,
                connectionSecrets = secrets,
            )
        }

        private suspend fun runTransfer(
            successPrefix: String,
            block: suspend () -> ImportResult,
        ) {
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess { result ->
                    _state.value =
                        TransferUiState(
                            busy = false,
                            status =
                                "$successPrefix: ${result.importedConnections} connections, " +
                                    "${result.importedTunnels} tunnels, " +
                                    "${result.importedSnippets} snippets.",
                            details = result.warnings,
                        )
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _state.value =
                        TransferUiState(
                            busy = false,
                            status = error.message ?: "Transfer failed.",
                            details = emptyList(),
                        )
                }
        }

        fun reportIoFailure(message: String) {
            _state.value = TransferUiState(busy = false, status = message)
        }

        private fun startTransfer(): Boolean {
            while (true) {
                val current = _state.value
                if (current.busy) return false
                if (_state.compareAndSet(current, TransferUiState(busy = true, status = "Working…"))) return true
            }
        }
    }

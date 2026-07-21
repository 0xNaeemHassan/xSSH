/*
 * xSSH — TunnelsViewModel: presenter for the Tunnels screen.
 *
 * Combines saved [TunnelRecord]s from Room with live [TunnelRuntime] state from
 * [TunnelManager] into a single flat [TunnelRow] list ready for the Compose UI.
 *
 * The screen is intentionally global (all connections), because that is how
 * users think about tunnels: "which of my forwards is up right now".
 */
package com.xssh.feature.tunnels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xssh.core.ssh.SshConnectionProfile
import com.xssh.core.ssh.Tunnel
import com.xssh.feature.connections.ConnectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row shown in the tunnels list. */
data class TunnelRow(
    val record: TunnelRecord,
    val runtime: TunnelRuntime,
    val connectionName: String,
)

data class TunnelsUiState(
    val rows: List<TunnelRow> = emptyList(),
    val connections: List<SshConnectionProfile> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class TunnelsViewModel
    @Inject
    constructor(
        private val repo: TunnelRepository,
        private val connections: ConnectionRepository,
        private val manager: TunnelManager,
    ) : ViewModel() {
        val hostKeyPrompt = manager.hostKeyPrompt
        val verificationEvent = manager.verificationEvent
        val keyboardPrompt = manager.keyboardPrompt

        private val errorState = MutableStateFlow<String?>(null)

        val state: StateFlow<TunnelsUiState> =
            combine(
                repo.observeAll(),
                manager.runtimes,
                connections.observeAll(),
                errorState,
            ) { records, runtimes, conns, err ->
                val nameById = conns.associate { it.id to it.name }
                val rows =
                    records.map { r ->
                        TunnelRow(
                            record = r,
                            runtime = runtimes[r.tunnel.id] ?: TunnelRuntime(r.tunnel.id),
                            connectionName = nameById[r.tunnel.connectionId] ?: "(deleted connection)",
                        )
                    }
                TunnelsUiState(rows = rows, connections = conns, loading = false, error = err)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TunnelsUiState())

        fun clearError() {
            errorState.value = null
        }

        /** Save a new tunnel definition (does not start it). */
        fun save(record: TunnelRecord) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    errorState.value = null
                    repo.byId(record.tunnel.id)?.let { previous ->
                        val runtime = manager.runtimeOf(previous.tunnel.id)
                        if (runtime?.running == true || runtime?.starting == true) {
                            manager.stop(previous.tunnel.id, previous.tunnel.connectionId)
                        }
                    }
                    repo.upsert(record)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    errorState.value = failure.message ?: "Failed to save tunnel"
                }
            }
        }

        /** Delete a tunnel definition; stops it first if running. */
        fun delete(record: TunnelRecord) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    errorState.value = null
                    manager.stop(record.tunnel.id, record.tunnel.connectionId)
                    repo.delete(record)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    errorState.value = failure.message ?: "Failed to delete tunnel"
                }
            }
        }

        fun start(record: TunnelRecord) = manager.start(record.tunnel)

        fun stop(record: TunnelRecord) {
            viewModelScope.launch(Dispatchers.IO) {
                manager.stop(record.tunnel.id, record.tunnel.connectionId)
            }
        }

        fun toggle(row: TunnelRow) {
            if (row.runtime.starting || row.runtime.running) stop(row.record) else start(row.record)
        }

        /** Build a new tunnel skeleton to seed the edit sheet with. */
        fun newTunnel(connectionId: String): TunnelRecord =
            TunnelRecord(
                tunnel =
                    Tunnel(
                        connectionId = connectionId,
                        kind = Tunnel.Kind.LOCAL,
                        bindHost = "127.0.0.1",
                        bindPort = 8080,
                        destHost = "localhost",
                        destPort = 80,
                    ),
                label = "",
            )

        fun acceptHostKey(
            tunnelId: String,
            key: com.xssh.core.ssh.InteractiveHostKeyVerifier.UnknownKey,
        ) = manager.acceptHostKey(tunnelId, key)

        fun rejectHostKey(
            tunnelId: String,
            key: com.xssh.core.ssh.InteractiveHostKeyVerifier.UnknownKey,
        ) = manager.rejectHostKey(tunnelId, key)

        fun clearVerificationEvent() = manager.clearVerificationEvent()

        fun forgetHostKey(
            tunnelId: String,
            hostPort: String,
        ) = manager.forgetHostKey(tunnelId, hostPort)
    }

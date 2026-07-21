/*
 * xSSH — Repository translating between Room [TunnelEntity] and the
 * domain [Tunnel] value type from :core-ssh. Kept intentionally thin: the
 * actual runtime state of a tunnel (running / stopped / bound port / error)
 * lives in [TunnelManager], not here.
 */
package com.xssh.feature.tunnels

import com.xssh.core.data.dao.TunnelDao
import com.xssh.core.data.entity.TunnelEntity
import com.xssh.core.ssh.Tunnel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TunnelRepository
    @Inject
    constructor(
        private val dao: TunnelDao,
    ) {
        fun observeAll(): Flow<List<TunnelRecord>> = dao.observeAll().map { list -> list.map { it.toRecord() } }

        fun observeForConnection(connectionId: String): Flow<List<TunnelRecord>> =
            dao.observeForConnection(connectionId).map { list -> list.map { it.toRecord() } }

        suspend fun byId(id: String): TunnelRecord? = dao.byId(id)?.toRecord()

        suspend fun upsert(record: TunnelRecord) = dao.upsert(record.toEntity())

        suspend fun delete(record: TunnelRecord) = dao.delete(record.toEntity())

        private fun TunnelEntity.toRecord() =
            TunnelRecord(
                tunnel =
                    Tunnel(
                        id = id,
                        connectionId = connectionId,
                        kind =
                            when (kind) {
                                0 -> Tunnel.Kind.LOCAL
                                1 -> Tunnel.Kind.REMOTE
                                else -> Tunnel.Kind.DYNAMIC
                            },
                        bindHost = bindHost,
                        bindPort = bindPort,
                        destHost = destHost,
                        destPort = destPort,
                        autoStart = autoStart,
                    ),
                label = label,
            )

        private fun TunnelRecord.toEntity() =
            TunnelEntity(
                id = tunnel.id,
                connectionId = tunnel.connectionId,
                kind =
                    when (tunnel.kind) {
                        Tunnel.Kind.LOCAL -> 0
                        Tunnel.Kind.REMOTE -> 1
                        Tunnel.Kind.DYNAMIC -> 2
                    },
                bindHost = tunnel.bindHost,
                bindPort = tunnel.bindPort,
                destHost = tunnel.destHost,
                destPort = tunnel.destPort,
                autoStart = tunnel.autoStart,
                label = label,
            )
    }

/**
 * A saved tunnel definition + display label. Runtime state (running, error
 * message, bound port) is looked up separately in [TunnelManager].
 */
data class TunnelRecord(
    val tunnel: Tunnel,
    val label: String = "",
)

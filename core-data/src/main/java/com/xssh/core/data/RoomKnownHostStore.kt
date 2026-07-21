package com.xssh.core.data

import com.xssh.core.data.dao.KnownHostDao
import com.xssh.core.data.entity.KnownHostEntity
import com.xssh.core.ssh.KnownHostRecord
import com.xssh.core.ssh.KnownHostStore
import javax.inject.Inject
import javax.inject.Singleton

/** Room-backed adapter for :core-ssh's storage-neutral host-key policy. */
@Singleton
class RoomKnownHostStore
    @Inject
    constructor(
        private val dao: KnownHostDao,
    ) : KnownHostStore {
        override suspend fun get(hostPort: String): KnownHostRecord? =
            dao.byHostPort(hostPort)?.let {
                KnownHostRecord(it.hostPort, it.keyType, it.fingerprintSha256, it.addedEpochMs)
            }

        override suspend fun put(record: KnownHostRecord) {
            dao.upsert(
                KnownHostEntity(
                    hostPort = record.hostPort,
                    keyType = record.keyType,
                    fingerprintSha256 = record.fingerprintSha256,
                    addedEpochMs = record.addedEpochMs,
                ),
            )
        }

        override suspend fun delete(hostPort: String) = dao.deleteByHostPort(hostPort)
    }

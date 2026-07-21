package com.xssh.core.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Trust-on-first-use record. `hostPort` follows OpenSSH syntax: "host" at
 * port 22, otherwise "[host]:port". A changed fingerprint must fail closed.
 */
@Entity(tableName = "known_hosts")
data class KnownHostEntity(
    @PrimaryKey val hostPort: String,
    val keyType: String,
    val fingerprintSha256: String,
    val addedEpochMs: Long = System.currentTimeMillis(),
    val note: String = "",
)

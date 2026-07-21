/*
 * xSSH — persisted connection profile.
 *
 * Sensitive values are opaque ByteArrays produced by SecretVault. Room never
 * receives a plaintext password, private key, or private-key passphrase.
 */
package com.xssh.core.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connections")
data class ConnectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    /** 0=PASSWORD, 1=PUBLIC_KEY, 2=AGENT, 3=INTERACTIVE. */
    val authKind: Int,
    val encryptedPassword: ByteArray? = null,
    val encryptedPrivateKey: ByteArray? = null,
    val encryptedKeyPassphrase: ByteArray? = null,
    val compression: Boolean = true,
    val keepAliveSeconds: Int = 30,
    val connectTimeoutMs: Int = 10_000,
    val ephemeral: Boolean = false,
    val agentForwarding: Boolean = false,
    val lastUsedEpochMs: Long? = null,
    val tags: List<String> = emptyList(),
)

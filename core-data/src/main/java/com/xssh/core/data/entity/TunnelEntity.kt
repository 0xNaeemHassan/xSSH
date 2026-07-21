package com.xssh.core.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** kind: 0=LOCAL (-L), 1=REMOTE (-R), 2=DYNAMIC (-D SOCKS5). */
@Entity(
    tableName = "tunnels",
    foreignKeys = [
        ForeignKey(
            entity = ConnectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["connectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("connectionId")],
)
data class TunnelEntity(
    @PrimaryKey val id: String,
    val connectionId: String,
    val kind: Int,
    /** Loopback by default: don't expose a tunnel to the LAN by accident. */
    val bindHost: String = "127.0.0.1",
    val bindPort: Int,
    val destHost: String? = null,
    val destPort: Int? = null,
    val autoStart: Boolean = false,
    val label: String = "",
)

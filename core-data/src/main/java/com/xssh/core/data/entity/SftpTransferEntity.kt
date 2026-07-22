package com.xssh.core.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sftp_transfer_queue",
    foreignKeys = [
        ForeignKey(
            entity = ConnectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["connectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["connectionId", "createdAtEpochMs"])],
)
data class SftpTransferEntity(
    @PrimaryKey val id: String,
    val connectionId: String,
    val label: String,
    val direction: String,
    val remotePath: String,
    val localUri: String,
    val totalBytes: Long,
    val status: String,
    val bytesTransferred: Long,
    val error: String?,
    val createdAtEpochMs: Long,
)

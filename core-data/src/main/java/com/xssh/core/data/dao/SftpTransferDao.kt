package com.xssh.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xssh.core.data.entity.SftpTransferEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SftpTransferDao {
    @Query(
        "SELECT * FROM sftp_transfer_queue " +
            "WHERE connectionId = :connectionId ORDER BY createdAtEpochMs DESC",
    )
    fun observeForConnection(connectionId: String): Flow<List<SftpTransferEntity>>

    @Query("SELECT * FROM sftp_transfer_queue WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): SftpTransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SftpTransferEntity)

    @Query(
        "UPDATE sftp_transfer_queue SET bytesTransferred = :bytesTransferred, " +
            "totalBytes = :totalBytes WHERE id = :id AND status = 'RUNNING'",
    )
    suspend fun updateProgress(
        id: String,
        bytesTransferred: Long,
        totalBytes: Long,
    )

    @Query("DELETE FROM sftp_transfer_queue WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        "DELETE FROM sftp_transfer_queue WHERE connectionId = :connectionId " +
            "AND status NOT IN ('QUEUED', 'RUNNING')",
    )
    suspend fun clearFinished(connectionId: String)

    @Query(
        "UPDATE sftp_transfer_queue SET status = 'FAILED', " +
            "error = 'Transfer interrupted. Reconnect and retry.' " +
            "WHERE connectionId = :connectionId AND status IN ('QUEUED', 'RUNNING')",
    )
    suspend fun markInterrupted(connectionId: String)
}

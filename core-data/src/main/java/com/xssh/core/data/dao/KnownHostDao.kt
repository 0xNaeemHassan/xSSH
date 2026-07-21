package com.xssh.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xssh.core.data.entity.KnownHostEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KnownHostDao {
    @Query("SELECT * FROM known_hosts ORDER BY hostPort COLLATE NOCASE")
    fun observeAll(): Flow<List<KnownHostEntity>>

    @Query("SELECT * FROM known_hosts WHERE hostPort = :hostPort LIMIT 1")
    suspend fun byHostPort(hostPort: String): KnownHostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: KnownHostEntity)

    @Query("DELETE FROM known_hosts WHERE hostPort = :hostPort")
    suspend fun deleteByHostPort(hostPort: String)
}

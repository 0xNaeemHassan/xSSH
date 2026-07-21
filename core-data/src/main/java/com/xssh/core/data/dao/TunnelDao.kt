package com.xssh.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xssh.core.data.entity.TunnelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TunnelDao {
    @Query("SELECT * FROM tunnels ORDER BY bindPort")
    fun observeAll(): Flow<List<TunnelEntity>>

    @Query("SELECT * FROM tunnels WHERE connectionId = :id ORDER BY bindPort")
    fun observeForConnection(id: String): Flow<List<TunnelEntity>>

    @Query("SELECT * FROM tunnels WHERE connectionId = :id ORDER BY bindPort")
    suspend fun listForConnection(id: String): List<TunnelEntity>

    @Query("SELECT * FROM tunnels WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): TunnelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TunnelEntity)

    @Delete suspend fun delete(entity: TunnelEntity)
}

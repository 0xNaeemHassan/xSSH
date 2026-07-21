package com.xssh.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.xssh.core.data.entity.ConnectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM connections ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM connections WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ConnectionEntity?

    @Query(
        "SELECT * FROM connections WHERE " +
            "instr(lower(name), lower(:q)) > 0 OR " +
            "instr(lower(host), lower(:q)) > 0 OR " +
            "instr(lower(username), lower(:q)) > 0 " +
            "ORDER BY name COLLATE NOCASE",
    )
    fun search(q: String): Flow<List<ConnectionEntity>>

    @Upsert
    suspend fun upsert(entity: ConnectionEntity)

    @Update suspend fun update(entity: ConnectionEntity)

    @Delete suspend fun delete(entity: ConnectionEntity)

    @Query("DELETE FROM connections WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE connections SET lastUsedEpochMs = :ts WHERE id = :id")
    suspend fun touch(
        id: String,
        ts: Long = System.currentTimeMillis(),
    )
}

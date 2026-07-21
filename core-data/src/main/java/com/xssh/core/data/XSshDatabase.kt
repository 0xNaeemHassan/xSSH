/*
 * xSSH Room database. Sensitive byte blobs are sealed by SecretVault before
 * reaching this layer; the SQLite file contains no directly-readable secret.
 */
package com.xssh.core.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.xssh.core.data.dao.ConnectionDao
import com.xssh.core.data.dao.KnownHostDao
import com.xssh.core.data.dao.SnippetDao
import com.xssh.core.data.dao.TunnelDao
import com.xssh.core.data.entity.ConnectionEntity
import com.xssh.core.data.entity.KnownHostEntity
import com.xssh.core.data.entity.SnippetEntity
import com.xssh.core.data.entity.TunnelEntity

@Database(
    entities = [ConnectionEntity::class, KnownHostEntity::class, TunnelEntity::class, SnippetEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class XSshDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao

    abstract fun knownHostDao(): KnownHostDao

    abstract fun tunnelDao(): TunnelDao

    abstract fun snippetDao(): SnippetDao
}

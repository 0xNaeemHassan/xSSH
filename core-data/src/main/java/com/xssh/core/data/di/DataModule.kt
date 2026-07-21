/*
 * xSSH — Hilt module for :core-data.
 *
 * Provides the Room database and DAOs, and binds the storage-neutral
 * [KnownHostStore] to its Room-backed adapter. All DAOs are singleton-scoped
 * so a single database instance is reused across the whole process.
 */
package com.xssh.core.data.di

import android.content.Context
import androidx.room.Room
import com.xssh.core.data.RoomKnownHostStore
import com.xssh.core.data.XSshDatabase
import com.xssh.core.data.dao.ConnectionDao
import com.xssh.core.data.dao.KnownHostDao
import com.xssh.core.data.dao.SnippetDao
import com.xssh.core.data.dao.TunnelDao
import com.xssh.core.ssh.KnownHostStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext ctx: Context,
    ): XSshDatabase =
        Room.databaseBuilder(ctx, XSshDatabase::class.java, "xssh.db")
            // Non-destructive migrations only. Downgrades wipe (dev), never
            // silently drop on forward migrations.
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides fun connectionDao(db: XSshDatabase): ConnectionDao = db.connectionDao()

    @Provides fun knownHostDao(db: XSshDatabase): KnownHostDao = db.knownHostDao()

    @Provides fun tunnelDao(db: XSshDatabase): TunnelDao = db.tunnelDao()

    @Provides fun snippetDao(db: XSshDatabase): SnippetDao = db.snippetDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindingsModule {
    @Binds
    @Singleton
    abstract fun bindKnownHostStore(impl: RoomKnownHostStore): KnownHostStore
}

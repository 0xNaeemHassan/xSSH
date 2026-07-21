package com.xssh.feature.tunnels

import com.xssh.feature.connections.ConnectionRuntimeCoordinator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TunnelBindingsModule {
    @Binds
    @Singleton
    abstract fun bindConnectionRuntimeCoordinator(manager: TunnelManager): ConnectionRuntimeCoordinator
}

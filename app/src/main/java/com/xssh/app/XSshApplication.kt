/*
 * xSSH — Application entry point.
 *
 * Copyright 2026 The xSSH Authors — Apache 2.0
 *
 * Wiring:
 *   • @HiltAndroidApp installs the Hilt SingletonComponent.
 *   • CryptoBootstrap.install() runs BEFORE anything that could pull in
 *     sshj / BouncyCastle — it swaps the platform's stripped-down BC
 *     provider for the full bcprov-jdk18on so X25519/Ed25519 exist.
 *   • No application logging backend is installed. Private session material
 *     must never be copied into a local or remote log sink.
 *   • Any tunnel row with `autoStart = true` is booted on process start
 *     from a background coroutine — no UI blocking; failures surface via
 *     TunnelManager.runtimes[id].error.
 *   • onTerminate stops every running tunnel and resets the foreground-
 *     activity counter. Note: onTerminate is only reliably called in the
 *     emulator; on real devices the kernel just kills the process, so we
 *     also lean on TunnelManager being a @Singleton with a supervisor scope
 *     that dies with the JVM.
 */
package com.xssh.app

import android.app.Application
import com.xssh.core.crypto.CryptoBootstrap
import com.xssh.core.data.dao.TunnelDao
import com.xssh.core.ssh.Tunnel
import com.xssh.feature.session.BackgroundActivityController
import com.xssh.feature.tunnels.TunnelManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class XSshApplication : Application() {
    @Inject lateinit var tunnelDao: TunnelDao

    @Inject lateinit var tunnels: TunnelManager

    @Inject lateinit var background: BackgroundActivityController

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // MUST run before anything touches sshj / BouncyCastle. Idempotent.
        CryptoBootstrap.install()

        // Explicit no-op: xSSH does NOT initialize any analytics, crash reporter,
        // ad SDK, or remote-config framework. Enforced by :verifyNoTelemetry.

        appScope.launch {
            val snap =
                runCatching { tunnelDao.observeAll().first() }
                    .getOrDefault(emptyList())
            snap.filter { it.autoStart }.forEach { e ->
                tunnels.start(
                    Tunnel(
                        id = e.id,
                        connectionId = e.connectionId,
                        kind =
                            when (e.kind) {
                                0 -> Tunnel.Kind.LOCAL
                                1 -> Tunnel.Kind.REMOTE
                                else -> Tunnel.Kind.DYNAMIC
                            },
                        bindHost = e.bindHost,
                        bindPort = e.bindPort,
                        destHost = e.destHost,
                        destPort = e.destPort,
                        autoStart = e.autoStart,
                    ),
                    allowHostKeyPrompt = false,
                )
            }
        }
    }

    override fun onTerminate() {
        // Emulator-only in practice; still worth doing for lifecycle hygiene.
        runCatching { tunnels.stopAll() }
        runCatching { background.reset() }
        super.onTerminate()
    }
}

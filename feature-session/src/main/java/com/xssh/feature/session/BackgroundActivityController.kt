/*
 * xSSH — BackgroundActivityController.
 *
 * Process-scoped singleton that owns the running-count of "things that must
 * survive the user backgrounding the app":
 *
 *     bumpSessions(+1)   — a shell has just connected
 *     bumpSessions(-1)   — a shell has just disconnected
 *     bumpTunnels(+1)    — a port forward has just bound
 *     bumpTunnels(-1)    — a port forward has just stopped
 *
 * Whenever the total is > 0 we (re-)start [SessionForegroundService] with the
 * current counts so the ongoing notification stays accurate. When the total
 * returns to 0 we stopService() and the foreground promotion is released.
 *
 * The controller does NOT observe TunnelManager.runtimes / SessionViewModel
 * state directly — that would create a dependency cycle between the feature
 * modules and :app. Callers bump explicitly at the exact lifecycle points
 * where they already know the state has changed. Everything else stays
 * decoupled.
 *
 * Testability:
 *   Android's start/stop-service surface is wrapped by the tiny
 *   [ServiceLauncher] seam. Production uses [AndroidServiceLauncher] (main-
 *   thread Handler + real Context). Unit tests inject a synchronous fake and
 *   assert the sequence of promote / demote calls. Neither side sees the
 *   counts leak.
 *
 * Threading:
 *   • Counts are AtomicIntegers, so concurrent bumps compose atomically.
 *   • [AndroidServiceLauncher] dispatches to the main thread via a Handler
 *     because Android requires startForegroundService / startService /
 *     stopService to run on the main thread on several OEM builds.
 */
package com.xssh.feature.session

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Boundary between the counter logic (unit-testable) and Android's
 * start/stop-service surface (not unit-testable without Robolectric).
 */
interface ServiceLauncher {
    /** Promote the service to foreground with the given session/tunnel counts. */
    fun promote(
        sessions: Int,
        tunnels: Int,
    )

    /** Release the foreground promotion and stop the service. */
    fun demote()
}

@Singleton
class BackgroundActivityController(
    private val launcher: ServiceLauncher,
) {
    /**
     * Hilt-friendly ctor that installs [AndroidServiceLauncher] from an
     * @ApplicationContext. Hilt picks the @Inject-annotated ctor; the
     * primary ctor above (taking a raw ServiceLauncher) is what tests use.
     */
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(AndroidServiceLauncher(context))

    private val sessions = AtomicInteger(0)
    private val tunnels = AtomicInteger(0)
    private val refreshLock = Any()

    /** Current counts — useful for tests and logging. */
    val sessionCount: Int get() = sessions.get()
    val tunnelCount: Int get() = tunnels.get()

    fun bumpSessions(delta: Int) {
        synchronized(refreshLock) {
            adjust(sessions, delta)
            refreshLocked()
        }
    }

    fun bumpTunnels(delta: Int) {
        synchronized(refreshLock) {
            adjust(tunnels, delta)
            refreshLocked()
        }
    }

    /** Force the notification content to re-render from current counts. */
    fun refresh() {
        synchronized(refreshLock) { refreshLocked() }
    }

    private fun refreshLocked() {
        val s = sessions.get()
        val t = tunnels.get()
        if (s + t > 0) launcher.promote(s, t) else launcher.demote()
    }

    /** Reset counts and stop the service — call from Application.onTerminate. */
    fun reset() {
        synchronized(refreshLock) {
            sessions.set(0)
            tunnels.set(0)
            launcher.demote()
        }
    }

    private fun adjust(
        counter: AtomicInteger,
        delta: Int,
    ) {
        // updateAndGet clamps in a single CAS — no chance a concurrent bump
        // observes a transient negative value between add and coerceAtLeast.
        counter.updateAndGet { (it + delta).coerceAtLeast(0) }
    }
}

/**
 * Production [ServiceLauncher] — routes intents through the main-thread
 * Handler because Android requires start/stop-service on the main thread on
 * several OEM builds.
 */
class AndroidServiceLauncher(private val context: Context) : ServiceLauncher {
    private val main = Handler(Looper.getMainLooper())

    override fun promote(
        sessions: Int,
        tunnels: Int,
    ) {
        val intent =
            Intent(context, SessionForegroundService::class.java).apply {
                putExtra(SessionForegroundService.EXTRA_SESSIONS, sessions)
                putExtra(SessionForegroundService.EXTRA_TUNNELS, tunnels)
            }
        main.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun demote() {
        main.post {
            context.stopService(Intent(context, SessionForegroundService::class.java))
        }
    }
}

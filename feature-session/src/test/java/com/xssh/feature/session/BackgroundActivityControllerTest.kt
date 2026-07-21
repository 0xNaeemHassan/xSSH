/*
 * xSSH — BackgroundActivityController unit tests.
 *
 * Scope: verify counter arithmetic (including clamping negative bumps),
 * the promote/demote decision, and that reset() drains everything.
 *
 * The Android start/stop-service surface is behind a [ServiceLauncher] seam,
 * so this test needs no Robolectric, no Handler shim, and no real Context.
 * A tiny recording fake stands in for the launcher and every call to it is
 * asserted in order.
 */
package com.xssh.feature.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackgroundActivityControllerTest {
    @Test fun `starts empty and does not promote`() {
        val launcher = RecordingLauncher()
        val c = BackgroundActivityController(launcher)

        assertThat(c.sessionCount).isEqualTo(0)
        assertThat(c.tunnelCount).isEqualTo(0)
        assertThat(launcher.calls).isEmpty()
    }

    @Test fun `first session bump promotes with (1,0)`() {
        val launcher = RecordingLauncher()
        val c = BackgroundActivityController(launcher)

        c.bumpSessions(+1)

        assertThat(c.sessionCount).isEqualTo(1)
        assertThat(launcher.calls).containsExactly("promote(1,0)").inOrder()
    }

    @Test fun `symmetric bump-down returns counter to 0 and demotes`() {
        val launcher = RecordingLauncher()
        val c = BackgroundActivityController(launcher)

        c.bumpSessions(+1)
        c.bumpSessions(-1)

        assertThat(c.sessionCount).isEqualTo(0)
        assertThat(launcher.calls).containsExactly("promote(1,0)", "demote").inOrder()
    }

    @Test fun `negative bumps clamp at zero, never go below`() {
        val launcher = RecordingLauncher()
        val c = BackgroundActivityController(launcher)

        // Two extra bump-downs would otherwise leave the counter at -2 and
        // a subsequent bump-up would falsely start at -1. The clamp prevents
        // the notification from ever lying about live state.
        c.bumpTunnels(-1)
        c.bumpTunnels(-1)
        c.bumpTunnels(+1)

        assertThat(c.tunnelCount).isEqualTo(1)
        // Every bump triggers a refresh; the first two are demote (0+0=0),
        // the third is promote(0,1).
        assertThat(launcher.calls).containsExactly("demote", "demote", "promote(0,1)").inOrder()
    }

    @Test fun `mixed session and tunnel counts appear in promote`() {
        val launcher = RecordingLauncher()
        val c = BackgroundActivityController(launcher)

        c.bumpSessions(+2)
        c.bumpTunnels(+3)

        assertThat(c.sessionCount).isEqualTo(2)
        assertThat(c.tunnelCount).isEqualTo(3)
        assertThat(launcher.calls.last()).isEqualTo("promote(2,3)")
    }

    @Test fun `reset zeros both counters and demotes`() {
        val launcher = RecordingLauncher()
        val c = BackgroundActivityController(launcher)
        c.bumpSessions(+2)
        c.bumpTunnels(+5)

        c.reset()

        assertThat(c.sessionCount).isEqualTo(0)
        assertThat(c.tunnelCount).isEqualTo(0)
        assertThat(launcher.calls.last()).isEqualTo("demote")
    }

    @Test fun `refresh replays current counts without changing them`() {
        val launcher = RecordingLauncher()
        val c = BackgroundActivityController(launcher)
        c.bumpSessions(+1) // → promote(1,0)
        launcher.calls.clear()

        c.refresh()

        assertThat(c.sessionCount).isEqualTo(1)
        assertThat(launcher.calls).containsExactly("promote(1,0)")
    }

    @Test fun `rapid bump sequence composes correctly`() {
        // AtomicInteger.updateAndGet composes atomically; a stress-style
        // exercise proves the arithmetic never desynchronises from the
        // running snapshot the launcher sees.
        val launcher = RecordingLauncher()
        val c = BackgroundActivityController(launcher)

        repeat(100) { c.bumpSessions(+1) }
        repeat(100) { c.bumpSessions(-1) }

        assertThat(c.sessionCount).isEqualTo(0)
        assertThat(launcher.calls.last()).isEqualTo("demote")
    }

    /**
     * Records every promote/demote call as a plain string for order-sensitive
     * assertions with Truth.containsExactly(...).inOrder(). No mockk — the
     * ServiceLauncher interface is tiny and a fake is more readable.
     */
    private class RecordingLauncher : ServiceLauncher {
        val calls = mutableListOf<String>()

        override fun promote(
            sessions: Int,
            tunnels: Int,
        ) {
            calls += "promote($sessions,$tunnels)"
        }

        override fun demote() {
            calls += "demote"
        }
    }
}

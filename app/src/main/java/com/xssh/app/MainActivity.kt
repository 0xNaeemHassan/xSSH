/*
 * xSSH — Main Activity. Single-activity architecture; everything below is Compose.
 *
 * Two side jobs run in onCreate before setContent:
 *
 *   1. FLAG_SECURE — hides the terminal from screenshots and task previews.
 *      Every screen in this app can render private material, so it's easier
 *      to blanket-flag the window than to opt individual screens in.
 *
 *   2. POST_NOTIFICATIONS runtime request (API 33+). Without it, the
 *      foreground service still runs but Android silently suppresses its
 *      ongoing notification — the user would have no idea a background
 *      tunnel or session is live. We ask once, on first launch, and never
 *      pester after the user answers.
 *
 * We use ComponentActivity's registerForActivityResult to keep the request
 * launcher lifecycle-safe; the actual permission grant is fire-and-forget
 * (Android will just not show the notification if declined).
 */
package com.xssh.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.rememberNavController
import com.xssh.app.nav.XSshNavHost
import com.xssh.design.theme.XSshTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* fire-and-forget */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle =
                SystemBarStyle.auto(
                    Color.Transparent.hashCode(),
                    Color.Transparent.hashCode(),
                ),
        )
        super.onCreate(savedInstanceState)

        // Prevent screenshots / recent-task previews from leaking a live session.
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
        )

        maybeRequestNotifications()

        setContent {
            XSshTheme(dynamicColor = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Root()
                }
            }
        }
    }

    /**
     * Android 13+ (API 33) added POST_NOTIFICATIONS as a runtime permission.
     * Without it, our foreground-service notification is silently dropped by
     * the system and the user has no way of knowing background work is
     * happening. Ask once on first launch.
     */
    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        val permissionPrefs = getSharedPreferences("xssh_permission_prompts", MODE_PRIVATE)
        val alreadyAsked = permissionPrefs.getBoolean("notifications_asked", false)
        if (!granted && !alreadyAsked) {
            permissionPrefs.edit { putBoolean("notifications_asked", true) }
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @Composable
    private fun Root() {
        val navController = rememberNavController()
        XSshNavHost(navController = navController)
    }
}

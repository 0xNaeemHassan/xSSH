/*
 * xSSH — Biometric gate.
 *
 * Coroutine-friendly wrapper around BiometricPrompt. Allowed authenticators
 * are BIOMETRIC_STRONG OR DEVICE_CREDENTIAL, so fingerprint/face unlock users
 * get the fast path and everyone else falls back to their PIN/pattern.
 */
package com.xssh.core.crypto

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class BiometricGate(private val activity: FragmentActivity) {
    private val allowed =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun canAuthenticate(): Boolean =
        BiometricManager.from(activity).canAuthenticate(allowed) ==
            BiometricManager.BIOMETRIC_SUCCESS

    suspend fun authenticate(
        title: String,
        subtitle: String,
    ): Boolean =
        suspendCancellableCoroutine { cont ->
            val prompt =
                BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence,
                        ) {
                            if (cont.isActive) cont.resume(false)
                        }

                        override fun onAuthenticationFailed() { /* retry */ }
                    },
                )
            val info =
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title).setSubtitle(subtitle)
                    .setAllowedAuthenticators(allowed).build()
            prompt.authenticate(info)
            cont.invokeOnCancellation { runCatching { prompt.cancelAuthentication() } }
        }
}

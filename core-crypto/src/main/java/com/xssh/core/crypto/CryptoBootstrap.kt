/*
 * xSSH — CryptoBootstrap.
 *
 * The single most important initialization step.
 *
 * BACKGROUND
 * Since Android P (API 28), AOSP ships a stripped-down BouncyCastle provider
 * under the name "BC" that omits many algorithms — most importantly X25519
 * and Ed25519. sshj needs those algorithms for modern KEX
 * (curve25519-sha256) and host-key verification (ssh-ed25519).
 *
 * SYMPTOM (widely reported against sshj 0.3x on Android 10+, targetSdk 34):
 *     net.schmizz.sshj.common.SSHException:
 *         no such algorithm: X25519 for provider BC
 *
 * FIX
 * Bundle bcprov-jdk18on ourselves and INSERT it at position 1 (before the
 * platform BC). The provider name from bcprov-jdk18on is also "BC", so we
 * first remove the platform entry to avoid a collision; then
 * insertProviderAt(fullBC, 1) so our provider wins for X25519/Ed25519 while
 * platform providers still supply AES-GCM, SHA-256, and AndroidKeyStore.
 *
 * Idempotent, thread-safe, cheap. Call from Application.onCreate.
 */
package com.xssh.core.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

object CryptoBootstrap {
    @Volatile private var installed = false

    @Synchronized
    fun install() {
        if (installed) return
        Security.removeProvider("BC")
        val position = Security.insertProviderAt(BouncyCastleProvider(), 1)
        check(position > 0) {
            "Failed to install BouncyCastle at position 1 (returned $position)"
        }
        installed = true
    }
}

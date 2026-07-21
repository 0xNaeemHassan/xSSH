/*
 * xSSH — Secret vault backed by Android Keystore (AES-256-GCM).
 *
 *   seal(plaintext)  →  nonce (12B) || ciphertext (variable) || tag (16B)
 *   open(payload)    →  plaintext, or AEADBadTagException on tamper.
 *
 * The AES key lives ONLY inside the Keystore / StrongBox. Application code
 * never touches raw key material. Encrypted blobs are what :core-data stores
 * for passwords, private keys, and key passphrases.
 *
 * StrongBox is preferred when the device has a hardware secure element
 * (Titan M, Pixel 3+, most modern Samsung/OnePlus). Falls back to TEE if
 * StrongBox is unavailable at key generation time.
 */
package com.xssh.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecretVault
    @Inject
    constructor(
        @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    ) {
        private val keystore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        fun seal(
            plaintext: ByteArray,
            alias: String = KEY_ALIAS,
        ): ByteArray {
            val key = getOrCreateKey(alias)
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
            val nonce = cipher.iv
            check(nonce.size == GCM_NONCE_LEN) { "Keystore returned an unexpected GCM nonce length" }
            val ct = cipher.doFinal(plaintext)
            val out = ByteArray(nonce.size + ct.size)
            System.arraycopy(nonce, 0, out, 0, nonce.size)
            System.arraycopy(ct, 0, out, nonce.size, ct.size)
            return out
        }

        fun open(
            payload: ByteArray,
            alias: String = KEY_ALIAS,
        ): ByteArray {
            val key = getKey(alias) ?: error("No key entry '$alias' in Keystore")
            require(payload.size >= GCM_NONCE_LEN + GCM_TAG_LEN_BITS / 8) {
                "Payload too short (${payload.size} bytes)"
            }
            val nonce = payload.copyOfRange(0, GCM_NONCE_LEN)
            val ct = payload.copyOfRange(GCM_NONCE_LEN, payload.size)
            val cipher =
                Cipher.getInstance(TRANSFORM).apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN_BITS, nonce))
                }
            return cipher.doFinal(ct)
        }

        @Synchronized
        fun delete(alias: String) {
            if (keystore.containsAlias(alias)) keystore.deleteEntry(alias)
        }

        fun hasStrongBox(): Boolean = context.packageManager.hasSystemFeature("android.hardware.strongbox_keystore")

        @Synchronized
        private fun getKey(alias: String): SecretKey? = keystore.getKey(alias, null) as? SecretKey

        @Synchronized
        private fun getOrCreateKey(alias: String): SecretKey {
            getKey(alias)?.let { return it }

            fun spec(strongBox: Boolean) =
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .apply { if (strongBox) setIsStrongBoxBacked(true) }
                    .build()

            fun generate(strongBox: Boolean): SecretKey {
                val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                kg.init(spec(strongBox))
                return kg.generateKey()
            }

            if (!hasStrongBox()) return generate(false)
            return try {
                generate(true)
            } catch (_: StrongBoxUnavailableException) {
                // Device advertises StrongBox but rejected the key at generation.
                generate(false)
            } catch (_: ProviderException) {
                // Several OEM Keystore providers wrap the same condition instead
                // of exposing StrongBoxUnavailableException directly.
                generate(false)
            }
        }

        companion object {
            private const val ANDROID_KEYSTORE = "AndroidKeyStore"
            internal const val KEY_ALIAS = "xssh.master.v1"
            private const val TRANSFORM = "AES/GCM/NoPadding"
            private const val GCM_NONCE_LEN = 12
            private const val GCM_TAG_LEN_BITS = 128
        }
    }

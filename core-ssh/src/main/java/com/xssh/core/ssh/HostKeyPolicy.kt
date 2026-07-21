/*
 * xSSH — strict host-key policy.
 *
 * First contact is Trust On First Use (TOFU), but never *silent* TOFU: the
 * verifier emits the SHA-256 fingerprint and blocks the SSH handshake until
 * the user explicitly accepts or rejects it. A later mismatch always fails
 * closed — no "continue anyway" path — because it can indicate MITM.
 *
 * Timeouts:
 *   sshj calls verify() on its blocking transport thread. If the user never
 *   answers (activity torn down, phone locked, coroutine cancelled), we
 *   bound the wait via [decisionTimeoutSeconds] and reject the key. That
 *   lets the connect() attempt fail cleanly instead of leaving the transport
 *   thread stuck forever.
 *
 *   A timeout is surfaced as [VerificationEvent.TimedOut] so the UI can
 *   distinguish "user said no" from "we never got an answer".
 */
package com.xssh.core.ssh

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/** Storage boundary so :core-ssh is independent of Room. */
interface KnownHostStore {
    suspend fun get(hostPort: String): KnownHostRecord?

    suspend fun put(record: KnownHostRecord)

    suspend fun delete(hostPort: String)
}

/** Reads existing trust records but never persists a newly accepted key. */
class EphemeralKnownHostStore(private val delegate: KnownHostStore) : KnownHostStore {
    override suspend fun get(hostPort: String): KnownHostRecord? = delegate.get(hostPort)

    override suspend fun put(record: KnownHostRecord) = Unit

    override suspend fun delete(hostPort: String) = Unit
}

data class KnownHostRecord(
    val hostPort: String,
    val keyType: String,
    val fingerprintSha256: String,
    val addedEpochMs: Long = System.currentTimeMillis(),
)

class InteractiveHostKeyVerifier(
    private val store: KnownHostStore,
    private val decisionTimeoutSeconds: Long = 90,
) : HostKeyVerifier {
    data class UnknownKey(
        val hostname: String,
        val port: Int,
        val fingerprintSha256: String,
        val keyType: String,
    ) {
        val hostPort: String get() = canonicalHostPort(hostname, port)
    }

    sealed interface VerificationEvent {
        data class Unknown(val key: UnknownKey) : VerificationEvent

        data class Changed(
            val hostPort: String,
            val expected: String,
            val actual: String,
        ) : VerificationEvent

        /** Emitted when the user did not answer within decisionTimeoutSeconds. */
        data object TimedOut : VerificationEvent
    }

    private val _pendingPrompt = MutableStateFlow<UnknownKey?>(null)
    val pendingPrompt: StateFlow<UnknownKey?> = _pendingPrompt

    private val _events = MutableStateFlow<VerificationEvent?>(null)
    val events: StateFlow<VerificationEvent?> = _events

    /** A handshake gets exactly one decision. Buffered to avoid UI races. */
    private val decisions = Channel<Boolean>(capacity = Channel.CONFLATED)

    override fun verify(
        hostname: String,
        port: Int,
        key: PublicKey,
    ): Boolean =
        runBlocking {
            val actual = sha256Fingerprint(key)
            val hostPort = canonicalHostPort(hostname, port)
            val existing = store.get(hostPort)

            when {
                existing == null -> {
                    // A cancelled prior attempt must never decide a later handshake.
                    while (decisions.tryReceive().isSuccess) Unit
                    val unknown = UnknownKey(hostname, port, actual, key.algorithm)
                    _pendingPrompt.value = unknown
                    _events.value = VerificationEvent.Unknown(unknown)

                    // Bounded wait — a torn-down UI must not keep the transport
                    // thread parked forever.
                    val decision: Boolean? =
                        withTimeoutOrNull(decisionTimeoutSeconds * 1000L) {
                            decisions.receiveCatching().getOrNull()
                        }
                    _pendingPrompt.value = null
                    if (_events.value is VerificationEvent.Unknown) _events.value = null

                    when (decision) {
                        true -> {
                            store.put(KnownHostRecord(hostPort, key.algorithm, actual))
                            true
                        }
                        false -> false
                        null -> {
                            // Distinguish "timed out" from "explicit reject" so the UI
                            // can show something more useful than a generic failure.
                            _events.value = VerificationEvent.TimedOut
                            false
                        }
                    }
                }
                existing.fingerprintSha256 == actual -> true
                // Builds before 0.1.0 accidentally persisted sshj's legacy MD5
                // display fingerprint under this field. If it matches the exact
                // same key, upgrade the record in place without weakening trust.
                !existing.fingerprintSha256.startsWith("SHA256:") &&
                    existing.fingerprintSha256 == SecurityUtils.getFingerprint(key) -> {
                    store.put(existing.copy(keyType = key.algorithm, fingerprintSha256 = actual))
                    true
                }
                else -> {
                    _events.value = VerificationEvent.Changed(hostPort, existing.fingerprintSha256, actual)
                    false
                }
            }
        }

    /** UI calls exactly one of these from the fingerprint confirmation dialog. */
    fun acceptPending(expected: UnknownKey) {
        if (_pendingPrompt.value == expected) decisions.trySend(true)
    }

    fun rejectPending(expected: UnknownKey) {
        if (_pendingPrompt.value == expected) decisions.trySend(false)
    }

    /** Called when the session closes while the user has not answered a prompt. */
    fun cancel() {
        if (_pendingPrompt.value != null) decisions.trySend(false)
        _pendingPrompt.value = null
    }

    /** Clear the last-seen event; call after showing a Changed/TimedOut dialog. */
    fun clearEvent() {
        _events.value = null
    }

    override fun findExistingAlgorithms(
        hostname: String,
        port: Int,
    ): List<String> = emptyList()

    companion object {
        fun canonicalHostPort(
            host: String,
            port: Int,
        ): String = if (port == 22) host else "[$host]:$port"

        internal fun sha256Fingerprint(key: PublicKey): String {
            val wire = Buffer.PlainBuffer().putPublicKey(key).compactData
            val digest = MessageDigest.getInstance("SHA-256").digest(wire)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }
}

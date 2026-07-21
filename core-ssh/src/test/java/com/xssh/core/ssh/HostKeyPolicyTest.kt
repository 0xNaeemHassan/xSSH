package com.xssh.core.ssh

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.common.SecurityUtils
import org.junit.Test
import java.security.KeyPairGenerator

class HostKeyPolicyTest {
    @Test fun `unknown host requires explicit acceptance then persists`() =
        runBlocking {
            val store = FakeKnownHostStore()
            val verifier = InteractiveHostKeyVerifier(store)
            val pair = rsaKeyPair()
            val job = async(Dispatchers.Default) { verifier.verify("example.test", 22, pair.public) }
            val prompt = withTimeout(5_000) { verifier.pendingPrompt.filterNotNull().first() }
            assertThat(prompt.fingerprintSha256).isNotEmpty()
            verifier.acceptPending(prompt)
            assertThat(job.await()).isTrue()
            assertThat(store.get("example.test")?.fingerprintSha256)
                .isEqualTo(InteractiveHostKeyVerifier.sha256Fingerprint(pair.public))
            assertThat(prompt.fingerprintSha256).startsWith("SHA256:")
        }

    @Test fun `changed host key fails closed`() =
        runBlocking {
            val old = rsaKeyPair()
            val new = rsaKeyPair()
            val store =
                FakeKnownHostStore().apply {
                    put(
                        KnownHostRecord(
                            "example.test",
                            old.public.algorithm,
                            SecurityUtils.getFingerprint(old.public),
                        ),
                    )
                }
            val verifier = InteractiveHostKeyVerifier(store)
            assertThat(verifier.verify("example.test", 22, new.public)).isFalse()
            assertThat(
                verifier.events.value,
            ).isInstanceOf(InteractiveHostKeyVerifier.VerificationEvent.Changed::class.java)
        }

    @Test fun `matching legacy MD5 record is upgraded to SHA256`() =
        runBlocking {
            val pair = rsaKeyPair()
            val store =
                FakeKnownHostStore().apply {
                    put(
                        KnownHostRecord(
                            "legacy.test",
                            pair.public.algorithm,
                            SecurityUtils.getFingerprint(pair.public),
                        ),
                    )
                }
            val verifier = InteractiveHostKeyVerifier(store)

            assertThat(verifier.verify("legacy.test", 22, pair.public)).isTrue()
            assertThat(store.get("legacy.test")?.fingerprintSha256).startsWith("SHA256:")
        }

    @Test fun `cancel without a pending prompt cannot reject a future handshake`() =
        runBlocking {
            val store = FakeKnownHostStore()
            val verifier = InteractiveHostKeyVerifier(store)
            verifier.cancel()
            val pair = rsaKeyPair()
            val job = async(Dispatchers.Default) { verifier.verify("fresh.test", 22, pair.public) }
            val prompt = withTimeout(5_000) { verifier.pendingPrompt.filterNotNull().first() }
            assertThat(prompt.hostPort).isEqualTo("fresh.test")
            verifier.acceptPending(prompt)
            assertThat(job.await()).isTrue()
        }

    @Test fun `ephemeral host store reads trust but does not persist new records`() =
        runBlocking {
            val backing = FakeKnownHostStore()
            val ephemeral = EphemeralKnownHostStore(backing)
            ephemeral.put(KnownHostRecord("temporary.test", "RSA", "SHA256:test"))
            assertThat(backing.get("temporary.test")).isNull()

            backing.put(KnownHostRecord("known.test", "RSA", "SHA256:known"))
            assertThat(ephemeral.get("known.test")?.fingerprintSha256).isEqualTo("SHA256:known")
        }

    @Test fun `a stale dialog callback cannot approve a later host`() =
        runBlocking {
            val verifier = InteractiveHostKeyVerifier(FakeKnownHostStore())
            val firstPair = rsaKeyPair()
            val firstJob = async(Dispatchers.Default) { verifier.verify("first.test", 22, firstPair.public) }
            val firstPrompt = withTimeout(5_000) { verifier.pendingPrompt.filterNotNull().first() }
            verifier.acceptPending(firstPrompt)
            assertThat(firstJob.await()).isTrue()

            val secondPair = rsaKeyPair()
            val secondJob = async(Dispatchers.Default) { verifier.verify("second.test", 22, secondPair.public) }
            val secondPrompt = withTimeout(5_000) { verifier.pendingPrompt.filterNotNull().first() }
            verifier.acceptPending(firstPrompt)
            delay(100)
            assertThat(secondJob.isCompleted).isFalse()
            verifier.rejectPending(secondPrompt)
            assertThat(secondJob.await()).isFalse()
        }

    private class FakeKnownHostStore : KnownHostStore {
        private val values = mutableMapOf<String, KnownHostRecord>()

        override suspend fun get(hostPort: String) = values[hostPort]

        override suspend fun put(record: KnownHostRecord) {
            values[record.hostPort] = record
        }

        override suspend fun delete(hostPort: String) {
            values.remove(hostPort)
        }
    }

    private fun rsaKeyPair() =
        KeyPairGenerator
            .getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
}

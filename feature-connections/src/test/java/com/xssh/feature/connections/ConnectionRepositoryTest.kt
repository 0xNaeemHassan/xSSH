/*
 * xSSH — ConnectionRepository unit tests.
 *
 * Scope:
 *   • Domain ↔ entity mapping round-trips without field drift.
 *   • Secrets: on upsert with a plaintext, the vault.seal() output is what
 *     lands in the entity — not the plaintext.
 *   • Secrets: when the caller does NOT supply a plaintext on upsert, the
 *     already-stored ciphertext is preserved (partial updates never nuke
 *     credentials the user isn't editing).
 *   • Retrieval: passwordCredentialFor decrypts through vault.open and hands
 *     back a matching CharArray.
 *
 * SecretVault is a final Kotlin class that talks to the Android Keystore, so
 * we replace it with a plain mockk<SecretVault>() and stub seal/open with a
 * deterministic identity-plus-prefix transform. That gives us round-trip
 * behaviour without touching any Android APIs.
 */
package com.xssh.feature.connections

import com.google.common.truth.Truth.assertThat
import com.xssh.core.crypto.SecretVault
import com.xssh.core.data.dao.ConnectionDao
import com.xssh.core.data.entity.ConnectionEntity
import com.xssh.core.ssh.AuthMethod
import com.xssh.core.ssh.SshConnectionProfile
import com.xssh.core.ssh.TransportOptions
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ConnectionRepositoryTest {
    // -- Mapper round-trip ----------------------------------------------------

    @Test fun `password profile round-trips through the mapper`() =
        runTest {
            val repo = ConnectionRepository(FakeConnectionDao(), sealingVault())

            val profile =
                SshConnectionProfile(
                    id = "c1",
                    name = "prod",
                    host = "10.0.0.1",
                    port = 2222,
                    username = "root",
                    auth = AuthMethod.Password,
                    options = TransportOptions(compression = false, keepAliveSeconds = 60),
                    ephemeral = false,
                    agentForwarding = true,
                    tags = listOf("prod", "gateway"),
                )
            repo.upsert(profile, plaintextPassword = "hunter2".toCharArray())

            val fetched = repo.get("c1")
            assertThat(fetched).isNotNull()
            assertThat(fetched!!.name).isEqualTo("prod")
            assertThat(fetched.host).isEqualTo("10.0.0.1")
            assertThat(fetched.port).isEqualTo(2222)
            assertThat(fetched.username).isEqualTo("root")
            assertThat(fetched.auth).isEqualTo(AuthMethod.Password)
            assertThat(fetched.options.compression).isFalse()
            assertThat(fetched.options.keepAliveSeconds).isEqualTo(60)
            assertThat(fetched.agentForwarding).isTrue()
            assertThat(fetched.tags).containsExactly("prod", "gateway").inOrder()
        }

    @Test fun `AuthMethod survives the int-encoded round-trip`() =
        runTest {
            val repo = ConnectionRepository(FakeConnectionDao(), sealingVault())

            listOf(
                AuthMethod.Password,
                AuthMethod.PublicKey,
                AuthMethod.Agent,
                AuthMethod.Interactive,
            ).forEachIndexed { i, kind ->
                val id = "auth-$i"
                repo.upsert(profile(id, auth = kind))
                assertThat(repo.get(id)?.auth).isEqualTo(kind)
            }
        }

    // -- Secret handling ------------------------------------------------------

    @Test fun `saved password is sealed before hitting Room`() =
        runTest {
            val dao = FakeConnectionDao()
            val repo = ConnectionRepository(dao, sealingVault())

            repo.upsert(profile("c1"), plaintextPassword = "s3cret!".toCharArray())

            val stored = dao.byId("c1")!!
            assertThat(stored.encryptedPassword).isNotNull()
            // The sealingVault prefixes with "SEALED:" — asserting the prefix
            // proves seal() actually ran (rather than plaintext bytes being
            // written verbatim into the row).
            assertThat(String(stored.encryptedPassword!!, Charsets.UTF_8))
                .isEqualTo("SEALED:s3cret!")
        }

    @Test fun `upsert without new secret preserves existing sealed password`() =
        runTest {
            val dao = FakeConnectionDao()
            val repo = ConnectionRepository(dao, sealingVault())

            repo.upsert(profile("c1"), plaintextPassword = "keep-me".toCharArray())
            val originalCiphertext = dao.byId("c1")!!.encryptedPassword

            // Update the profile again but pass no plaintextPassword — the saved
            // secret must survive untouched. This is critical: an accidental
            // credential wipe here would silently break every saved connection
            // the next time the user renamed one.
            repo.upsert(profile("c1").copy(name = "renamed"))

            val after = dao.byId("c1")!!
            assertThat(after.name).isEqualTo("renamed")
            assertThat(after.encryptedPassword).isEqualTo(originalCiphertext)
        }

    @Test fun `passwordCredentialFor opens the vault and returns the plaintext`() =
        runTest {
            val repo = ConnectionRepository(FakeConnectionDao(), sealingVault())
            repo.upsert(profile("c1"), plaintextPassword = "opensesame".toCharArray())

            val cred = repo.passwordCredentialFor("c1")
            assertThat(cred).isNotNull()
            assertThat(String(cred!!.password)).isEqualTo("opensesame")
        }

    @Test fun `passwordCredentialFor is null when no password was ever saved`() =
        runTest {
            val repo = ConnectionRepository(FakeConnectionDao(), sealingVault())
            repo.upsert(profile("c1"))

            assertThat(repo.passwordCredentialFor("c1")).isNull()
        }

    @Test fun `switching from password to public key clears the old password ciphertext`() =
        runTest {
            val dao = FakeConnectionDao()
            val repo = ConnectionRepository(dao, sealingVault())

            repo.upsert(profile("c1", auth = AuthMethod.Password), plaintextPassword = "pw".toCharArray())
            repo.upsert(
                profile("c1", auth = AuthMethod.PublicKey),
                plaintextPrivateKey = "PRIVATE-KEY".toByteArray(),
            )

            val stored = dao.byId("c1")!!
            assertThat(stored.encryptedPassword).isNull()
            assertThat(stored.encryptedPrivateKey).isNotNull()
        }

    @Test fun `agent auth persists the encrypted private key and clears stale password ciphertext`() =
        runTest {
            val dao = FakeConnectionDao()
            val repo = ConnectionRepository(dao, sealingVault())

            repo.upsert(profile("c1", auth = AuthMethod.Password), plaintextPassword = "pw".toCharArray())
            repo.upsert(
                profile("c1", auth = AuthMethod.Agent),
                plaintextPrivateKey = "PRIVATE-KEY".toByteArray(),
                plaintextKeyPassphrase = "phrase".toCharArray(),
            )

            val stored = dao.byId("c1")!!
            assertThat(stored.authKind).isEqualTo(2)
            assertThat(stored.encryptedPassword).isNull()
            assertThat(stored.encryptedPrivateKey)
                .isEqualTo("SEALED:PRIVATE-KEY".toByteArray(Charsets.UTF_8))
            assertThat(stored.encryptedKeyPassphrase)
                .isEqualTo("SEALED:phrase".toByteArray(Charsets.UTF_8))
        }

    @Test fun `replacing a stored private key clears the old passphrase when none is supplied`() =
        runTest {
            val dao = FakeConnectionDao()
            val repo = ConnectionRepository(dao, sealingVault())

            repo.upsert(
                profile("c1", auth = AuthMethod.PublicKey),
                plaintextPrivateKey = "PRIVATE-KEY-1".toByteArray(),
                plaintextKeyPassphrase = "passphrase".toCharArray(),
            )

            repo.upsert(
                profile("c1", auth = AuthMethod.PublicKey),
                plaintextPrivateKey = "PRIVATE-KEY-2".toByteArray(),
            )

            val after = dao.byId("c1")!!
            assertThat(after.encryptedPrivateKey).isEqualTo("SEALED:PRIVATE-KEY-2".toByteArray(Charsets.UTF_8))
            assertThat(after.encryptedKeyPassphrase).isNull()
        }

    @Test fun `public key partial update preserves stored key when no new key bytes are supplied`() =
        runTest {
            val dao = FakeConnectionDao()
            val repo = ConnectionRepository(dao, sealingVault())

            repo.upsert(
                profile("c1", auth = AuthMethod.PublicKey),
                plaintextPrivateKey = "PRIVATE-KEY".toByteArray(),
                plaintextKeyPassphrase = "passphrase".toCharArray(),
            )
            val original = dao.byId("c1")!!

            repo.upsert(profile("c1", auth = AuthMethod.PublicKey).copy(name = "renamed"))

            val after = dao.byId("c1")!!
            assertThat(after.name).isEqualTo("renamed")
            assertThat(after.encryptedPrivateKey).isEqualTo(original.encryptedPrivateKey)
            assertThat(after.encryptedKeyPassphrase).isEqualTo(original.encryptedKeyPassphrase)
        }

    @Test fun `delete removes the row and its secrets`() =
        runTest {
            val dao = FakeConnectionDao()
            val repo = ConnectionRepository(dao, sealingVault())
            repo.upsert(profile("c1"), plaintextPassword = "x".toCharArray())

            repo.delete("c1")

            assertThat(dao.byId("c1")).isNull()
        }

    // -- helpers --------------------------------------------------------------

    /**
     * Deterministic sealing vault. `seal(x)` prepends "SEALED:" so we can
     * assert that ciphertext ≠ plaintext without any real crypto; `open`
     * strips the prefix so round-trip decrypt yields the original bytes.
     *
     * Both single-argument and two-argument overloads are stubbed because
     * the production code uses the default-argument form.
     */
    private fun sealingVault(): SecretVault {
        val v = mockk<SecretVault>()
        every { v.seal(any(), any()) } answers {
            val plain = firstArg<ByteArray>()
            ("SEALED:" + String(plain, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        }
        every { v.seal(any()) } answers {
            val plain = firstArg<ByteArray>()
            ("SEALED:" + String(plain, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        }
        every { v.open(any(), any()) } answers {
            val enc = String(firstArg<ByteArray>(), Charsets.UTF_8)
            enc.removePrefix("SEALED:").toByteArray(Charsets.UTF_8)
        }
        every { v.open(any()) } answers {
            val enc = String(firstArg<ByteArray>(), Charsets.UTF_8)
            enc.removePrefix("SEALED:").toByteArray(Charsets.UTF_8)
        }
        return v
    }

    private fun profile(
        id: String,
        auth: AuthMethod = AuthMethod.Password,
    ) = SshConnectionProfile(
        id = id,
        name = "n",
        host = "h",
        port = 22,
        username = "u",
        auth = auth,
    )

    private class FakeConnectionDao : ConnectionDao {
        private val backing = MutableStateFlow<Map<String, ConnectionEntity>>(emptyMap())

        override fun observeAll(): Flow<List<ConnectionEntity>> =
            backing.map { m -> m.values.sortedBy { it.name.lowercase() } }

        override suspend fun byId(id: String): ConnectionEntity? = backing.value[id]

        override fun search(q: String): Flow<List<ConnectionEntity>> =
            backing.map { m ->
                m.values.filter {
                    it.name.contains(q, ignoreCase = true) ||
                        it.host.contains(q, ignoreCase = true) ||
                        it.username.contains(q, ignoreCase = true)
                }.sortedBy { it.name.lowercase() }
            }

        override suspend fun upsert(entity: ConnectionEntity) {
            backing.update { it + (entity.id to entity) }
        }

        override suspend fun update(entity: ConnectionEntity) {
            backing.update { it + (entity.id to entity) }
        }

        override suspend fun delete(entity: ConnectionEntity) {
            backing.update { it - entity.id }
        }

        override suspend fun deleteById(id: String) {
            backing.update { it - id }
        }

        override suspend fun touch(
            id: String,
            ts: Long,
        ) {
            backing.update { m ->
                val existing = m[id] ?: return@update m
                m + (id to existing.copy(lastUsedEpochMs = ts))
            }
        }
    }
}

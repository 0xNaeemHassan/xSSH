package com.xssh.feature.connections

import com.xssh.core.crypto.SecretVault
import com.xssh.core.data.dao.ConnectionDao
import com.xssh.core.data.entity.ConnectionEntity
import com.xssh.core.ssh.AuthMethod
import com.xssh.core.ssh.Credential
import com.xssh.core.ssh.KeyProviders
import com.xssh.core.ssh.SshConnectionProfile
import com.xssh.core.ssh.TransportOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import javax.inject.Singleton

typealias InteractivePrompter = suspend (prompts: List<String>) -> List<String>

@Singleton
class ConnectionRepository
    @Inject
    constructor(
        private val dao: ConnectionDao,
        private val vault: SecretVault,
    ) {
        fun observeAll(): Flow<List<SshConnectionProfile>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

        fun search(q: String): Flow<List<SshConnectionProfile>> =
            dao.search(
                q,
            ).map { list -> list.map { it.toDomain() } }

        suspend fun get(id: String): SshConnectionProfile? = dao.byId(id)?.toDomain()

        suspend fun upsert(
            profile: SshConnectionProfile,
            plaintextPassword: CharArray? = null,
            plaintextPrivateKey: ByteArray? = null,
            plaintextKeyPassphrase: CharArray? = null,
        ) {
            val existing = dao.byId(profile.id)

            val encryptedPassword =
                when (profile.auth) {
                    AuthMethod.Password -> {
                        plaintextPassword?.let(::sealChars) ?: existing?.encryptedPassword
                    }
                    else -> null
                }

            val encryptedPrivateKey =
                when (profile.auth) {
                    AuthMethod.PublicKey,
                    AuthMethod.Agent,
                    -> {
                        plaintextPrivateKey?.let { vault.seal(it) }
                            ?: existing?.encryptedPrivateKey
                    }
                    else -> null
                }

            val encryptedPassphrase =
                when (profile.auth) {
                    AuthMethod.PublicKey,
                    AuthMethod.Agent,
                    -> {
                        when {
                            plaintextKeyPassphrase != null ->
                                sealChars(plaintextKeyPassphrase)
                            plaintextPrivateKey != null -> null
                            else -> existing?.encryptedKeyPassphrase
                        }
                    }
                    else -> null
                }

            dao.upsert(
                ConnectionEntity(
                    id = profile.id,
                    name = profile.name,
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authKind =
                        when (profile.auth) {
                            AuthMethod.Password -> 0
                            AuthMethod.PublicKey -> 1
                            AuthMethod.Agent -> 2
                            AuthMethod.Interactive -> 3
                        },
                    encryptedPassword = encryptedPassword,
                    encryptedPrivateKey = encryptedPrivateKey,
                    encryptedKeyPassphrase = encryptedPassphrase,
                    compression = profile.options.compression,
                    keepAliveSeconds = profile.options.keepAliveSeconds,
                    connectTimeoutMs = profile.options.connectTimeoutMs,
                    ephemeral = profile.ephemeral,
                    agentForwarding = profile.agentForwarding,
                    lastUsedEpochMs = profile.lastUsedEpochMs,
                    tags = profile.tags,
                ),
            )
        }

        suspend fun delete(id: String) = dao.deleteById(id)

        suspend fun touch(id: String) = dao.touch(id)

        suspend fun hasPrivateKey(id: String): Boolean = dao.byId(id)?.encryptedPrivateKey != null

        suspend fun hasPassword(id: String): Boolean = dao.byId(id)?.encryptedPassword != null

        suspend fun credentialFor(
            id: String,
            interactivePrompter: InteractivePrompter? = null,
        ): Credential {
            val row = dao.byId(id) ?: throw CredentialUnavailable("No such profile")
            return when (row.authKind) {
                0 -> {
                    val blob =
                        row.encryptedPassword
                            ?: throw CredentialUnavailable("This profile has no saved password.")
                    Credential.Password(openChars(blob))
                }
                1 -> {
                    val keyBlob =
                        row.encryptedPrivateKey
                            ?: throw CredentialUnavailable("No private key stored for this profile.")
                    val keyBytes = vault.open(keyBlob)
                    val passphrase = row.encryptedKeyPassphrase?.let(::openChars)
                    try {
                        Credential.PrivateKey(KeyProviders.fromBytes(keyBytes, passphrase))
                    } finally {
                        passphrase?.fill('\u0000')
                        java.util.Arrays.fill(keyBytes, 0)
                    }
                }
                2 -> {
                    val keyBlob =
                        row.encryptedPrivateKey
                            ?: throw CredentialUnavailable("No private key stored for this agent profile.")
                    val keyBytes = vault.open(keyBlob)
                    val passphrase = row.encryptedKeyPassphrase?.let(::openChars)
                    try {
                        Credential.Agent(
                            keyProvider = KeyProviders.fromBytes(keyBytes, passphrase),
                            label = "${row.username}@${row.host}:${row.port}",
                        )
                    } finally {
                        passphrase?.fill('\u0000')
                        java.util.Arrays.fill(keyBytes, 0)
                    }
                }
                3 -> {
                    val p =
                        interactivePrompter
                            ?: throw CredentialUnavailable("Keyboard-interactive requires an interactive prompter.")
                    Credential.Interactive { prompts -> kotlinx.coroutines.runBlocking { p(prompts) } }
                }
                else -> throw CredentialUnavailable("Unknown auth method (kind=${row.authKind}).")
            }
        }

        @Deprecated("Use credentialFor(id) instead.")
        suspend fun passwordCredentialFor(id: String): Credential.Password? {
            val row = dao.byId(id) ?: return null
            val blob = row.encryptedPassword ?: return null
            return Credential.Password(openChars(blob))
        }

        suspend fun openPrivateKeyBytes(id: String): ByteArray? {
            val row = dao.byId(id) ?: return null
            val blob = row.encryptedPrivateKey ?: return null
            return vault.open(blob)
        }

        suspend fun openKeyPassphrase(id: String): CharArray? {
            val row = dao.byId(id) ?: return null
            val blob = row.encryptedKeyPassphrase ?: return null
            return openChars(blob)
        }

        private fun sealChars(chars: CharArray): ByteArray {
            val encoder =
                Charsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            val encoded = encoder.encode(CharBuffer.wrap(chars))
            val plaintext = ByteArray(encoded.remaining()).also(encoded::get)
            return try {
                vault.seal(plaintext)
            } finally {
                plaintext.fill(0)
                if (encoded.hasArray()) encoded.array().fill(0)
            }
        }

        private fun openChars(ciphertext: ByteArray): CharArray {
            val plaintext = vault.open(ciphertext)
            return try {
                val decoder =
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                val decoded = decoder.decode(ByteBuffer.wrap(plaintext))
                CharArray(decoded.remaining()).also(decoded::get).also {
                    if (decoded.hasArray()) decoded.array().fill('\u0000')
                }
            } finally {
                plaintext.fill(0)
            }
        }

        private fun ConnectionEntity.toDomain() =
            SshConnectionProfile(
                id = id,
                name = name,
                host = host,
                port = port,
                username = username,
                auth =
                    when (authKind) {
                        0 -> AuthMethod.Password
                        1 -> AuthMethod.PublicKey
                        2 -> AuthMethod.Agent
                        else -> AuthMethod.Interactive
                    },
                options =
                    TransportOptions(
                        compression = compression,
                        keepAliveSeconds = keepAliveSeconds,
                        connectTimeoutMs = connectTimeoutMs,
                    ),
                ephemeral = ephemeral,
                agentForwarding = agentForwarding,
                lastUsedEpochMs = lastUsedEpochMs,
                tags = tags,
            )
    }

class CredentialUnavailable(message: String) : IllegalStateException(message)

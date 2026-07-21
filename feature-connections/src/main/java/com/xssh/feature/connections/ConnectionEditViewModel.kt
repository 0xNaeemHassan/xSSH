package com.xssh.feature.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xssh.core.crypto.KeyAlgo
import com.xssh.core.crypto.KeyMaterial
import com.xssh.core.ssh.KeyProviders
import com.xssh.core.ssh.SshConnectionProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class GeneratedKeyDraft(
    val privateKeyPem: ByteArray,
    val authorizedKey: String,
    val fingerprintSha256: String,
)

data class ConnectionEditUiState(
    val saving: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ConnectionEditViewModel
    @Inject
    constructor(
        private val repo: ConnectionRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(ConnectionEditUiState())
        val state: StateFlow<ConnectionEditUiState> = _state.asStateFlow()

        suspend fun load(id: String): SshConnectionProfile? = repo.get(id)

        fun clearError() {
            _state.value = _state.value.copy(error = null)
        }

        fun save(
            profile: SshConnectionProfile,
            plaintextPassword: CharArray?,
            plaintextPrivateKey: ByteArray? = null,
            plaintextKeyPassphrase: CharArray? = null,
            onDone: () -> Unit,
        ) {
            if (_state.value.saving) return
            _state.value = ConnectionEditUiState(saving = true)
            viewModelScope.launch {
                var saved = false
                try {
                    if (plaintextPrivateKey != null) {
                        withContext(Dispatchers.Default) {
                            KeyProviders.fromBytes(plaintextPrivateKey, plaintextKeyPassphrase)
                        }
                    }
                    withContext(Dispatchers.IO) {
                        repo.upsert(
                            profile,
                            plaintextPassword = plaintextPassword,
                            plaintextPrivateKey = plaintextPrivateKey,
                            plaintextKeyPassphrase = plaintextKeyPassphrase,
                        )
                    }
                    _state.value = ConnectionEditUiState()
                    saved = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    _state.value =
                        ConnectionEditUiState(
                            saving = false,
                            error = t.message ?: "Unable to save this connection.",
                        )
                } finally {
                    plaintextPassword?.fill('\u0000')
                    plaintextKeyPassphrase?.fill('\u0000')
                    plaintextPrivateKey?.fill(0)
                }
                if (saved) onDone()
            }
        }

        suspend fun hasStoredPassword(id: String): Boolean = repo.hasPassword(id)

        suspend fun hasStoredPrivateKey(id: String): Boolean = repo.hasPrivateKey(id)

        suspend fun fingerprintForStoredKey(id: String): String? {
            return withContext(Dispatchers.Default) {
                val bytes = repo.openPrivateKeyBytes(id) ?: return@withContext null
                val passphrase = repo.openKeyPassphrase(id)
                try {
                    val provider = KeyProviders.fromBytes(bytes, passphrase)
                    val publicKey = provider.public ?: return@withContext null
                    KeyMaterial.fingerprintSha256(publicKey)
                } catch (_: Throwable) {
                    null
                } finally {
                    passphrase?.fill('\u0000')
                    bytes.fill(0)
                }
            }
        }

        suspend fun generateEd25519Draft(comment: String): GeneratedKeyDraft =
            withContext(Dispatchers.Default) {
                val pair = KeyMaterial.generate(KeyAlgo.ED25519)
                GeneratedKeyDraft(
                    privateKeyPem = KeyMaterial.toPkcs8PemBytes(pair),
                    authorizedKey = KeyMaterial.toOpenSshAuthorizedKey(pair, comment = comment),
                    fingerprintSha256 = KeyMaterial.fingerprintSha256(pair.public),
                )
            }
    }

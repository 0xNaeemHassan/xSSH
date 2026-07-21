package com.xssh.core.ssh

import com.hierynomus.sshj.key.KeyAlgorithm
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AbstractAuthMethod
import org.slf4j.LoggerFactory
import java.security.PublicKey
import java.util.ArrayDeque

/**
 * Minimal in-app SSH agent bridge for Android.
 */
internal fun interface AgentSigner {
    fun sign(
        keyAlgorithm: KeyAlgorithm,
        data: ByteArray,
    ): ByteArray
}

internal class InProcessAgentSigner(
    private val keyProvider: KeyProvider,
) : AgentSigner {
    val identityBlob: ByteArray by lazy(LazyThreadSafetyMode.NONE) {
        Buffer.PlainBuffer().putPublicKey(requirePublicKey()).compactData
    }

    override fun sign(
        keyAlgorithm: KeyAlgorithm,
        data: ByteArray,
    ): ByteArray {
        val privateKey = keyProvider.private ?: error("SSH agent key is missing a private key")
        val signature = keyAlgorithm.newSignature()
        signature.initSign(privateKey)
        signature.update(data)
        val encoded = signature.encode(signature.sign())
        return Buffer.PlainBuffer()
            .putString(signature.signatureName)
            .putString(encoded)
            .compactData
    }

    private fun requirePublicKey(): PublicKey = keyProvider.public ?: error("SSH agent key is missing a public key")
}

internal class AgentBackedAuthMethod(
    private val keyProvider: KeyProvider,
    private val signer: AgentSigner,
    private val label: String,
) : AbstractAuthMethod("publickey") {
    private val logger = LoggerFactory.getLogger(javaClass)
    private var candidateAlgorithms: ArrayDeque<KeyAlgorithm>? = null

    override fun buildReq(): SSHPacket = buildRequest(withSignature = false)

    override fun shouldRetry(): Boolean {
        val queue = candidateAlgorithms ?: return false
        if (queue.isNotEmpty()) queue.removeFirst()
        return queue.isNotEmpty()
    }

    override fun handle(
        msg: Message,
        buf: SSHPacket,
    ) {
        if (msg == Message.USERAUTH_60) {
            sendSignedRequest()
            return
        }
        super.handle(msg, buf)
    }

    private fun buildRequest(withSignature: Boolean): SSHPacket {
        val request =
            super.buildReq()
                .putBoolean(withSignature)
        appendPublicKey(request)
        if (withSignature) appendSignature(request)
        return request
    }

    private fun appendPublicKey(packet: SSHPacket) {
        val keyAlgorithm = currentKeyAlgorithm()
        packet.putString(keyAlgorithm.keyAlgorithm)
        packet.putString(publicIdentityBlob())
    }

    private fun appendSignature(packet: SSHPacket) {
        val keyAlgorithm = currentKeyAlgorithm()
        val dataToSign =
            Buffer.PlainBuffer()
                .putString(params.transport.sessionID)
                .putBuffer(packet)
                .compactData
        val signatureBlob = signer.sign(keyAlgorithm, dataToSign)
        val decoded = Buffer.PlainBuffer(signatureBlob)
        packet.putSignature(decoded.readString(), decoded.readBytes())
    }

    private fun sendSignedRequest() {
        try {
            val packet = buildRequest(withSignature = true)
            logger.debug("Trying agent-backed authentication with key={}", label)
            params.transport.write(packet)
        } catch (e: TransportException) {
            throw e
        } catch (e: UserAuthException) {
            throw e
        } catch (t: Throwable) {
            throw UserAuthException("SSH agent signing failed for $label", t)
        }
    }

    private fun currentKeyAlgorithm(): KeyAlgorithm {
        val queue =
            candidateAlgorithms ?: ArrayDeque<KeyAlgorithm>().also {
                val publicKey = keyProvider.public ?: throw UserAuthException("SSH agent key is missing a public key")
                val keyType = KeyType.fromKey(publicKey)
                val algorithms = params.transport.getClientKeyAlgorithms(keyType)
                if (algorithms.isEmpty()) {
                    throw UserAuthException("No negotiated client key algorithms available for ${keyType.name}")
                }
                it.addAll(algorithms)
                candidateAlgorithms = it
            }
        return queue.first()
    }

    private fun publicIdentityBlob(): ByteArray =
        when (signer) {
            is InProcessAgentSigner -> signer.identityBlob
            else ->
                Buffer.PlainBuffer()
                    .putPublicKey(
                        keyProvider.public ?: throw UserAuthException("SSH agent key is missing a public key"),
                    )
                    .compactData
        }
}

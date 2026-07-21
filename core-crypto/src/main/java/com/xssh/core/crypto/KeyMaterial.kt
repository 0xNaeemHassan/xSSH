/*
 * xSSH — Key generation and OpenSSH-format helpers.
 *
 * Supported algorithms:
 *   - Ed25519    (default, recommended)
 *   - ECDSA P-256
 *   - RSA 4096
 *
 * BouncyCastle is pinned via CryptoBootstrap.install(); if the caller forgets
 * to invoke it, we surface a clear error rather than silently failing with
 * "no such algorithm".
 */
package com.xssh.core.crypto

import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

enum class KeyAlgo(val displayName: String) {
    ED25519("Ed25519 (recommended)"),
    ECDSA_P256("ECDSA P-256"),
    RSA_4096("RSA 4096"),
}

object KeyMaterial {
    fun generate(algo: KeyAlgo): KeyPair {
        checkBouncyCastleReady()
        val rnd = SecureRandom()
        return when (algo) {
            KeyAlgo.ED25519 ->
                KeyPairGenerator.getInstance("Ed25519", "BC")
                    .apply { initialize(255, rnd) }.generateKeyPair()
            KeyAlgo.ECDSA_P256 ->
                KeyPairGenerator.getInstance("EC", "BC")
                    .apply { initialize(256, rnd) }.generateKeyPair()
            KeyAlgo.RSA_4096 ->
                KeyPairGenerator.getInstance("RSA", "BC")
                    .apply { initialize(4096, rnd) }.generateKeyPair()
        }
    }

    /**
     * Encodes a public key into the OpenSSH `authorized_keys` line format:
     *   <keytype> <base64 wire encoding> <comment>
     * All supported key types emit their RFC 4251 wire encoding.
     */
    fun toOpenSshAuthorizedKey(
        pair: KeyPair,
        comment: String = "xssh",
    ): String {
        val pk = pair.public
        return when (pk.algorithm) {
            "Ed25519", "EdDSA" -> {
                val wire = ed25519WireFormat(pk)
                "ssh-ed25519 ${Base64.getEncoder().encodeToString(wire)} $comment"
            }
            "EC", "ECDSA" -> {
                val wire = ecdsaWireFormat(pk)
                "ecdsa-sha2-nistp256 ${Base64.getEncoder().encodeToString(wire)} $comment"
            }
            "RSA" -> {
                val wire = rsaWireFormat(pk)
                "ssh-rsa ${Base64.getEncoder().encodeToString(wire)} $comment"
            }
            else -> error("Unsupported public-key algorithm: ${pk.algorithm}")
        }
    }

    /** SHA-256 fingerprint over the OpenSSH wire encoding, formatted as `SHA256:...`. */
    fun fingerprintSha256(wireEncoding: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(wireEncoding)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    fun fingerprintSha256(publicKey: PublicKey): String {
        val wire =
            when (publicKey.algorithm) {
                "Ed25519", "EdDSA" -> ed25519WireFormat(publicKey)
                "EC", "ECDSA" -> ecdsaWireFormat(publicKey)
                "RSA" -> rsaWireFormat(publicKey)
                else -> error("Unsupported public-key algorithm: ${publicKey.algorithm}")
            }
        return fingerprintSha256(wire)
    }

    /** Returns wipeable PKCS#8 PEM bytes suitable for sshj's PKCS8 key provider. */
    fun toPkcs8PemBytes(pair: KeyPair): ByteArray {
        val pkcs8 = pair.private.encoded
        val b64 = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encode(pkcs8)
        return try {
            ByteArrayOutputStream().use { output ->
                output.write("-----BEGIN PRIVATE KEY-----\n".toByteArray(Charsets.US_ASCII))
                output.write(b64)
                output.write('\n'.code)
                output.write("-----END PRIVATE KEY-----\n".toByteArray(Charsets.US_ASCII))
                output.toByteArray()
            }
        } finally {
            pkcs8.fill(0)
            b64.fill(0)
        }
    }

    private fun checkBouncyCastleReady() {
        check(Security.getProvider("BC") != null) {
            "BouncyCastle not initialized. Call CryptoBootstrap.install() from Application.onCreate."
        }
    }

    // ---- OpenSSH wire format helpers (RFC 4251 string format) ---------------

    private fun ed25519WireFormat(pk: PublicKey): ByteArray {
        val raw = pk.encoded.takeLast(32).toByteArray() // last 32 bytes of SPKI = raw Ed25519 key
        val bao = ByteArrayOutputStream()
        writeString(bao, "ssh-ed25519".toByteArray(Charsets.US_ASCII))
        writeString(bao, raw)
        return bao.toByteArray()
    }

    private fun ecdsaWireFormat(pk: PublicKey): ByteArray {
        require(pk is ECPublicKey) { "Expected an EC public key" }
        val coordinateSize = (pk.params.curve.field.fieldSize + 7) / 8
        val point =
            byteArrayOf(0x04) +
                fixedUnsigned(pk.w.affineX.toByteArray(), coordinateSize) +
                fixedUnsigned(pk.w.affineY.toByteArray(), coordinateSize)
        val bao = ByteArrayOutputStream()
        writeString(bao, "ecdsa-sha2-nistp256".toByteArray(Charsets.US_ASCII))
        writeString(bao, "nistp256".toByteArray(Charsets.US_ASCII))
        writeString(bao, point)
        return bao.toByteArray()
    }

    private fun rsaWireFormat(pk: PublicKey): ByteArray {
        require(pk is RSAPublicKey) { "Expected an RSA public key" }
        val bao = ByteArrayOutputStream()
        writeString(bao, "ssh-rsa".toByteArray(Charsets.US_ASCII))
        writeString(bao, pk.publicExponent.toByteArray())
        writeString(bao, pk.modulus.toByteArray())
        return bao.toByteArray()
    }

    private fun fixedUnsigned(
        bytes: ByteArray,
        size: Int,
    ): ByteArray {
        val unsigned = if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        require(unsigned.size <= size) { "Coordinate does not fit expected curve size" }
        return ByteArray(size).also { unsigned.copyInto(it, destinationOffset = size - unsigned.size) }
    }

    private fun writeString(
        bao: ByteArrayOutputStream,
        bytes: ByteArray,
    ) {
        val len = bytes.size
        bao.write((len ushr 24) and 0xFF)
        bao.write((len ushr 16) and 0xFF)
        bao.write((len ushr 8) and 0xFF)
        bao.write(len and 0xFF)
        bao.write(bytes)
    }
}

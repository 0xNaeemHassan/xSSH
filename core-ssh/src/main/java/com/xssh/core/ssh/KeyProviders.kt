/*
 * xSSH — KeyProviders.
 */
package com.xssh.core.ssh

import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.CharArrayReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object KeyProviders {
    fun fromBytes(
        pemOrOpenSsh: ByteArray,
        passphrase: CharArray? = null,
    ): KeyProvider {
        val decoder =
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = decoder.decode(ByteBuffer.wrap(pemOrOpenSsh))
        val chars = CharArray(decoded.remaining()).also(decoded::get)
        if (decoded.hasArray()) decoded.array().fill('\u0000')
        val finder = passphrase?.let(::CharArrayPasswordFinder)
        val provider =
            when {
                chars.containsMarker("-----BEGIN OPENSSH PRIVATE KEY-----") -> OpenSSHKeyFile()
                chars.startsWithMarker("PuTTY-User-Key-File-") -> PuTTYKeyFile()
                chars.containsMarker("-----BEGIN ENCRYPTED PRIVATE KEY-----") ||
                    chars.containsMarker("-----BEGIN PRIVATE KEY-----") -> PKCS8KeyFile()
                chars.containsMarker("-----BEGIN ") -> OpenSSHKeyFile()
                else -> OpenSSHKeyFile()
            }
        return try {
            val reader = CharArrayReader(chars)
            if (finder != null) provider.init(reader, finder) else provider.init(reader)
            // Parse now while the wipeable reader and passphrase are valid.
            requireNotNull(provider.private) { "Private key data did not contain a private key" }
            requireNotNull(provider.public) { "Private key data did not contain a public key" }
            provider
        } finally {
            chars.fill('\u0000')
        }
    }

    private fun CharArray.startsWithMarker(marker: String): Boolean {
        var start = 0
        while (start < size && this[start].isWhitespace()) start++
        if (size - start < marker.length) return false
        return marker.indices.all { this[start + it] == marker[it] }
    }

    private fun CharArray.containsMarker(marker: String): Boolean {
        if (marker.length > size) return false
        for (start in 0..size - marker.length) {
            if (marker.indices.all { this[start + it] == marker[it] }) return true
        }
        return false
    }

    private class CharArrayPasswordFinder(private val chars: CharArray) : PasswordFinder {
        override fun reqPassword(resource: Resource<*>?): CharArray = chars.copyOf()

        override fun shouldRetry(resource: Resource<*>?): Boolean = false
    }
}

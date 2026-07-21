package com.xssh.core.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.BeforeClass
import org.junit.Test
import java.security.KeyPairGenerator

class KeyMaterialTest {
    companion object {
        @BeforeClass @JvmStatic
        fun installBc() {
            CryptoBootstrap.install()
        }
    }

    @Test fun `Ed25519 generation produces a well-formed key pair`() {
        val kp = KeyMaterial.generate(KeyAlgo.ED25519)
        assertThat(kp.public).isNotNull()
        assertThat(kp.private).isNotNull()
        assertThat(kp.public.algorithm).ignoringCase().contains("ed25519")
    }

    @Test fun `ECDSA P-256 generation succeeds`() {
        val kp = KeyMaterial.generate(KeyAlgo.ECDSA_P256)
        assertThat(kp.public.algorithm).isAnyOf("EC", "ECDSA")
    }

    @Test fun `fingerprintSha256 emits SHA256 prefix`() {
        val fp = KeyMaterial.fingerprintSha256("hello world".toByteArray())
        assertThat(fp).startsWith("SHA256:")
    }

    @Test fun `Ed25519 openssh line has ssh-ed25519 prefix`() {
        val kp = KeyMaterial.generate(KeyAlgo.ED25519)
        val line = KeyMaterial.toOpenSshAuthorizedKey(kp, "test@xssh")
        assertThat(line).startsWith("ssh-ed25519 ")
        assertThat(line).endsWith(" test@xssh")
    }

    @Test fun `RSA openssh line uses RFC wire encoding rather than X509 bytes`() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val line = KeyMaterial.toOpenSshAuthorizedKey(kp, "rsa@xssh")
        val wire = java.util.Base64.getDecoder().decode(line.split(' ')[1])

        assertThat(line).startsWith("ssh-rsa ")
        assertThat(String(wire.copyOfRange(4, 11), Charsets.US_ASCII)).isEqualTo("ssh-rsa")
    }
}

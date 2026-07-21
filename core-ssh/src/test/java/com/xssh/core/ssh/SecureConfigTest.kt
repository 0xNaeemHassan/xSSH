package com.xssh.core.ssh

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SecureConfigTest {
    @Test
    fun `transport offers only explicitly approved algorithms`() {
        val config = buildSecureConfig(TransportOptions())

        assertThat(config.cipherFactories.map { it.name }).isNotEmpty()
        assertThat(config.cipherFactories.map { it.name }).containsNoneOf(
            "3des-cbc",
            "aes128-cbc",
            "arcfour",
        )
        assertThat(config.cipherFactories.map { it.name }).containsNoDuplicates()
        assertThat(config.cipherFactories.map { it.name }).containsExactlyElementsIn(
            SECURE_CIPHERS.filter(config.cipherFactories.map { it.name }.toSet()::contains),
        ).inOrder()
        assertThat(config.macFactories.all { it.name in SECURE_MACS }).isTrue()
        assertThat(config.keyExchangeFactories.all { it.name in SECURE_KEY_EXCHANGES }).isTrue()
        assertThat(config.keyAlgorithms.all { it.name in SECURE_KEY_ALGORITHMS }).isTrue()
    }

    @Test
    fun `profile can further disable an approved algorithm but cannot add one`() {
        val config =
            buildSecureConfig(
                TransportOptions(disabledAlgorithms = setOf("aes128-ctr", "ssh-rsa")),
            )

        assertThat(config.cipherFactories.map { it.name }).doesNotContain("aes128-ctr")
        assertThat(config.keyAlgorithms.map { it.name }).doesNotContain("ssh-rsa")
    }
}

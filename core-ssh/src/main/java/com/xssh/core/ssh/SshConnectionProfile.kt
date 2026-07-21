/*
 * xSSH — Value type describing an SSH connection profile.
 *
 * A profile is the *user-supplied* description; it never carries decrypted key
 * material. Secrets live in :core-crypto (Android Keystore-wrapped) and are
 * fetched at connect time only via the credential-provider callback.
 */
package com.xssh.core.ssh

import java.util.UUID

data class SshConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val auth: AuthMethod,
    val options: TransportOptions = TransportOptions(),
    /** True → nothing about this session is persisted (no history, no known_hosts add). */
    val ephemeral: Boolean = false,
    /**
     * Imported compatibility metadata for `ForwardAgent yes`.
     * The pinned Android sshj transport cannot forward an SSH agent, so the
     * editor keeps this disabled and [SshSession] deliberately ignores it.
     */
    val agentForwarding: Boolean = false,
    val lastUsedEpochMs: Long? = null,
    val tags: List<String> = emptyList(),
) {
    init {
        require(SAFE_ID.matches(id)) { "Connection id is invalid" }
        require(name.isNotBlank() && name.length <= 256 && name.none(Char::isISOControl)) {
            "Connection name is invalid"
        }
        require(
            host.isNotBlank() &&
                host.length <= 253 &&
                host.none(Char::isWhitespace) &&
                host.none(Char::isISOControl),
        ) { "Connection host is invalid" }
        require(port in 1..65_535) { "Connection port must be 1..65535" }
        require(username.length <= 256 && username.none(Char::isISOControl)) {
            "Connection username is invalid"
        }
        require(tags.size <= 100 && tags.all { tag -> tag.length <= 128 && tag.none(Char::isISOControl) }) {
            "Connection tags are invalid"
        }
        require(lastUsedEpochMs == null || lastUsedEpochMs >= 0) { "Last-used timestamp is invalid" }
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9._-]{1,128}")
    }
}

sealed interface AuthMethod {
    data object Password : AuthMethod

    data object PublicKey : AuthMethod

    data object Agent : AuthMethod

    data object Interactive : AuthMethod
}

data class TransportOptions(
    val compression: Boolean = true,
    val keepAliveSeconds: Int = 30,
    val connectTimeoutMs: Int = 10_000,
    val readTimeoutMs: Int = 0,
    /** Additional per-profile removals from the app's strict modern allowlists. */
    val disabledAlgorithms: Set<String> =
        setOf(
            "ssh-dss",
            "ssh-rsa",
            "ssh-rsa-sha1",
            "diffie-hellman-group1-sha1",
            "diffie-hellman-group14-sha1",
            "diffie-hellman-group-exchange-sha1",
            "hmac-md5",
            "hmac-md5-96",
            "hmac-sha1",
            "hmac-sha1-96",
            "3des-cbc",
            "aes128-cbc",
            "aes192-cbc",
            "aes256-cbc",
            "arcfour",
            "arcfour128",
            "arcfour256",
            "blowfish-cbc",
            "cast128-cbc",
        ),
) {
    init {
        require(keepAliveSeconds >= 0) { "keepAliveSeconds must not be negative" }
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
        require(readTimeoutMs >= 0) { "readTimeoutMs must not be negative" }
    }
}

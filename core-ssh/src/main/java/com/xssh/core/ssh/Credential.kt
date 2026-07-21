/*
 * xSSH — Credentials handed to sshj for a single authentication attempt.
 *
 * These objects are short-lived. The Password variant uses CharArray so the
 * caller can `password.fill('\u0000')` after auth completes. sshj will drop
 * its own copy shortly after the handshake.
 */
package com.xssh.core.ssh

import net.schmizz.sshj.userauth.keyprovider.KeyProvider

sealed interface Credential {
    /** Identity-only equality avoids comparing or stringifying secret data. */
    class Password(val password: CharArray) : Credential

    data class PrivateKey(val keyProvider: KeyProvider) : Credential

    data class Agent(
        val keyProvider: KeyProvider,
        val label: String,
    ) : Credential

    data class Interactive(val respond: (prompts: List<String>) -> List<String>) : Credential
}

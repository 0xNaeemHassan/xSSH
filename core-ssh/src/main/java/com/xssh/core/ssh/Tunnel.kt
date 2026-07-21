/*
 * xSSH — Port forwarding surface.
 *
 * Three flavors:
 *   - LOCAL   : local port → remote host:port (like `ssh -L`)
 *   - REMOTE  : remote port → local host:port (like `ssh -R`)
 *   - DYNAMIC : local SOCKS proxy (like `ssh -D`)
 *
 * Implementation lives in feature-tunnels; this file is the pure model.
 */
package com.xssh.core.ssh

import java.util.UUID

data class Tunnel(
    val id: String = UUID.randomUUID().toString(),
    val connectionId: String,
    val kind: Kind,
    val bindHost: String = "127.0.0.1",
    val bindPort: Int,
    val destHost: String? = null,
    val destPort: Int? = null,
    val autoStart: Boolean = false,
) {
    enum class Kind { LOCAL, REMOTE, DYNAMIC }

    init {
        require(SAFE_ID.matches(id)) { "Tunnel id is invalid" }
        require(SAFE_ID.matches(connectionId)) { "Tunnel connectionId is invalid" }
        require(validHost(bindHost)) { "Tunnel bindHost is invalid" }
        require(bindPort in 1..65_535) { "Tunnel bindPort must be 1..65535" }
        when (kind) {
            Kind.LOCAL, Kind.REMOTE -> {
                require(destHost?.let(::validHost) == true && destPort != null) {
                    "LOCAL/REMOTE tunnels require destHost and destPort"
                }
                require(destPort in 1..65_535) { "Tunnel destPort must be 1..65535" }
            }
            Kind.DYNAMIC ->
                require(destHost == null && destPort == null) {
                    "DYNAMIC (SOCKS) tunnels must not have destHost/destPort"
                }
        }
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9._-]{1,128}")

        fun validHost(host: String): Boolean =
            host.isNotBlank() &&
                host.length <= 253 &&
                host.none(Char::isWhitespace) &&
                host.none(Char::isISOControl)
    }
}

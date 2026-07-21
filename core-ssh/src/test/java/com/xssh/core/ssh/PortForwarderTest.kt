package com.xssh.core.ssh

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PortForwarderTest {
    @Test fun `LOCAL requires destination`() {
        assertThrows(IllegalArgumentException::class.java) {
            Tunnel(connectionId = "c", kind = Tunnel.Kind.LOCAL, bindPort = 8080)
        }
    }

    @Test fun `DYNAMIC forbids destination`() {
        assertThrows(IllegalArgumentException::class.java) {
            Tunnel(
                connectionId = "c",
                kind = Tunnel.Kind.DYNAMIC,
                bindPort = 1080,
                destHost = "example.com",
                destPort = 80,
            )
        }
    }

    @Test fun `DYNAMIC builds cleanly`() {
        val t = Tunnel(connectionId = "c", kind = Tunnel.Kind.DYNAMIC, bindPort = 1080)
        assertThat(t.kind).isEqualTo(Tunnel.Kind.DYNAMIC)
        assertThat(t.bindPort).isEqualTo(1080)
    }

    @Test fun `default denylist covers legacy algorithms`() {
        val disabled = TransportOptions().disabledAlgorithms
        assertThat(disabled).containsAtLeast(
            "ssh-dss",
            "hmac-md5",
            "hmac-sha1",
            "3des-cbc",
            "aes256-cbc",
            "diffie-hellman-group1-sha1",
            "arcfour",
        )
    }
}

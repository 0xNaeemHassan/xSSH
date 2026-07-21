package com.xssh.feature.connections

import com.google.common.truth.Truth.assertThat
import com.xssh.core.data.entity.SnippetEntity
import com.xssh.core.data.entity.TunnelEntity
import com.xssh.core.ssh.AuthMethod
import com.xssh.core.ssh.SshConnectionProfile
import com.xssh.core.ssh.TransportOptions
import org.junit.Assert.assertThrows
import org.junit.Test

class ProfileTransferCodecTest {
    @Test fun `bundle round-trip preserves connections tunnels snippets and secret hints`() {
        val snapshot =
            TransferSnapshot(
                connections =
                    listOf(
                        SshConnectionProfile(
                            id = "c1",
                            name = "prod-gateway",
                            host = "prod.example.com",
                            port = 2222,
                            username = "root",
                            auth = AuthMethod.Agent,
                            options =
                                TransportOptions(
                                    compression = true,
                                    keepAliveSeconds = 45,
                                    connectTimeoutMs = 20_000,
                                ),
                            ephemeral = true,
                            agentForwarding = true,
                            tags = listOf("prod", "bastion"),
                        ),
                    ),
                tunnels =
                    listOf(
                        TunnelEntity(
                            id = "t1",
                            connectionId = "c1",
                            kind = 0,
                            bindHost = "127.0.0.1",
                            bindPort = 5432,
                            destHost = "db.internal",
                            destPort = 5432,
                            autoStart = true,
                            label = "postgres",
                        ),
                    ),
                snippets =
                    listOf(
                        SnippetEntity(
                            id = "s1",
                            label = "uptime",
                            body = "uptime && whoami",
                            tags = listOf("ops"),
                            executeOnPaste = true,
                        ),
                    ),
                connectionSecrets = mapOf("c1" to SecretPresence(hasPassword = false, hasPrivateKey = true)),
            )

        val encoded = ProfileTransferCodec.encodeBundle(snapshot)
        val decoded = ProfileTransferCodec.decodeBundle(encoded)

        assertThat(decoded.connections).hasSize(1)
        assertThat(decoded.connections.single().auth).isEqualTo(AuthMethod.Agent)
        assertThat(decoded.connections.single().agentForwarding).isTrue()
        assertThat(decoded.connections.single().tags).containsExactly("prod", "bastion").inOrder()
        assertThat(decoded.tunnels.single().label).isEqualTo("postgres")
        assertThat(decoded.snippets.single().executeOnPaste).isTrue()
        assertThat(decoded.connectionSecrets["c1"]?.hasPrivateKey).isTrue()
    }

    @Test fun `OpenSSH parser imports auth hints and skips wildcard aliases`() {
        val input =
            """
            Host *
                ServerAliveInterval 60
                Compression yes

            # xssh-auth: agent
            # xssh-tags: prod,bastion
            Host prod-* prod-gateway
                HostName prod.example.com
                User root
                Port 2222
                ForwardAgent yes
            """.trimIndent()

        val (profiles, warnings) = ProfileTransferCodec.decodeOpenSshConfig(input)

        assertThat(profiles).hasSize(1)
        val imported = profiles.single()
        assertThat(imported.name).isEqualTo("prod-gateway")
        assertThat(imported.host).isEqualTo("prod.example.com")
        assertThat(imported.username).isEqualTo("root")
        assertThat(imported.port).isEqualTo(2222)
        assertThat(imported.auth).isEqualTo(AuthMethod.Agent)
        assertThat(imported.agentForwarding).isTrue()
        assertThat(imported.options.compression).isTrue()
        assertThat(imported.options.keepAliveSeconds).isEqualTo(60)
        assertThat(imported.tags).containsExactly("prod", "bastion").inOrder()
        assertThat(warnings.single()).contains("wildcard")
    }

    @Test fun `bundle decoder rejects unsupported formats before importing rows`() {
        val bad = """{"format":"not-xssh","version":1,"connections":[],"tunnels":[],"snippets":[]}"""
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                ProfileTransferCodec.decodeBundle(bad)
            }
        assertThat(error).hasMessageThat().contains("not an xSSH")
    }

    @Test fun `OpenSSH parser applies global and trailing defaults without overriding concrete values`() {
        val input =
            """
            ConnectTimeout 9
            Host alpha
                HostName "alpha.example.com" # inline comment
                User deploy
                User ignored-second-value
                Port 2200

            Host *
                User fallback
                Compression yes
                ServerAliveInterval 55
            """.trimIndent()

        val (profiles, warnings) = ProfileTransferCodec.decodeOpenSshConfig(input)

        assertThat(warnings).isEmpty()
        val imported = profiles.single()
        assertThat(imported.host).isEqualTo("alpha.example.com")
        assertThat(imported.username).isEqualTo("deploy")
        assertThat(imported.port).isEqualTo(2200)
        assertThat(imported.options.compression).isTrue()
        assertThat(imported.options.keepAliveSeconds).isEqualTo(55)
        assertThat(imported.options.connectTimeoutMs).isEqualTo(9_000)
    }
}

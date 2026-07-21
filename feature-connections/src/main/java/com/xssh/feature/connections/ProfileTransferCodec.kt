package com.xssh.feature.connections

import com.xssh.core.data.entity.SnippetEntity
import com.xssh.core.data.entity.TunnelEntity
import com.xssh.core.ssh.AuthMethod
import com.xssh.core.ssh.SshConnectionProfile
import com.xssh.core.ssh.TransportOptions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

private val safeTransferId = Regex("[A-Za-z0-9._-]{1,128}")

private val transferJson =
    Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

@Serializable
internal data class ConnectionBundleV1(
    val format: String = "xssh-connection-bundle",
    val version: Int = 1,
    val exportedAtUtc: String = Instant.now().toString(),
    val notes: List<String> =
        listOf(
            "Secrets are intentionally omitted from export bundles.",
            "Re-enter passwords, private keys, or passphrases after import.",
        ),
    val connections: List<ExportedConnection> = emptyList(),
    val tunnels: List<ExportedTunnel> = emptyList(),
    val snippets: List<ExportedSnippet> = emptyList(),
)

@Serializable
internal data class ExportedConnection(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val auth: String,
    val compression: Boolean,
    val keepAliveSeconds: Int,
    val connectTimeoutMs: Int,
    val ephemeral: Boolean,
    val agentForwarding: Boolean,
    val tags: List<String> = emptyList(),
    val hasPassword: Boolean = false,
    val hasPrivateKey: Boolean = false,
    val lastUsedEpochMs: Long? = null,
)

@Serializable
internal data class ExportedTunnel(
    val id: String,
    val connectionId: String,
    val kind: Int,
    val bindHost: String,
    val bindPort: Int,
    val destHost: String? = null,
    val destPort: Int? = null,
    val autoStart: Boolean = false,
    val label: String = "",
)

@Serializable
internal data class ExportedSnippet(
    val id: String,
    val label: String,
    val body: String,
    val tags: List<String> = emptyList(),
    val executeOnPaste: Boolean = false,
)

internal data class TransferSnapshot(
    val connections: List<SshConnectionProfile>,
    val tunnels: List<TunnelEntity>,
    val snippets: List<SnippetEntity>,
    val connectionSecrets: Map<String, SecretPresence>,
)

internal data class SecretPresence(
    val hasPassword: Boolean,
    val hasPrivateKey: Boolean,
)

internal data class ImportResult(
    val source: String,
    val importedConnections: Int,
    val importedTunnels: Int,
    val importedSnippets: Int,
    val warnings: List<String> = emptyList(),
)

private data class ParsedHostBlock(
    val aliases: List<String>,
    val options: Map<String, String>,
    val authHint: String? = null,
    val tags: List<String> = emptyList(),
)

internal object ProfileTransferCodec {
    fun encodeBundle(snapshot: TransferSnapshot): String {
        val bundle =
            ConnectionBundleV1(
                connections =
                    snapshot.connections.map { profile ->
                        val secrets = snapshot.connectionSecrets[profile.id] ?: SecretPresence(false, false)
                        ExportedConnection(
                            id = profile.id,
                            name = profile.name,
                            host = profile.host,
                            port = profile.port,
                            username = profile.username,
                            auth = profile.auth.serialName,
                            compression = profile.options.compression,
                            keepAliveSeconds = profile.options.keepAliveSeconds,
                            connectTimeoutMs = profile.options.connectTimeoutMs,
                            ephemeral = profile.ephemeral,
                            agentForwarding = profile.agentForwarding,
                            tags = profile.tags,
                            hasPassword = secrets.hasPassword,
                            hasPrivateKey = secrets.hasPrivateKey,
                            lastUsedEpochMs = profile.lastUsedEpochMs,
                        )
                    },
                tunnels =
                    snapshot.tunnels.map {
                        ExportedTunnel(
                            id = it.id,
                            connectionId = it.connectionId,
                            kind = it.kind,
                            bindHost = it.bindHost,
                            bindPort = it.bindPort,
                            destHost = it.destHost,
                            destPort = it.destPort,
                            autoStart = it.autoStart,
                            label = it.label,
                        )
                    },
                snippets =
                    snapshot.snippets.map {
                        ExportedSnippet(
                            id = it.id,
                            label = it.label,
                            body = it.body,
                            tags = it.tags,
                            executeOnPaste = it.executeOnPaste,
                        )
                    },
            )
        return transferJson.encodeToString(ConnectionBundleV1.serializer(), bundle)
    }

    // Bundle validation is intentionally centralized so no decoded field bypasses the same import boundary.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun decodeBundle(text: String): TransferSnapshot {
        val bundle = transferJson.decodeFromString(ConnectionBundleV1.serializer(), text)
        require(bundle.format == "xssh-connection-bundle") { "This is not an xSSH connection bundle." }
        require(bundle.version == 1) { "Unsupported xSSH bundle version: ${bundle.version}." }
        require(bundle.connections.size <= 5_000) { "Bundle contains too many connections." }
        require(bundle.tunnels.size <= 10_000) { "Bundle contains too many tunnels." }
        require(bundle.snippets.size <= 10_000) { "Bundle contains too many snippets." }
        require(bundle.connections.map { it.id }.distinct().size == bundle.connections.size) {
            "Bundle contains duplicate connection IDs."
        }
        require(bundle.tunnels.map { it.id }.distinct().size == bundle.tunnels.size) {
            "Bundle contains duplicate tunnel IDs."
        }
        require(bundle.snippets.map { it.id }.distinct().size == bundle.snippets.size) {
            "Bundle contains duplicate snippet IDs."
        }
        val connectionIds = bundle.connections.mapTo(mutableSetOf()) { it.id }
        bundle.connections.forEach { connection ->
            require(safeTransferId.matches(connection.id)) { "Invalid connection ID." }
            require(
                connection.host.isNotBlank() &&
                    connection.host.length <= 253 &&
                    connection.host.none(Char::isWhitespace) &&
                    connection.host.none(Char::isISOControl),
            ) { "Invalid connection host." }
            require(
                connection.username.isNotBlank() &&
                    connection.username.length <= 256 &&
                    connection.username.none(Char::isISOControl),
            ) { "Invalid connection username." }
            require(
                connection.name.isNotBlank() &&
                    connection.name.length <= 256 &&
                    connection.name.none(Char::isISOControl),
            ) { "Invalid connection name." }
            require(connection.auth in setOf("password", "public_key", "agent", "interactive")) {
                "Invalid authentication method: ${connection.auth}."
            }
            require(connection.port in 1..65_535) { "Invalid connection port: ${connection.port}." }
            require(connection.keepAliveSeconds in 0..3600) { "Invalid keepalive value." }
            require(connection.connectTimeoutMs in 1_000..120_000) { "Invalid connect timeout." }
            require(
                connection.tags.size <= 100 &&
                    connection.tags.all { tag -> tag.length <= 128 && tag.none(Char::isISOControl) },
            ) {
                "Invalid connection tags."
            }
            require(connection.lastUsedEpochMs == null || connection.lastUsedEpochMs >= 0) {
                "Invalid last-used timestamp."
            }
        }
        bundle.tunnels.forEach { tunnel ->
            require(safeTransferId.matches(tunnel.id)) { "Invalid tunnel ID." }
            require(tunnel.connectionId in connectionIds) { "Tunnel references a missing connection." }
            require(tunnel.kind in 0..2) { "Invalid tunnel type." }
            require(
                tunnel.bindHost.isNotBlank() &&
                    tunnel.bindHost.length <= 253 &&
                    tunnel.bindHost.none(Char::isWhitespace) &&
                    tunnel.bindHost.none(Char::isISOControl),
            ) { "Invalid tunnel bind host." }
            require(tunnel.bindPort in 1..65_535) { "Invalid tunnel bind port." }
            if (tunnel.kind != 2) {
                require(
                    !tunnel.destHost.isNullOrBlank() &&
                        tunnel.destHost.length <= 253 &&
                        tunnel.destHost.none(Char::isWhitespace) &&
                        tunnel.destHost.none(Char::isISOControl),
                ) {
                    "Tunnel destination host is missing or invalid."
                }
                require(tunnel.destPort?.let { it in 1..65_535 } == true) { "Invalid tunnel destination port." }
            } else {
                require(tunnel.destHost == null && tunnel.destPort == null) {
                    "Dynamic tunnels cannot contain a destination."
                }
            }
            require(tunnel.label.length <= 256 && tunnel.label.none(Char::isISOControl)) {
                "Tunnel label is invalid."
            }
        }
        bundle.snippets.forEach { snippet ->
            require(safeTransferId.matches(snippet.id)) { "Invalid snippet ID." }
            require(
                snippet.label.isNotBlank() &&
                    snippet.label.length <= 256 &&
                    snippet.label.none(Char::isISOControl),
            ) { "Invalid snippet label." }
            require(
                snippet.body.isNotBlank() &&
                    snippet.body.length <= 256 * 1024 &&
                    '\u0000' !in snippet.body,
            ) { "Invalid snippet body." }
            require(
                snippet.tags.size <= 100 &&
                    snippet.tags.all { tag -> tag.length <= 128 && tag.none(Char::isISOControl) },
            ) { "Invalid snippet tags." }
        }
        val connections =
            bundle.connections.map {
                SshConnectionProfile(
                    id = it.id,
                    name = it.name,
                    host = it.host,
                    port = it.port,
                    username = it.username,
                    auth = it.auth.toAuthMethod(),
                    options =
                        TransportOptions(
                            compression = it.compression,
                            keepAliveSeconds = it.keepAliveSeconds,
                            connectTimeoutMs = it.connectTimeoutMs,
                        ),
                    ephemeral = it.ephemeral,
                    agentForwarding = it.agentForwarding,
                    lastUsedEpochMs = it.lastUsedEpochMs,
                    tags = it.tags,
                )
            }
        val tunnels =
            bundle.tunnels.map {
                TunnelEntity(
                    id = it.id,
                    connectionId = it.connectionId,
                    kind = it.kind,
                    bindHost = it.bindHost,
                    bindPort = it.bindPort,
                    destHost = it.destHost,
                    destPort = it.destPort,
                    autoStart = it.autoStart,
                    label = it.label,
                )
            }
        val snippets =
            bundle.snippets.map {
                SnippetEntity(
                    id = it.id,
                    label = it.label,
                    body = it.body,
                    tags = it.tags,
                    executeOnPaste = it.executeOnPaste,
                )
            }
        return TransferSnapshot(
            connections = connections,
            tunnels = tunnels,
            snippets = snippets,
            connectionSecrets =
                bundle.connections.associate { dto ->
                    dto.id to SecretPresence(dto.hasPassword, dto.hasPrivateKey)
                },
        )
    }

    fun encodeOpenSshConfig(connections: List<SshConnectionProfile>): String {
        val header =
            listOf(
                "# xSSH export",
                "# Secrets are intentionally omitted.",
                "# Re-enter passwords or import private keys after migration.",
                "",
            )
        val body =
            connections.flatMap { profile ->
                buildList {
                    add("Host ${profile.name.toOpenSshAlias()}")
                    add("    HostName ${profile.host}")
                    add("    User ${profile.username}")
                    add("    Port ${profile.port}")
                    add("    Compression ${if (profile.options.compression) "yes" else "no"}")
                    add("    ServerAliveInterval ${profile.options.keepAliveSeconds}")
                    add("    # xssh-auth: ${profile.auth.serialName}")
                    if (profile.agentForwarding) add("    ForwardAgent yes")
                    if (profile.tags.isNotEmpty()) add("    # xssh-tags: ${profile.tags.joinToString(",")}")
                    add("")
                }
            }
        return (header + body).joinToString("\n").trimEnd() + "\n"
    }

    // This is a small state-machine parser; keeping directives in source order preserves OpenSSH semantics.
    @Suppress("CyclomaticComplexMethod")
    fun decodeOpenSshConfig(text: String): Pair<List<SshConnectionProfile>, List<String>> {
        val warnings = mutableListOf<String>()
        val defaults = linkedMapOf<String, String>()
        val blocks = mutableListOf<ParsedHostBlock>()
        var currentAliases = emptyList<String>()
        var currentOptions = linkedMapOf<String, String>()
        var currentAuthHint: String? = null
        var currentTags = emptyList<String>()
        var pendingAuthHint: String? = null
        var pendingTags = emptyList<String>()
        var afterBlankLine = false

        fun flushCurrent() {
            if (currentAliases.isEmpty()) return
            if (currentAliases.size == 1 && currentAliases.first() == "*") {
                currentOptions.forEach { (key, value) -> defaults.putIfAbsent(key, value) }
            } else {
                val plainAliases = currentAliases.filterNot { it.contains('*') || it.contains('?') || it.contains('!') }
                if (plainAliases.isEmpty()) {
                    warnings += "Skipped wildcard-only Host block: ${currentAliases.joinToString(" ")}"
                } else {
                    blocks +=
                        ParsedHostBlock(
                            aliases = plainAliases,
                            options = currentOptions,
                            authHint = currentAuthHint,
                            tags = currentTags,
                        )
                    if (plainAliases.size != currentAliases.size) {
                        warnings += "Skipped wildcard aliases in Host block: ${currentAliases.joinToString(" ")}"
                    }
                }
            }
            currentAliases = emptyList()
            currentOptions = linkedMapOf()
            currentAuthHint = null
            currentTags = emptyList()
        }

        text.lineSequence().forEach { rawLine ->
            val trimmedRaw = rawLine.trim()
            val line = if (trimmedRaw.startsWith('#')) trimmedRaw else stripOpenSshComment(rawLine).trim()
            when {
                line.isBlank() -> afterBlankLine = true
                line.startsWith("#") -> {
                    when {
                        line.startsWith("# xssh-auth:", ignoreCase = true) -> {
                            val hint = line.substringAfter(':').trim().takeIf { it.isNotEmpty() }
                            if (currentAliases.isEmpty() || afterBlankLine) {
                                pendingAuthHint = hint
                            } else {
                                currentAuthHint = hint
                            }
                        }
                        line.startsWith("# xssh-tags:", ignoreCase = true) -> {
                            val tags =
                                line.substringAfter(':')
                                    .split(',')
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                            if (currentAliases.isEmpty() || afterBlankLine) {
                                pendingTags = tags
                            } else {
                                currentTags = tags
                            }
                        }
                    }
                }
                else -> {
                    val parts = line.split(Regex("\\s+"), limit = 2)
                    if (parts.isEmpty()) return@forEach
                    val key = parts.first().lowercase()
                    val value = decodeOpenSshValue(parts.getOrElse(1) { "" })
                    if (key == "host") {
                        flushCurrent()
                        currentAliases =
                            value.split(Regex("\\s+"))
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                        currentAuthHint = pendingAuthHint
                        currentTags = pendingTags
                        pendingAuthHint = null
                        pendingTags = emptyList()
                    } else if (currentAliases.isNotEmpty()) {
                        // OpenSSH uses the first obtained value for almost all
                        // scalar keywords; repeated later directives must not
                        // silently change what is imported.
                        currentOptions.putIfAbsent(key, value)
                    } else {
                        // Directives before the first Host block are global.
                        defaults.putIfAbsent(key, value)
                    }
                    afterBlankLine = false
                }
            }
        }
        flushCurrent()

        require(blocks.sumOf { it.aliases.size } <= 5_000) {
            "OpenSSH config contains too many concrete host aliases."
        }
        val profiles =
            blocks.flatMap { block ->
                block.aliases.mapNotNull { alias ->
                    // Apply Host * / global defaults regardless of whether they
                    // appeared before or after a concrete block. Concrete values
                    // intentionally win for predictable migration UX.
                    val options = defaults + block.options
                    val host = (options["hostname"] ?: alias).trim()
                    val portText = options["port"]
                    val port = portText?.toIntOrNull() ?: if (portText == null) 22 else -1
                    val keepAliveText = options["serveraliveinterval"]
                    val keepAlive = keepAliveText?.toIntOrNull() ?: if (keepAliveText == null) 30 else -1
                    val timeoutText = options["connecttimeout"]
                    val timeoutSeconds = timeoutText?.toIntOrNull() ?: if (timeoutText == null) 15 else -1
                    val tags = block.tags.distinctBy { it.lowercase() }
                    val invalidEntry =
                        listOf(
                            alias.length > 256,
                            host.isBlank(),
                            host.length > 253,
                            host.any(Char::isWhitespace),
                            host.any(Char::isISOControl),
                            '%' in host,
                            port !in 1..65_535,
                            keepAlive !in 0..3_600,
                            timeoutSeconds !in 1..120,
                        ).any { it }
                    if (invalidEntry) {
                        warnings += "Skipped invalid Host entry: $alias"
                        return@mapNotNull null
                    }
                    if (block.authHint != null && block.authHint.toAuthMethodOrNull() == null) {
                        warnings += "Host $alias has an unknown xssh-auth hint; inferred authentication instead."
                    }
                    val username = options["user"].orEmpty().trim()
                    if (username.length > 256) {
                        warnings += "Skipped Host entry with an invalid User value: $alias"
                        return@mapNotNull null
                    }
                    if (username.any(Char::isISOControl)) {
                        warnings += "Skipped Host entry with control characters in User: $alias"
                        return@mapNotNull null
                    }
                    if (tags.size > 100 || tags.any { tag -> tag.length > 128 || tag.any(Char::isISOControl) }) {
                        warnings += "Skipped Host entry with invalid xssh-tags: $alias"
                        return@mapNotNull null
                    }
                    if (username.isBlank()) {
                        warnings += "Host $alias has no User value; add a username before connecting."
                    }
                    val auth = inferAuthMethod(block.authHint, options)
                    SshConnectionProfile(
                        id = UUID.randomUUID().toString(),
                        name = alias,
                        host = host,
                        port = port,
                        username = username,
                        auth = auth,
                        options =
                            TransportOptions(
                                compression = options["compression"].equals("yes", ignoreCase = true),
                                keepAliveSeconds = keepAlive,
                                connectTimeoutMs = timeoutSeconds * 1_000,
                            ),
                        agentForwarding = options["forwardagent"].equals("yes", ignoreCase = true),
                        tags = tags,
                    )
                }
            }
        return profiles to warnings
    }

    private fun inferAuthMethod(
        authHint: String?,
        options: Map<String, String>,
    ): AuthMethod {
        authHint?.toAuthMethodOrNull()?.let { return it }
        val preferred = options["preferredauthentications"].orEmpty().lowercase()
        return when {
            preferred.contains("keyboard-interactive") -> AuthMethod.Interactive
            options.containsKey("identityagent") -> AuthMethod.Agent
            options.containsKey("identityfile") -> AuthMethod.PublicKey
            else -> AuthMethod.Password
        }
    }

    private val AuthMethod.serialName: String
        get() =
            when (this) {
                AuthMethod.Password -> "password"
                AuthMethod.PublicKey -> "public_key"
                AuthMethod.Agent -> "agent"
                AuthMethod.Interactive -> "interactive"
            }

    private fun String.toAuthMethod(): AuthMethod = toAuthMethodOrNull() ?: AuthMethod.Password

    private fun String.toAuthMethodOrNull(): AuthMethod? =
        when (trim().lowercase()) {
            "password" -> AuthMethod.Password
            "public_key", "publickey", "key", "private_key" -> AuthMethod.PublicKey
            "agent" -> AuthMethod.Agent
            "interactive", "keyboard-interactive", "keyboard_interactive" -> AuthMethod.Interactive
            else -> null
        }

    private fun String.toOpenSshAlias(): String =
        lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "xssh-host" }

    private fun stripOpenSshComment(raw: String): String {
        var quoted = false
        var escaped = false
        raw.forEachIndexed { index, char ->
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> quoted = !quoted
                char == '#' && !quoted -> return raw.substring(0, index)
            }
        }
        return raw
    }

    private fun decodeOpenSshValue(raw: String): String {
        val value = raw.trim()
        if (value.length < 2 || value.first() != '"' || value.last() != '"') return value
        return buildString(value.length - 2) {
            var escaped = false
            value.substring(1, value.lastIndex).forEach { char ->
                if (escaped) {
                    append(char)
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else {
                    append(char)
                }
            }
            if (escaped) append('\\')
        }
    }
}

/*
 * xSSH — Top-level build script.
 *
 * The `verifyNoTelemetry` task is our privacy contract enforced as code.
 * It scans the resolved dependency graph and fails the build if any
 * analytics / crash-reporting / ad SDK slips in — even transitively.
 * CI runs it on every PR.
 */
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

subprojects {
    pluginManager.apply("org.jlleitschuh.gradle.ktlint")
    pluginManager.apply("io.gitlab.arturbosch.detekt")
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        parallel = true
    }
}

val bannedTelemetryPrefixes = listOf(
    "com.google.firebase:firebase-analytics",
    "com.google.firebase:firebase-crashlytics",
    "com.google.firebase:firebase-perf",
    "com.google.android.gms:play-services-ads",
    "com.google.android.gms:play-services-analytics",
    "com.appsflyer",
    "io.branch",
    "com.facebook.android:facebook-android-sdk",
    "com.amplitude",
    "com.mixpanel",
    "com.segment",
    "io.sentry:sentry-android",
    "io.bugsnag",
    "com.datadog",
)

tasks.register("verifyNoTelemetry") {
    group = "verification"
    description = "Fails the build if a banned analytics / crash / ad SDK is on the classpath."
    notCompatibleWithConfigurationCache("Scans resolved configurations across every project at execution time")
    doLast {
        val hits = mutableListOf<String>()
        allprojects.forEach { p ->
            p.configurations
                .filter { it.name.endsWith("RuntimeClasspath", ignoreCase = true) }
                .filter { it.isCanBeResolved }
                .forEach { c ->
                    val resolved = c.resolvedConfiguration
                    resolved.rethrowFailure()
                    resolved.lenientConfiguration.allModuleDependencies.forEach { d ->
                        val id = "${d.moduleGroup}:${d.moduleName}"
                        if (bannedTelemetryPrefixes.any { id.startsWith(it) }) {
                            hits += "${p.path} :: $id"
                        }
                    }
                }
        }
        if (hits.isNotEmpty()) {
            throw GradleException(
                "Banned telemetry / analytics / ad dependencies detected:\n" +
                    hits.joinToString("\n") { "  - $it" } +
                    "\n\nxSSH has a strict no-telemetry policy.",
            )
        }
        println("verifyNoTelemetry: OK — no banned SDKs in dependency graph.")
    }
}

tasks.register("generateSbomLite") {
    group = "reporting"
    description = "Writes a lightweight dependency SBOM snapshot to build/reports/sbom-lite.txt."
    notCompatibleWithConfigurationCache("Scans resolved configurations across every project at execution time")
    doLast {
        val out = layout.buildDirectory.file("reports/sbom-lite.txt").get().asFile
        out.parentFile.mkdirs()
        val lines = mutableListOf<String>()
        lines += "# xSSH SBOM (lightweight)"
        lines += "# Generated: ${java.time.Instant.now()}"
        lines += ""
        allprojects.sortedBy { it.path }.forEach { p ->
            p.configurations
                .filter { it.name.endsWith("RuntimeClasspath", ignoreCase = true) }
                .filter { it.isCanBeResolved }
                .forEach { c ->
                    lines += "## ${p.path}:${c.name}"
                    val resolved = c.resolvedConfiguration
                    resolved.rethrowFailure()
                    resolved.lenientConfiguration.allModuleDependencies
                        .sortedBy { "${it.moduleGroup}:${it.moduleName}:${it.moduleVersion}" }
                        .forEach { d ->
                            lines += "- ${d.moduleGroup}:${d.moduleName}:${d.moduleVersion}"
                        }
                    lines += ""
                }
        }
        out.writeText(lines.joinToString("\n"))
        println("generateSbomLite: wrote ${out.absolutePath}")
    }
}

tasks.register("generateLicenseReportLite") {
    group = "reporting"
    description = "Writes a lightweight third-party dependency inventory to build/reports/licenses-lite.txt."
    notCompatibleWithConfigurationCache("Scans resolved configurations across every project at execution time")
    doLast {
        val out = layout.buildDirectory.file("reports/licenses-lite.txt").get().asFile
        out.parentFile.mkdirs()
        val seen = linkedSetOf<String>()
        allprojects.sortedBy { it.path }.forEach { p ->
            p.configurations
                .filter { it.name.endsWith("RuntimeClasspath", ignoreCase = true) }
                .filter { it.isCanBeResolved }
                .forEach { c ->
                    val resolved = c.resolvedConfiguration
                    resolved.rethrowFailure()
                    resolved.lenientConfiguration.allModuleDependencies.forEach { d ->
                        seen += "${d.moduleGroup}:${d.moduleName}:${d.moduleVersion}"
                    }
                }
        }
        out.writeText(
            buildString {
                appendLine("# xSSH dependency inventory")
                appendLine("# Generated: ${java.time.Instant.now()}")
                appendLine()
                seen.sorted().forEach { appendLine(it) }
            },
        )
        println("generateLicenseReportLite: wrote ${out.absolutePath}")
    }
}

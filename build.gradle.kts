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

val bannedTelemetryPrefixes =
    listOf(
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

val telemetryChecks =
    subprojects.map { child ->
        child.tasks.register("verifyNoTelemetryDependencies") {
            group = "verification"
            description = "Checks this project's runtime classpaths for banned telemetry SDKs."
            notCompatibleWithConfigurationCache("Resolves this project's runtime dependency graphs")
            doLast {
                val hits = mutableListOf<String>()
                child.configurations
                    .filter { it.name.endsWith("RuntimeClasspath", ignoreCase = true) }
                    .filter { it.isCanBeResolved }
                    .forEach { configuration ->
                        val resolved = configuration.resolvedConfiguration
                        resolved.rethrowFailure()
                        resolved.lenientConfiguration.allModuleDependencies.forEach { dependency ->
                            val id = "${dependency.moduleGroup}:${dependency.moduleName}"
                            if (bannedTelemetryPrefixes.any { id.startsWith(it) }) hits += id
                        }
                    }
                if (hits.isNotEmpty()) {
                    throw GradleException(
                        "Banned telemetry / analytics / ad dependencies detected in ${child.path}:\n" +
                            hits.distinct().sorted().joinToString("\n") { "  - $it" } +
                            "\n\nxSSH has a strict no-telemetry policy.",
                    )
                }
            }
        }
    }

val dependencyInventory =
    subprojects.map { child ->
        val fragment = child.layout.buildDirectory.file("reports/dependency-inventory-fragment.txt")
        val task =
            child.tasks.register("generateDependencyInventoryFragment") {
                group = "reporting"
                description = "Writes this project's resolved runtime dependency inventory."
                notCompatibleWithConfigurationCache("Resolves this project's runtime dependency graphs")
                outputs.file(fragment)
                outputs.upToDateWhen { false }
                doLast {
                    val lines = mutableListOf<String>()
                    child.configurations
                        .filter { it.name.endsWith("RuntimeClasspath", ignoreCase = true) }
                        .filter { it.isCanBeResolved }
                        .sortedBy { it.name }
                        .forEach { configuration ->
                            lines += "## ${child.path}:${configuration.name}"
                            val resolved = configuration.resolvedConfiguration
                            resolved.rethrowFailure()
                            resolved.lenientConfiguration.allModuleDependencies
                                .map { "${it.moduleGroup}:${it.moduleName}:${it.moduleVersion}" }
                                .distinct()
                                .sorted()
                                .forEach { lines += "- $it" }
                            lines += ""
                        }
                    val output = fragment.get().asFile
                    output.parentFile.mkdirs()
                    output.writeText(lines.joinToString("\n"))
                }
            }
        task to fragment
    }

tasks.register("verifyNoTelemetry") {
    group = "verification"
    description = "Fails the build if a banned analytics / crash / ad SDK is on the classpath."
    dependsOn(telemetryChecks)
    doLast { println("verifyNoTelemetry: OK — no banned SDKs in dependency graph.") }
}

tasks.register("generateSbomLite") {
    group = "reporting"
    description = "Writes a lightweight dependency SBOM snapshot to build/reports/sbom-lite.txt."
    dependsOn(dependencyInventory.map { it.first })
    notCompatibleWithConfigurationCache("Aggregates generated dependency inventory fragments")
    outputs.upToDateWhen { false }
    doLast {
        val out = layout.buildDirectory.file("reports/sbom-lite.txt").get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            buildString {
                appendLine("# xSSH SBOM (lightweight)")
                appendLine("# Generated: ${java.time.Instant.now()}")
                appendLine()
                dependencyInventory.forEach { appendLine(it.second.get().asFile.readText()) }
            },
        )
        println("generateSbomLite: wrote ${out.absolutePath}")
    }
}

tasks.register("generateLicenseReportLite") {
    group = "reporting"
    description = "Writes a lightweight third-party dependency inventory to build/reports/licenses-lite.txt."
    dependsOn(dependencyInventory.map { it.first })
    notCompatibleWithConfigurationCache("Aggregates generated dependency inventory fragments")
    outputs.upToDateWhen { false }
    doLast {
        val out = layout.buildDirectory.file("reports/licenses-lite.txt").get().asFile
        out.parentFile.mkdirs()
        val dependencies =
            dependencyInventory
                .flatMap { it.second.get().asFile.readLines() }
                .filter { it.startsWith("- ") }
                .map { it.removePrefix("- ") }
                .toSortedSet()
        out.writeText(
            buildString {
                appendLine("# xSSH dependency inventory")
                appendLine("# Generated: ${java.time.Instant.now()}")
                appendLine()
                dependencies.forEach(::appendLine)
            },
        )
        println("generateLicenseReportLite: wrote ${out.absolutePath}")
    }
}

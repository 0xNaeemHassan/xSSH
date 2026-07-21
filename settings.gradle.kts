pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Termux terminal-emulator / terminal-view live on JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "xSSH"
include(":app")
include(":core-ssh")
include(":core-terminal")
include(":core-crypto")
include(":core-data")
include(":design-system")
include(":feature-connections")
include(":feature-session")
include(":feature-sftp")
include(":feature-tunnels")
include(":feature-snippets")

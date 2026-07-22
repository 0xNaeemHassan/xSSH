/*
 * xSSH — :app module
 */
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProperties =
    Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.isFile) {
            localPropertiesFile.inputStream().use { load(it) }
        }
    }

fun releaseSigningValue(
    injectedProperty: String,
    localProperty: String,
): String? =
    providers.gradleProperty(injectedProperty).orNull
        ?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(localProperty)?.takeIf { it.isNotBlank() }

val releaseStoreFile =
    releaseSigningValue(
        "android.injected.signing.store.file",
        "xssh.signing.storeFile",
    )
val releaseStorePassword =
    releaseSigningValue(
        "android.injected.signing.store.password",
        "xssh.signing.storePassword",
    )
val releaseKeyAlias =
    releaseSigningValue(
        "android.injected.signing.key.alias",
        "xssh.signing.keyAlias",
    )
val releaseKeyPassword =
    releaseSigningValue(
        "android.injected.signing.key.password",
        "xssh.signing.keyPassword",
    )
val releaseSigningValues =
    listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    )
val hasReleaseSigning = releaseSigningValues.all { it != null }

check(releaseSigningValues.none { it != null } || hasReleaseSigning) {
    "Release signing is only partially configured. Provide store file, store password, key alias, and key password."
}

android {
    namespace = "com.xssh.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xssh.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            storeFile =
                rootProject.file(
                    releaseStoreFile ?: "keystore/RELEASE_SIGNING_NOT_CONFIGURED",
                )
            storePassword = releaseStorePassword ?: "not-configured"
            keyAlias = releaseKeyAlias ?: "not-configured"
            keyPassword = releaseKeyPassword ?: "not-configured"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs +=
            listOf(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            )
    }

    packaging {
        jniLibs.pickFirsts += "**/libtermux.so"
        resources {
            excludes +=
                setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/DEPENDENCIES",
                    "/META-INF/LICENSE*",
                    "/META-INF/NOTICE*",
                )
        }
    }
}

dependencies {
    implementation(project(":core-ssh"))
    implementation(project(":core-terminal"))
    implementation(project(":core-crypto"))
    implementation(project(":design-system"))
    implementation(project(":feature-connections"))
    implementation(project(":feature-session"))
    implementation(project(":feature-sftp"))
    implementation(project(":feature-tunnels"))
    implementation(project(":feature-snippets"))
    implementation(project(":core-data"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.bundles.lifecycle)
    implementation(libs.bundles.coroutines)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

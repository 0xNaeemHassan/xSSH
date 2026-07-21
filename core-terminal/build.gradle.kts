plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.xssh.core.terminal"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 31
        externalNativeBuild {
            ndkBuild {
                arguments += "APP_PLATFORM=android-31"
            }
        }
    }
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }
    packaging {
        jniLibs.pickFirsts += "**/libtermux.so"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.bundles.coroutines)
    // Termux terminal engine (Apache 2.0 exception). Provides the full VT/xterm-256color
    // parser, wide-glyph handling, and battle-tested selection code.
    // The JNI bridge is rebuilt below with NDK r28 for 16 KiB page-size support.
    api(libs.bundles.termux)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

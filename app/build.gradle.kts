plugins {
    id("com.android.application")
    kotlin("android") // version supplied by the root Kotlin plugin classpath
    id("org.jetbrains.kotlin.plugin.compose")
}
val releaseSigningNames = listOf(
    "YU_RELEASE_KEYSTORE", "YU_RELEASE_KEYSTORE_PASSWORD",
    "YU_RELEASE_KEY_ALIAS", "YU_RELEASE_KEY_PASSWORD",
)
val releaseSigning = releaseSigningNames.associateWith { providers.environmentVariable(it).orNull }
val hasReleaseSigning = releaseSigning.values.all { !it.isNullOrBlank() }

// Validate only packaging/signing tasks: core tests, lint and debug builds need no secrets.
val requireReleaseSigning = tasks.register("requireReleaseSigning") {
    doLast {
        val missing = releaseSigningNames.filter { releaseSigning[it].isNullOrBlank() }
        check(missing.isEmpty()) {
            "Release signing configuration missing: ${missing.joinToString()}. See docs/SIGNING.md."
        }
        check(file(releaseSigning.getValue("YU_RELEASE_KEYSTORE")!!).isFile) {
            "Release keystore file does not exist. See docs/SIGNING.md."
        }
    }
}
tasks.configureEach {
    if (name in setOf("packageRelease", "packageReleaseBundle", "signReleaseBundle", "assembleRelease", "bundleRelease", "validateSigningRelease")) {
        dependsOn(requireReleaseSigning)
    }
}

android {
    namespace = "io.yu.flash"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.yu.flash"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (hasReleaseSigning) {
            create("permanentRelease") {
                storeFile = file(releaseSigning.getValue("YU_RELEASE_KEYSTORE")!!)
                storeType = "PKCS12"
                storePassword = releaseSigning.getValue("YU_RELEASE_KEYSTORE_PASSWORD")
                keyAlias = releaseSigning.getValue("YU_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("YU_RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("permanentRelease")
        }
    }
    // Preserve common upstream license resources rather than excluding them.
    packaging { resources.merges += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.window:window:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

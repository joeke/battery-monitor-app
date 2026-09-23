plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val updateManifestUrl = providers.gradleProperty("UPDATE_MANIFEST_URL")
    .getOrElse("https://joeke.dev/android-apps/battery-monitor/latest.json")
val escapedUpdateManifestUrl = updateManifestUrl.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.jbd.bmsmonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jbd.bmsmonitor"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "0.5.5"

        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$escapedUpdateManifestUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("testRelease") {
            storeFile = rootProject.file("signing/test-release.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // This project distributes test APKs directly, not through an app store.
            // This shared test key makes release APKs upgrade-compatible across build machines.
            signingConfig = signingConfigs.getByName("testRelease")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing details live in local.properties (git-ignored), never in this file.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.rishabh.clockdown"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.rishabh.clockdown"
        minSdk = 26
        targetSdk = 36
        // Bump versionCode by 1 for EVERY release (a whole number; Android and the in-app updater compare it).
        // versionName is only the label people see, e.g. "1.1". The GitHub release tag must equal versionCode.
        versionCode = 2
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Where crash reports go. Lives in local.properties (git-ignored); empty means crash reporting is simply off.
        buildConfigField("String", "SENTRY_DSN", "\"${localProps.getProperty("sentry.dsn", "")}\"")
    }

    signingConfigs {
        create("release") {
            val store = localProps.getProperty("release.storeFile")
            if (store != null) {
                storeFile = file(store)
                storePassword = localProps.getProperty("release.storePassword")
                keyAlias = localProps.getProperty("release.keyAlias")
                keyPassword = localProps.getProperty("release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Without local.properties (e.g. a fresh clone) the release build is simply unsigned instead of failing.
            if (localProps.getProperty("release.storeFile") != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)
    implementation(libs.sentry.android.core) // crash reports (see CrashReporting)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver) // a fake GitHub for the updater tests
    testImplementation(libs.org.json) // real org.json for JVM tests; android.jar only has stubs
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
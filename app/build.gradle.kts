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
        // 1.9.3 (18) is the last GitHub build; Google Play already has 2.0 (20), so the next Play release is 21.
        versionCode = 18
        versionName = "1.9.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Where crash reports go. Lives in local.properties (git-ignored); empty means crash reporting is simply off.
        buildConfigField("String", "SENTRY_DSN", "\"${localProps.getProperty("sentry.dsn", "")}\"")
        // The GitHub build updates itself from GitHub Releases; the Play build ("play" below) leaves updates to Google Play.
        buildConfigField("boolean", "SELF_UPDATE", "true")
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
        // Google Play: the same app and signing key as release, without the GitHub self-updater and its install permissions
        // (src/play/AndroidManifest.xml). Build it with ./gradlew bundlePlay -> app/build/outputs/bundle/play/app-play.aab.
        create("play") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            buildConfigField("boolean", "SELF_UPDATE", "false")
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
    implementation(libs.play.services.mlkit.document.scanner) // "Scan pages to PDF" for uploads (see Scan.kt); falls back to the plain camera without Google Play services
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
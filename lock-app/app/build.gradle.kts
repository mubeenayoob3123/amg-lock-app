plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pk.amgsystems.lock"
    compileSdk = 34

    defaultConfig {
        applicationId = "pk.amgsystems.lock"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Debug APK: fast install on the test phone, no obfuscation.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Background check-in: polls the AMG server for lock state.
    // WorkManager survives reboots and Doze; minimum periodic interval is 15 min.
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    // Encrypted storage (PU-M7 / A-M11): pairing code, deviceId, server
    // URL, reset PIN aur offline-code hash encrypted rehte hain
    // (SecurePrefs -> EncryptedSharedPreferences).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // No Retrofit / no extra networking library on purpose:
    // ServerApi uses HttpURLConnection + org.json (built into Android),
    // so the app has fewer dependencies to break on a shop phone.
}

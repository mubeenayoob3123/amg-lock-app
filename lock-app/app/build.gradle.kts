plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// FCM (Wave 5.4): Google Services plugin SIRF tab lagta hai jab
// google-services.json maujood ho. Firebase project abhi bana hi nahi,
// is liye is file ke baghair bhi app compile honi chahiye — plugin bina
// file ke build fail kar deta hai, is liye ye shart zaroori hai.
// google-services.json app/ folder mein hoti hai (Mubeen ka step —
// setup guide: hidden_files/fcm-setup-guide.md). Ye file .gitignore
// mein hai, repo mein kabhi nahi jati.
val fcmEnabled = file("google-services.json").exists()
if (fcmEnabled) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "pk.amgsystems.lock"
    compileSdk = 34

    defaultConfig {
        applicationId = "pk.amgsystems.lock"
        minSdk = 26
        targetSdk = 34
        // v1.4.0 (code 6): audit round 2 — local deadline lock, reset PIN
        // throttling + server URL lock, Safe Mode / USB debugging block,
        // fail-closed storage, FCM typed pushes, direct-boot lock.
        versionCode = 6
        versionName = "1.4.0"
        // Build flag: FCM sirf tab on jab google-services.json build ke
        // waqt maujood thi. FcmManager is ke saath runtime check bhi
        // karta hai (FirebaseApp init hua ya nahi).
        buildConfigField("boolean", "FCM_ENABLED", fcmEnabled.toString())
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
        // FCM_ENABLED flag ke liye (AGP 8 mein BuildConfig default off hai).
        buildConfig = true
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
    // FCM push (Wave 5.4): payment ke baad phone foran khule (15 minute
    // ka intezar na ho). Library hamesha maujood hai; bina
    // google-services.json ke Firebase init nahi hota aur FcmManager
    // khud ko band rakhta hai — compile + run dono mehfooz hain.
    implementation("com.google.firebase:firebase-messaging:24.0.0")
    // QR pairing scan (A-M13 / Wave 5.6): dashboard ka pairing QR camera
    // se parhein. ZXing offline chalta hai, Google Play Services ki
    // zaroorat nahi — factory-reset phone par bhi kaam karta hai.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // No Retrofit / no extra networking library on purpose:
    // ServerApi uses HttpURLConnection + org.json (built into Android),
    // so the app has fewer dependencies to break on a shop phone.
}

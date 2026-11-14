// Top-level build file — AMG Customer Lock app (Phase 3)
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // FCM (Wave 5.4): Google Services plugin ka version yahan declare
    // hai, lekin plugin app module mein SIRF tab lagta hai jab
    // google-services.json maujood ho (dekhein app/build.gradle.kts) —
    // bina Firebase project ke bhi app compile honi chahiye.
    id("com.google.gms.google-services") version "4.4.2" apply false
}

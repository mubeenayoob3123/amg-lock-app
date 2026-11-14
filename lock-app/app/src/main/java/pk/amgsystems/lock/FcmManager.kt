package pk.amgsystems.lock

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

/**
 * FCM push (Wave 5.4) — payment ke baad phone FORAN khule.
 *
 * Kaam ka tareeqa:
 *  1. App start par (LockApp) ya token badalne par
 *     (AmgFirebaseMessagingService.onNewToken) token liya jata hai aur
 *     encrypted store mein rakha jata hai (LockStateStore.fcmToken).
 *  2. Token server ko register / check-in ke saath jata hai
 *     (ServerApi — optional field, FCM band ho to bheja hi nahi jata).
 *  3. Server jab sync se account unlock/completed karta hai to is token
 *     par data-only push bhejta hai ("check-in karo"). Push par
 *     AmgFirebaseMessagingService foran aik check-in karata hai — faisla
 *     hamesha check-in ka jawab karta hai, push ka data nahi.
 *
 * Bina Firebase setup ke mehfooz (CRITICAL):
 *  - Build flag BuildConfig.FCM_ENABLED sirf tab true hota hai jab build
 *    ke waqt google-services.json maujood thi (dekhein
 *    app/build.gradle.kts). Us ke baghair bhi app compile hoti hai.
 *  - Runtime par FirebaseApp init hua ya nahi, wo bhi check hota hai —
 *    bina google-services.json ke FirebaseInitProvider init nahi kar
 *    pata (sirf aik warning log aati hai, crash nahi hota).
 *  - Har FCM call try/catch mein hai: Firebase ki koi ghalti app ko
 *    kabhi crash nahi kar sakti. FCM band ho to 15 minute wala
 *    WorkManager check-in hi chalta hai, jaise pehle chalta tha.
 *
 * Setup (Mubeen ka step): hidden_files/fcm-setup-guide.md — Firebase
 * project banayein, Android app add karein (pk.amgsystems.lock),
 * google-services.json app/ folder mein rakhein, server key Render
 * env FCM_SERVER_KEY mein lagayein, phir release APK dobara banayein.
 */
object FcmManager {

    private const val TAG = "FcmManager"

    /**
     * FCM istemal ho sakta hai? Dono shartein zaroori hain:
     * build flag (google-services.json build ke waqt thi) AUR runtime
     * init (FirebaseApp ban gaya). Aik bhi na ho to FCM band hai.
     */
    fun isAvailable(context: Context): Boolean {
        if (!BuildConfig.FCM_ENABLED) return false
        return runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)
    }

    /**
     * App start par token taaza karein — FCM band ho to kuch nahi hota
     * (no-op). Token milne par onTokenReceived chalta hai.
     */
    fun refreshTokenIfAvailable(context: Context) {
        if (!isAvailable(context)) {
            Log.d(TAG, "FCM not available (disabled or Firebase not initialised) — push off, 15-min check-in continues")
            return
        }
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> onTokenReceived(context, token) }
                .addOnFailureListener { e ->
                    Log.w(TAG, "FCM token fetch failed — push off until next launch", e)
                }
        }.onFailure { e ->
            Log.w(TAG, "FCM token fetch threw — push off", e)
        }
    }

    /**
     * Naya token mila (fetch ya onNewToken se): encrypted store mein
     * rakhein, aur registered hon to foran aik check-in karayein taake
     * naya token server tak pahunche (ServerApi check-in body mein
     * fcmToken bhejta hai). Token tabdeel na hua ho to check-in nahi.
     */
    fun onTokenReceived(context: Context, token: String?) {
        if (token.isNullOrBlank()) return
        val store = LockStateStore(context)
        val changed = store.fcmToken != token
        store.fcmToken = token
        Log.i(TAG, "FCM token ${if (changed) "updated" else "unchanged"}")
        if (changed && store.isRegistered) {
            CheckInWorker.enqueueImmediate(context)
        }
    }
}

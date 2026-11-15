package pk.amgsystems.lock

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM push receiver (Wave 5.4).
 *
 * Server sirf data-only push bhejta hai — "check-in karo" ka ishara:
 *   data = { type: "amg_checkin", reason: "unlocked"|"locked"|"completed",
 *            dashboardAccountId: "101" }
 * (Server lock par bhi push bhejta hai — unified-4 se — taake manual
 * lock 15 minute ka intezar na kare.)
 *
 * Push milte hi foran aik check-in hota hai (CheckInWorker, one-shot) —
 * faisla hamesha check-in ka jawab karta hai, push ka data nahi. Is liye
 * push mein koi lock/unlock faisla nahi hota aur ghalat ya purana push
 * phone ko ghalat lock/unlock nahi kar sakta.
 *
 * Bina Firebase setup ke ye service kabhi call nahi hoti (FirebaseApp
 * init hi nahi hota) — is liye is mein koi availability check nahi hai.
 * Manifest mein MESSAGING_EVENT intent-filter ke saath register hai.
 */
class AmgFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        // J2-p19: SIRF typed AMG push check-in karati hai — bina type
        // wali ya ghalat type wali push nazar-andaaz hoti hai. Server
        // hamesha type "amg_checkin" bhejta hai (reason: unlocked /
        // locked / completed).
        val type = message.data["type"]
        if (type != "amg_checkin") {
            Log.d(TAG, "Ignoring push without a known AMG type (type=$type)")
            return
        }
        Log.i(TAG, "Push received (type=amg_checkin) — triggering immediate check-in")
        CheckInWorker.enqueueImmediate(applicationContext)
    }

    override fun onNewToken(token: String) {
        FcmManager.onTokenReceived(applicationContext, token)
    }

    companion object {
        private const val TAG = "AmgFcm"
    }
}

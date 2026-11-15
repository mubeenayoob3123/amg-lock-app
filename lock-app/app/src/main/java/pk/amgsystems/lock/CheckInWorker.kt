package pk.amgsystems.lock

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * Background check-in (WorkManager, every 15 min, needs internet).
 *
 *  1. If the phone is not registered yet, do nothing.
 *  2. If the phone is already COMPLETED (final payment), do nothing —
 *     there is nothing left to lock, and the app should be gone anyway.
 *  3. Ask the server. No internet / server down -> Result.success()
 *     anyway (do NOT retry aggressively): the cached lock state stays,
 *     the next 15-minute tick tries again. Lekin nakaami ka status
 *     darj hota hai taake UI par nazar aaye (F-31), khamosh retry nahi.
 *  4. Apply the answer via PolicyEngine.
 *
 * Why WorkManager and not a foreground service? WorkManager survives
 * reboots, respects Doze, and cannot be killed by swiping the app
 * away. A foreground service would drain battery and show a permanent
 * notification the customer could tap to kill.
 *
 * FCM push (Wave 5.4): server jab unlock/completed ki push bhejta hai
 * to AmgFirebaseMessagingService isi worker ko one-shot chalata hai
 * (enqueueImmediate) — payment ke baad phone foran khul jata hai,
 * 15 minute ka intezar nahi hota. Push wali check-in EXPEDITED hai
 * (J1-m73) — Doze isay rok nahi sakta.
 *
 * Har tick par local deadline bhi check hoti hai (J2-M14) — network
 * ho to check-in faisla karta hai, na ho to deadline worker
 * (DeadlineCheckWorker, bina network constraint ke) ye kaam karta hai.
 */
class CheckInWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = LockStateStore(applicationContext)
        if (!store.isRegistered) {
            Log.d(TAG, "Not registered — skipping check-in")
            return Result.success()
        }
        if (store.lockState == LockState.COMPLETED) {
            Log.d(TAG, "Already completed — skipping check-in")
            return Result.success()
        }
        // J2-M14: check-in se pehle local deadline bhi dekh lein (agar
        // deadline guzar gayi to phone khud lock ho jaye — check-in
        // ka jawab aaye to PolicyEngine us ke mutabiq theek kar de ga).
        LocalDeadlineLock.evaluate(applicationContext)
        // J1-m58: FCM token fetch pehle nakaam hua ho to retry ka waqt
        // ho to dobara koshish karein (network wali tick par).
        FcmManager.maybeRetryTokenFetch(applicationContext)
        when (val outcome = ServerApi(applicationContext).checkIn()) {
            is ServerApi.CheckInOutcome.Success -> {
                PolicyEngine.apply(applicationContext, outcome.result)
            }
            is ServerApi.CheckInOutcome.ServerError -> {
                // Server ne jawab diya magar mana kar diya (masalan
                // device_not_registered) — ye "internet nahi" NAHI hai (A-M2).
                // recordCheckInServerError lagatar nakaami par dobara
                // register ka prompt bhi dikhata hai (J2-m34 / J1-m54).
                PolicyEngine.recordCheckInServerError(applicationContext, outcome.error)
            }
            is ServerApi.CheckInOutcome.NetworkError -> {
                PolicyEngine.recordFailure(
                    applicationContext,
                    PolicyEngine.statusFor(outcome.kind)
                )
            }
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "amg-lock-checkin"
        private const val TAG = "CheckInWorker"

        /**
         * Foran aik dafa check-in (FCM push ya naye token ke baad).
         * One-shot work hai — periodic 15-minute schedule ko nahi
         * chheerta. doWork() ke andar hi unregistered/completed wale
         * guard hain, is liye yahan se seedha enqueue karna mehfooz hai.
         *
         * J1-m73: EXPEDITED hai — aam work ki tarah Doze isay ghanton
         * rok nahi sakta. Quota khatam ho to aam work ki tarah chalta
         * hai (RUN_AS_NON_EXPEDITED_WORK_REQUEST). Is ke liye manifest
         * mein FOREGROUND_SERVICE permission zaroori hai (API 26–30
         * par expedited work foreground service se chalta hai).
         */
        fun enqueueImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<CheckInWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}

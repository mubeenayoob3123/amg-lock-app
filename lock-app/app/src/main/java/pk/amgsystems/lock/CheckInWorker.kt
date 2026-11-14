package pk.amgsystems.lock

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
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
 * notification the customer could tap to kill. (A push/FCM trigger
 * can be added later so a payment unlocks the phone instantly instead
 * of within 15 minutes — see README "Baad mein".)
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
        when (val outcome = ServerApi(applicationContext).checkIn()) {
            is ServerApi.CheckInOutcome.Success -> {
                PolicyEngine.apply(applicationContext, outcome.result)
            }
            is ServerApi.CheckInOutcome.ServerError -> {
                // Server ne jawab diya magar mana kar diya (masalan
                // device_not_registered) — ye "internet nahi" NAHI hai (A-M2).
                PolicyEngine.recordFailure(
                    applicationContext,
                    PolicyEngine.CheckInStatus.serverError(outcome.error)
                )
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
    }
}

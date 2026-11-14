package pk.amgsystems.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * After every reboot, re-apply the cached lock state BEFORE the
 * customer can use the phone.
 *
 * If the cached state was LOCKED, the lock screen comes straight back.
 * If it was WARNING, the warning notification comes back too
 * (notifications reboot par khatam ho jati hain).
 * Then a fresh check-in runs (on a background thread, goAsync ke
 * saath taake poora hone se pehle mara na jaye — A-n4) in case a
 * payment arrived while the phone was off.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        val store = LockStateStore(context)
        if (!store.isRegistered || store.lockState == LockState.COMPLETED) return

        Log.i(TAG, "Boot — cached state is ${store.lockState}, re-applying")
        when (store.lockState) {
            LockState.LOCKED -> LockManager(context).applyLock()
            LockState.WARNING -> NotificationHelper.showWarning(context)
            else -> Unit
        }
        // Fresh check-in in the background (payment may have arrived).
        val pendingResult = goAsync()
        Thread {
            try {
                when (val outcome = ServerApi(context).checkIn()) {
                    is ServerApi.CheckInOutcome.Success ->
                        PolicyEngine.apply(context, outcome.result)
                    is ServerApi.CheckInOutcome.ServerError ->
                        PolicyEngine.recordFailure(
                            context,
                            PolicyEngine.CheckInStatus.serverError(outcome.error)
                        )
                    is ServerApi.CheckInOutcome.NetworkError ->
                        PolicyEngine.recordFailure(context, PolicyEngine.statusFor(outcome.kind))
                }
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}

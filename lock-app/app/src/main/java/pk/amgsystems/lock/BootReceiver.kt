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
 *
 * Do boot stages (J2-p18):
 *  - LOCKED_BOOT_COMPLETED (phone abhi unlock nahi hua): encrypted
 *    prefs band hoti hain, is liye sirf BootState (device-protected
 *    minimal state: registered + lock state, koi secret nahi) parhi
 *    jati hai. LOCKED ho to lock screen dikhayi jati hai. Is path
 *    par LockStateStore / SecurePrefs ko chheerte hi nahi (na
 *    keystore retry, na key delete ka khatra). Receiver manifest
 *    mein directBootAware hai — warna ye broadcast milta hi nahi.
 *  - BOOT_COMPLETED (phone unlock ho gaya): normal path — encrypted
 *    store se halat parh kar wapas lagayein + fresh check-in +
 *    local deadline check (J2-M14: phone band raha ho to deadline
 *    guzar gayi ho gi). Storage band ho (degraded) to sirf boot
 *    state se lock wapas lagate hain.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> onLockedBoot(context)
            Intent.ACTION_BOOT_COMPLETED -> onBootCompleted(context)
            else -> return
        }
    }

    /** Locked boot — sirf BootState (device-protected) parhein, encrypted storage ko haath na lagayein. */
    private fun onLockedBoot(context: Context) {
        if (!BootState.isLocked(context)) return
        Log.i(TAG, "Locked boot — boot state says LOCKED, re-applying lock screen")
        launchBootLockScreen(context)
    }

    private fun onBootCompleted(context: Context) {
        val store = LockStateStore(context)
        if (SecurePrefs.degraded) {
            // Encrypted storage khul nahi rahi — dead store ke defaults
            // (unregistered/ACTIVE) par bharosa nahi. Sirf boot state
            // se lock wapas lagayein (agar LOCKED tha).
            if (BootState.isLocked(context)) {
                Log.w(TAG, "Boot — storage degraded, boot state says LOCKED, re-applying lock screen")
                launchBootLockScreen(context)
            }
            return
        }
        if (!store.isRegistered || store.lockState == LockState.COMPLETED) return

        Log.i(TAG, "Boot — cached state is ${store.lockState}, re-applying")
        when (store.lockState) {
            LockState.LOCKED -> LockManager(context).applyLock()
            LockState.WARNING -> NotificationHelper.showWarning(context)
            else -> Unit
        }
        // J2-M14: boot par bhi deadline check — phone band raha ho to
        // deadline guzar gayi ho gi, aur check-in se pehle hi lock lagna chahiye.
        LocalDeadlineLock.evaluate(context)
        // Fresh check-in in the background (payment may have arrived).
        val pendingResult = goAsync()
        Thread {
            try {
                when (val outcome = ServerApi(context).checkIn()) {
                    is ServerApi.CheckInOutcome.Success ->
                        PolicyEngine.apply(context, outcome.result)
                    is ServerApi.CheckInOutcome.ServerError ->
                        PolicyEngine.recordCheckInServerError(context, outcome.error)
                    is ServerApi.CheckInOutcome.NetworkError ->
                        PolicyEngine.recordFailure(context, PolicyEngine.statusFor(outcome.kind))
                }
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    /** Lock screen kholein — EXTRA_BOOT_LOCK batata hai ke ye boot-time lock hai (storage band ho sakti hai). */
    private fun launchBootLockScreen(context: Context) {
        runCatching {
            val launch = Intent(context, LockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(LockActivity.EXTRA_BOOT_LOCK, true)
            }
            context.startActivity(launch)
        }.onFailure { Log.w(TAG, "Could not launch LockActivity at boot", it) }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}

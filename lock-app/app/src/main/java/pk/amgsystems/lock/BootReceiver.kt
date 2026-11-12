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
 * Then a fresh check-in runs (on a background thread) in case a
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
        if (store.lockState == LockState.LOCKED) {
            LockManager(context).applyLock()
        }
        // Fresh check-in in the background (payment may have arrived).
        Thread {
            ServerApi(context).checkIn()?.let { PolicyEngine.apply(context, it) }
        }.start()
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}

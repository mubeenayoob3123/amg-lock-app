package pk.amgsystems.lock

import android.content.Context
import android.util.Log

/**
 * Turns a server check-in answer into a lock action.
 * Single place where instruction strings become behaviour, so the
 * policy is identical whether the check-in came from WorkManager,
 * from MainActivity ("Abhi Check Karein") or from boot.
 *
 * Instructions from the server:
 *   stay_unlocked     -> phone normal (payment covered the overdue qist,
 *                        or nothing was due). Any cached lock is lifted.
 *   warn              -> qist overdue but grace (3/5/7 din) still running:
 *                        phone usable, warning banner shown.
 *   lock_now          -> grace khatam: LOCK. A fresh one-time offline
 *                        code may arrive with this answer — store only
 *                        its hash (OfflineCodeStore), never the plaintext.
 *   permanent_unlock  -> final payment: remove Device Owner, uninstall.
 *
 * No internet (checkIn() returns null) -> DO NOTHING. The cached
 * state stays exactly as it was, so a customer cannot unlock by
 * switching off the internet, and a paid customer is not wrongly
 * locked by a failed request.
 */
object PolicyEngine {

    fun apply(context: Context, result: ServerApi.CheckInResult) {
        val store = LockStateStore(context)
        val lockManager = LockManager(context)

        store.dueDate = result.dueDate
        store.remainingAmount = result.remainingAmount
        store.graceDaysLeft = result.graceDaysLeft
        store.shopName = result.shopName
        store.shopPhone = result.shopPhone
        store.lastCheckInAt = System.currentTimeMillis()
        store.lastCheckInResult = result.instruction

        when (result.instruction) {
            "lock_now" -> {
                result.offlineUnlockCode?.let { code ->
                    OfflineCodeStore(context).storeCode(code)
                    Log.i(TAG, "Fresh offline unlock code stored (hash only)")
                }
                lockManager.applyLock()
            }
            "warn" -> lockManager.applyWarning()
            "permanent_unlock" -> lockManager.applyPermanentUnlock()
            else -> { // "stay_unlocked" and anything unknown = safe default: unlock
                OfflineCodeStore(context).clear()
                lockManager.applyUnlock()
            }
        }
    }

    private const val TAG = "PolicyEngine"
}

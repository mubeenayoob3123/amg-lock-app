package pk.amgsystems.lock

import android.content.Context
import android.content.Intent
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
 * No internet (checkIn() returns NetworkError) -> DO NOTHING. The cached
 * state stays exactly as it was, so a customer cannot unlock by
 * switching off the internet, and a paid customer is not wrongly
 * locked by a failed request.
 *
 * A-M1 (fail-safe): koi NA-MAALOOM instruction (masalan dashboard wali
 * "show_warning", ya khaali instruction) kabhi unlock NAHI karti —
 * purani halat barkarar rehti hai aur offline code bhi nahi jalta.
 */
object PolicyEngine {

    fun apply(context: Context, result: ServerApi.CheckInResult) {
        val store = LockStateStore(context)
        val lockManager = LockManager(context)
        val previousState = store.lockState

        // Display fields hamesha taaza rakhein (ye sirf dikhane ke liye hain).
        store.dueDate = result.dueDate
        store.remainingAmount = result.remainingAmount
        store.graceDaysLeft = result.graceDaysLeft
        store.shopName = result.shopName
        store.shopPhone = result.shopPhone
        // Wave 3.5 optional fields — sirf tab likhein jab server ne bheje hon.
        result.overdueAmount?.let { store.overdueAmount = it }
        result.overdueDueDate?.let { store.overdueDueDate = it }
        result.overdueCount?.let { store.overdueCount = it }
        result.nextDueDate?.let { store.nextDueDate = it }
        result.monthlyAmount?.let { store.monthlyAmount = it }
        result.offlineUnlockUntil?.let { store.offlineUnlockUntil = it }
        // v2.1 (PU-S9) — "Qist #3 of 6"; 0/-1 aaye to purani value rehne dein.
        result.installmentNumber?.takeIf { it > 0 }?.let { store.installmentNumber = it }
        result.installmentTotal?.takeIf { it > 0 }?.let { store.installmentTotal = it }
        store.lastCheckInAt = System.currentTimeMillis()
        store.lastCheckInAttemptAt = System.currentTimeMillis()
        store.lastCheckInResult = result.instruction
        store.lastCheckInStatus = CheckInStatus.OK

        // A-M3: agar koi "code istemal hua" wali khabar pending hai, to
        // is kamyab check-in ke saath dobara bhejne ki koshish karein.
        retryPendingCodeReport(context)

        when (result.instruction) {
            "lock_now" -> {
                result.offlineUnlockCode?.let { code ->
                    OfflineCodeStore(context).storeCode(code)
                    Log.i(TAG, "Fresh offline unlock code stored (hash only)")
                }
                if (previousState != LockState.LOCKED) {
                    store.lockedSinceAt = System.currentTimeMillis()
                }
                NotificationHelper.cancelWarning(context)
                lockManager.applyLock()
            }
            "warn" -> {
                lockManager.applyWarning()
                if (previousState != LockState.WARNING) {
                    // Pehli dafa warning mein aaye: full-screen warning
                    // dikhane ki koshish + persistent notification (F-19).
                    showWarningUi(context)
                } else {
                    NotificationHelper.showWarning(context)
                }
            }
            "permanent_unlock" -> {
                NotificationHelper.cancelWarning(context)
                store.lockedSinceAt = 0L
                lockManager.applyPermanentUnlock()
            }
            "stay_unlocked" -> {
                OfflineCodeStore(context).clear()
                store.lockedSinceAt = 0L
                NotificationHelper.cancelWarning(context)
                lockManager.applyUnlock()
            }
            else -> {
                // A-M1: na-maaloom instruction — FAIL SAFE. Halat waisi hi,
                // offline code bhi mehfooz. Sirf log + status mein darj karein.
                Log.w(TAG, "Unknown instruction '${result.instruction}' — keeping cached state $previousState (fail-safe, NOT unlocking)")
                store.lastCheckInStatus = CheckInStatus.UNKNOWN_INSTRUCTION
            }
        }
    }

    /** Nakaam check-in ka status darj karein (F-31: UI par nazar aaye). */
    fun recordFailure(context: Context, status: String) {
        val store = LockStateStore(context)
        store.lastCheckInAttemptAt = System.currentTimeMillis()
        store.lastCheckInStatus = status
        Log.w(TAG, "Check-in failed: $status — cached state kept")
    }

    /** Network error kind se check-in status code (sab callers aik hi mapping istemal karein). */
    fun statusFor(kind: ServerApi.ErrorKind): String = when (kind) {
        ServerApi.ErrorKind.NO_INTERNET -> CheckInStatus.NO_INTERNET
        ServerApi.ErrorKind.SERVER_ASLEEP -> CheckInStatus.SERVER_SLOW
        else -> CheckInStatus.SERVER_ERROR
    }

    /** Pending "offline code istemal hua" report dobara bhejein (A-M3). */
    fun retryPendingCodeReport(context: Context) {
        val offlineStore = OfflineCodeStore(context)
        val pending = offlineStore.pendingReportCode() ?: return
        when (val report = ServerApi(context).reportOfflineCodeUsed(pending)) {
            is ServerApi.CodeReportResult.Confirmed -> {
                offlineStore.clearPendingReport()
                Log.i(TAG, "Pending offline-code-used report delivered")
            }
            is ServerApi.CodeReportResult.Rejected -> {
                // PU-N7 / R-N6: server ne MUSTAQIL tor par mana kar diya
                // (masalan code pehle se jal chuka) — dobara bhejne ka
                // faida nahi. Pending report saaf karein taake istemal
                // shuda code ka plaintext prefs mein hamesha na rahe.
                offlineStore.clearPendingReport()
                Log.w(TAG, "Pending offline-code-used report permanently rejected (${report.error}) — cleared, will not retry")
            }
            is ServerApi.CodeReportResult.Transient -> {
                Log.w(TAG, "Pending offline-code-used report still failing — will retry next check-in")
            }
        }
    }

    /**
     * Warning UI: pehle full-screen WarningActivity kholne ki koshish
     * (foreground se chalta hai; background worker se Android rok deta
     * hai — is liye notification hamesha bhi bhejte hain).
     */
    private fun showWarningUi(context: Context) {
        NotificationHelper.showWarning(context)
        runCatching {
            val intent = Intent(context, WarningActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            Log.w(TAG, "Could not launch WarningActivity from background (notification is the fallback)", it)
        }
    }

    /** Check-in status codes (LockStateStore.lastCheckInStatus ke liye). */
    object CheckInStatus {
        const val OK = "ok"
        const val NO_INTERNET = "no_internet"
        const val SERVER_SLOW = "server_slow"
        const val SERVER_ERROR = "server_error"
        const val UNKNOWN_INSTRUCTION = "unknown_instruction"

        fun serverError(error: String) = "$SERVER_ERROR:$error"
    }

    private const val TAG = "PolicyEngine"
}

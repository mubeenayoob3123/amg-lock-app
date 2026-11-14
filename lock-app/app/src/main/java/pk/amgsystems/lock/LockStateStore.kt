package pk.amgsystems.lock

import android.content.Context
import android.content.SharedPreferences

/**
 * All persistent lock state, in one place.
 *
 * Stored here (SharedPreferences "amg_lock"):
 *  - serverUrl, pairingCode, deviceId, accountId (registration)
 *  - lockState: ACTIVE / WARNING / LOCKED / COMPLETED
 *  - dueDate, remainingAmount, graceDaysLeft, shopName, shopPhone
 *  - overdueAmount / overdueDueDate / overdueCount — sirf OVERDUE qist
 *    ka hisaab (Wave 3.5, F-21). -1 / "" ka matlab hai server ne ye
 *    field bheja hi nahi (purana server) — tab UI purane fields
 *    (remainingAmount / dueDate) par wapas chala jata hai.
 *  - nextDueDate / monthlyAmount — agli qist ki tareekh aur raqam
 *  - offlineUnlockUntil — offline code wali 24 ghante ki window (D-M6)
 *  - lockedSinceAt — phone kab se lock hai (F-24)
 *  - lastCheckInAt / lastCheckInResult / lastCheckInStatus /
 *    lastCheckInAttemptAt — check-in ki halat (F-31: khamosh retry
 *    ki bajaye UI par nazar aaye ke aakhri check kaisa raha)
 *
 * NOTE (honest limitation): SharedPreferences is NOT encrypted.
 * For the test phase this is fine. Before real customers, switch
 * this file to EncryptedSharedPreferences (androidx.security) —
 * one-line change, listed as a TODO in README (Wave 5, A-M11).
 */
enum class LockState { ACTIVE, WARNING, LOCKED, COMPLETED }

class LockStateStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Observe state changes (LockActivity uses this to dismiss itself). */
    fun addListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun removeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trimEnd('/')).apply()

    var pairingCode: String
        get() = prefs.getString(KEY_PAIRING_CODE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PAIRING_CODE, value.trim().uppercase()).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var accountId: String
        get() = prefs.getString(KEY_ACCOUNT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACCOUNT_ID, value).apply()

    var isRegistered: Boolean
        get() = prefs.getBoolean(KEY_REGISTERED, false)
        set(value) = prefs.edit().putBoolean(KEY_REGISTERED, value).apply()

    var lockState: LockState
        get() = runCatching { LockState.valueOf(prefs.getString(KEY_LOCK_STATE, "ACTIVE") ?: "ACTIVE") }
            .getOrDefault(LockState.ACTIVE)
        set(value) = prefs.edit().putString(KEY_LOCK_STATE, value.name).apply()

    var dueDate: String
        get() = prefs.getString(KEY_DUE_DATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DUE_DATE, value).apply()

    var remainingAmount: Long
        get() = prefs.getLong(KEY_REMAINING, 0L)
        set(value) = prefs.edit().putLong(KEY_REMAINING, value).apply()

    var graceDaysLeft: Int
        get() = prefs.getInt(KEY_GRACE_LEFT, 0)
        set(value) = prefs.edit().putInt(KEY_GRACE_LEFT, value).apply()

    var shopName: String
        get() = prefs.getString(KEY_SHOP_NAME, "Al Arab Electronic") ?: "Al Arab Electronic"
        set(value) = prefs.edit().putString(KEY_SHOP_NAME, value).apply()

    var shopPhone: String
        get() = prefs.getString(KEY_SHOP_PHONE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SHOP_PHONE, value).apply()

    // --- Wave 3.5 (F-21): per-installment overdue hisaab (optional fields) ---

    /** Sirf overdue qist/qiston ki baqi raqam. -1 = server ne nahi bheja. */
    var overdueAmount: Long
        get() = prefs.getLong(KEY_OVERDUE_AMOUNT, -1L)
        set(value) = prefs.edit().putLong(KEY_OVERDUE_AMOUNT, value).apply()

    /** Sab se pehli overdue qist ki tareekh (yyyy-MM-dd). "" = nahi bheja. */
    var overdueDueDate: String
        get() = prefs.getString(KEY_OVERDUE_DUE_DATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OVERDUE_DUE_DATE, value).apply()

    /** Kitni qistein overdue hain. -1 = server ne nahi bheja. */
    var overdueCount: Int
        get() = prefs.getInt(KEY_OVERDUE_COUNT, -1)
        set(value) = prefs.edit().putInt(KEY_OVERDUE_COUNT, value).apply()

    /** Agli (na ada shuda) qist ki tareekh. "" = nahi bheja. */
    var nextDueDate: String
        get() = prefs.getString(KEY_NEXT_DUE_DATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NEXT_DUE_DATE, value).apply()

    /** Agli qist ki raqam (mahina-waar qist). 0 = nahi bheja. */
    var monthlyAmount: Long
        get() = prefs.getLong(KEY_MONTHLY_AMOUNT, 0L)
        set(value) = prefs.edit().putLong(KEY_MONTHLY_AMOUNT, value).apply()

    /** Offline-code wali unlock window kab khatam hoti hai (ISO). "" = koi window nahi. */
    var offlineUnlockUntil: String
        get() = prefs.getString(KEY_OFFLINE_UNTIL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OFFLINE_UNTIL, value).apply()

    /** Phone kab se lock hai (millis). 0 = lock nahi hai. */
    var lockedSinceAt: Long
        get() = prefs.getLong(KEY_LOCKED_SINCE, 0L)
        set(value) = prefs.edit().putLong(KEY_LOCKED_SINCE, value).apply()

    var lastCheckInAt: Long
        get() = prefs.getLong(KEY_LAST_CHECKIN, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECKIN, value).apply()

    var lastCheckInResult: String
        get() = prefs.getString(KEY_LAST_RESULT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_RESULT, value).apply()

    /**
     * Aakhri check-in kaisa raha: "ok", "no_internet", "server_slow",
     * "server_error", "server_error:<code>", "unknown_instruction".
     * UI is se saaf lafzon mein batati hai (F-31).
     */
    var lastCheckInStatus: String
        get() = prefs.getString(KEY_LAST_STATUS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_STATUS, value).apply()

    /** Aakhri check-in ki koshish kab hui (kamyab ho ya nakaam). */
    var lastCheckInAttemptAt: Long
        get() = prefs.getLong(KEY_LAST_ATTEMPT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_ATTEMPT, value).apply()

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "amg_lock"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val KEY_REGISTERED = "registered"
        /** Key for the lock-state preference (observed by LockActivity). */
        const val KEY_LOCK_STATE = "lock_state"
        private const val KEY_DUE_DATE = "due_date"
        private const val KEY_REMAINING = "remaining_amount"
        private const val KEY_GRACE_LEFT = "grace_days_left"
        private const val KEY_SHOP_NAME = "shop_name"
        private const val KEY_SHOP_PHONE = "shop_phone"
        private const val KEY_OVERDUE_AMOUNT = "overdue_amount"
        private const val KEY_OVERDUE_DUE_DATE = "overdue_due_date"
        private const val KEY_OVERDUE_COUNT = "overdue_count"
        private const val KEY_NEXT_DUE_DATE = "next_due_date"
        private const val KEY_MONTHLY_AMOUNT = "monthly_amount"
        private const val KEY_OFFLINE_UNTIL = "offline_unlock_until"
        private const val KEY_LOCKED_SINCE = "locked_since_at"
        private const val KEY_LAST_CHECKIN = "last_checkin_at"
        private const val KEY_LAST_RESULT = "last_checkin_result"
        private const val KEY_LAST_STATUS = "last_checkin_status"
        private const val KEY_LAST_ATTEMPT = "last_checkin_attempt_at"

        // Free phase ka live test server (Render). Paid domain baad mein
        // aaye ga — tab ye default badal diya jaye ga. Purana default
        // (https://alarab.amgsystems.pk) abhi tak registered domain nahi
        // hai, is liye usay hata diya gaya (F-9 / 3.10).
        const val DEFAULT_SERVER_URL = "https://amg-device-api.onrender.com"

        /** Purana (dead) default — purani install mein ye stored ho to naya default lagayein. */
        const val LEGACY_DEAD_SERVER_URL = "https://alarab.amgsystems.pk"
    }
}

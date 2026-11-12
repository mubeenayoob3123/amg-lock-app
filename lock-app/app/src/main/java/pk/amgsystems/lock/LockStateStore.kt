package pk.amgsystems.lock

import android.content.Context
import android.content.SharedPreferences

/**
 * All persistent lock state, in one place.
 *
 * Stored here (SharedPreferences "amg_lock"):
 *  - serverUrl, pairingCode, deviceId (registration)
 *  - lockState: ACTIVE / WARNING / LOCKED / COMPLETED
 *  - dueDate, remainingAmount, graceDaysLeft, shopName, shopPhone
 *  - lastCheckInAt (millis), lastCheckInResult
 *
 * NOTE (honest limitation): SharedPreferences is NOT encrypted.
 * For the test phase this is fine. Before real customers, switch
 * this file to EncryptedSharedPreferences (androidx.security) —
 * one-line change, listed as a TODO in README.
 */
enum class LockState { ACTIVE, WARNING, LOCKED, COMPLETED }

class LockStateStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trimEnd('/')).apply()

    var pairingCode: String
        get() = prefs.getString(KEY_PAIRING_CODE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PAIRING_CODE, value.trim().uppercase()).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

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

    var lastCheckInAt: Long
        get() = prefs.getLong(KEY_LAST_CHECKIN, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECKIN, value).apply()

    var lastCheckInResult: String
        get() = prefs.getString(KEY_LAST_RESULT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_RESULT, value).apply()

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "amg_lock"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_REGISTERED = "registered"
        private const val KEY_LOCK_STATE = "lock_state"
        private const val KEY_DUE_DATE = "due_date"
        private const val KEY_REMAINING = "remaining_amount"
        private const val KEY_GRACE_LEFT = "grace_days_left"
        private const val KEY_SHOP_NAME = "shop_name"
        private const val KEY_SHOP_PHONE = "shop_phone"
        private const val KEY_LAST_CHECKIN = "last_checkin_at"
        private const val KEY_LAST_RESULT = "last_checkin_result"

        // Paid domain (later). Free phase: replace with the free URL in Setup.
        const val DEFAULT_SERVER_URL = "https://alarab.amgsystems.pk"
    }
}

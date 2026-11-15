package pk.amgsystems.lock

import android.content.Context
import android.content.SharedPreferences

/**
 * All persistent lock state, in one place.
 *
 * Stored here (encrypted SharedPreferences "amg_lock_enc" — PU-M7 /
 * A-M11, Wave 5.8; pehle plain "amg_lock" thi, pehli launch par
 * khud bakhud migrate ho jati hai, dekhein SecurePrefs):
 *  - serverUrl, pairingCode, deviceId, accountId (registration)
 *  - resetPin — shop ka Reset PIN (server register jawab mein deta
 *    hai, v2.1; reset sirf is PIN se hota hai — PU-M8 / R-S13)
 *  - lockState: ACTIVE / WARNING / LOCKED / COMPLETED
 *  - dueDate, remainingAmount, graceDaysLeft, shopName, shopPhone
 *  - overdueAmount / overdueDueDate / overdueCount — sirf OVERDUE qist
 *    ka hisaab (Wave 3.5, F-21). -1 / "" ka matlab hai server ne ye
 *    field bheja hi nahi (purana server) — tab UI purane fields
 *    (remainingAmount / dueDate) par wapas chala jata hai.
 *  - nextDueDate / monthlyAmount — agli qist ki tareekh aur raqam
 *  - installmentNumber / installmentTotal — overdue qist ka number,
 *    masalan "Qist #3 of 6" (v2.1; -1 = server ne nahi bheja — PU-S9)
 *  - fcmToken — Firebase push token (Wave 5.4); server ko register /
 *    check-in par bheja jata hai taake payment par push aa sake.
 *    "" = FCM band hai ya token abhi nahi mila.
 *  - offlineUnlockUntil — offline code wali 24 ghante ki window (D-M6)
 *  - lockedSinceAt — phone kab se lock hai (F-24)
 *  - lastCheckInAt / lastCheckInResult / lastCheckInStatus /
 *    lastCheckInAttemptAt — check-in ki halat (F-31: khamosh retry
 *    ki bajaye UI par nazar aaye ke aakhri check kaisa raha)
 *  - v1.4.0 (audit round 2): dashboardAccountId (J1-m55), scheduleJson
 *    + graceDays + localLock (J2-M14 offline deadline lock),
 *    resetPinIssued / resetPinAttempts / resetPinLockedUntil (J2-M15),
 *    serverUrlLocked (J2-M15c), consecutiveNotRegistered (J2-m34),
 *    completionPending (J2-m35), fcmTokenFetchAttempts /
 *    fcmTokenNextRetryAt (J1-m58).
 */
enum class LockState { ACTIVE, WARNING, LOCKED, COMPLETED }

class LockStateStore(context: Context) {

    private val prefs: SharedPreferences =
        SecurePrefs.create(context, PREFS_NAME, PREFS_NAME_ENCRYPTED)

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

    /**
     * Dashboard ka account id (v1.4.0 / server unified-4 — J1-m55).
     * Register jawab mein `dashboardAccountId` aaye to ye set hota hai;
     * na aaye to khaali rehta hai aur dikhane ke liye [accountId]
     * (server ka andaruni id) istemal hota hai — dekhein [displayAccountId].
     */
    var dashboardAccountId: String
        get() = prefs.getString(KEY_DASHBOARD_ACCOUNT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DASHBOARD_ACCOUNT_ID, value).apply()

    /** Dikhane wala account id — dashboard wala ho to wahi, warna server wala (J1-m55). */
    val displayAccountId: String
        get() = dashboardAccountId.ifBlank { accountId }

    /**
     * Server ne kabhi Reset PIN bheja hai? (J2-M15a) — PIN jari ho
     * chuka ho lekin ab stored na ho (storage ka masla) to reset
     * phone se NAHI ho sakta (fail open nahi). Sirf wo phone jin
     * ko PIN kabhi mila hi nahi (purani registration) bare-confirm
     * path istemal karte hain, dobara register tak.
     */
    var resetPinIssued: Boolean
        get() = prefs.getBoolean(KEY_RESET_PIN_ISSUED, false)
        set(value) = prefs.edit().putBoolean(KEY_RESET_PIN_ISSUED, value).apply()

    /** Reset PIN ki ghalat koshishen (J2-M15b) — 5 par 15 minute ki rok lagti hai. */
    var resetPinAttempts: Int
        get() = prefs.getInt(KEY_RESET_PIN_ATTEMPTS, 0)
        set(value) = prefs.edit().putInt(KEY_RESET_PIN_ATTEMPTS, value).apply()

    /** Reset PIN kab tak band hai (millis, J2-M15b). 0 = koi rok nahi. */
    var resetPinLockedUntil: Long
        get() = prefs.getLong(KEY_RESET_PIN_LOCKED_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_RESET_PIN_LOCKED_UNTIL, value).apply()

    /**
     * Server URL lock hai? (J2-M15c) — register ke baad true ho jata
     * hai. Reset ke baad bhi URL wahi rehta hai (customer phone ko
     * nakli server ki taraf nahi mod sakta); URL sirf dobara QR
     * provisioning se badalta hai.
     */
    var serverUrlLocked: Boolean
        get() = prefs.getBoolean(KEY_SERVER_URL_LOCKED, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVER_URL_LOCKED, value).apply()

    // --- v1.4.0 (J2-M14): aakhri sync wala schedule — offline deadline lock ke liye ---

    /**
     * Aakhri kamyab check-in par server se mila schedule — raw JSON
     * array, masalan [{"seq":1,"dueDate":"2026-11-10","amount":10000,
     * "paidAmount":0}]. "" = server ne schedule bheja hi nahi (purana
     * server) — tab purane fields (overdueDueDate / nextDueDate)
     * fallback hain. LocalDeadlineLock isi se pehli na ada shuda
     * qist ki tareekh nikalta hai.
     */
    var scheduleJson: String
        get() = prefs.getString(KEY_SCHEDULE_JSON, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SCHEDULE_JSON, value).apply()

    /**
     * Account ki grace (din mein, server se — masalan 5). -1 = server
     * ne nahi bheja (purana server) — tab aakhri sync ke waqt ke
     * graceDaysLeft se deadline nikaali jati hai.
     */
    var graceDays: Int
        get() = prefs.getInt(KEY_GRACE_DAYS, -1)
        set(value) = prefs.edit().putInt(KEY_GRACE_DAYS, value).apply()

    /**
     * Kya maujooda lock phone ne KHUD offline lagaya hai (local
     * deadline lock, J2-M14)? True ho to lock screen offline wala
     * paigham dikhati hai ("internet on karein ya dukaan se rabta
     * karein"). Agli kamyab check-in (stay_unlocked / warn) ya
     * server ke lock_now par ye flag saaf ho jata hai.
     */
    var localLock: Boolean
        get() = prefs.getBoolean(KEY_LOCAL_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCAL_LOCK, value).apply()

    /** Lagatar `device_not_registered` check-in nakaamiyan (J2-m34) — 3 par dobara register ka prompt. */
    var consecutiveNotRegistered: Int
        get() = prefs.getInt(KEY_CONSEC_NOT_REGISTERED, 0)
        set(value) = prefs.edit().putInt(KEY_CONSEC_NOT_REGISTERED, value).apply()

    /**
     * Completion adhoori hai (J2-m35): aakhri qist ada ho gayi lekin
     * Device Owner hatne ki tasdeeq nahi hui — state COMPLETED NAHI
     * hui, agli check-in dobara koshish kare gi.
     */
    var completionPending: Boolean
        get() = prefs.getBoolean(KEY_COMPLETION_PENDING, false)
        set(value) = prefs.edit().putBoolean(KEY_COMPLETION_PENDING, value).apply()

    /** FCM token fetch ki nakaam koshishen (J1-m58) — backoff ke liye. */
    var fcmTokenFetchAttempts: Int
        get() = prefs.getInt(KEY_FCM_FETCH_ATTEMPTS, 0)
        set(value) = prefs.edit().putInt(KEY_FCM_FETCH_ATTEMPTS, value).apply()

    /** FCM token dobara kab lena hai (millis, J1-m58). 0 = foran koshish ho sakti hai. */
    var fcmTokenNextRetryAt: Long
        get() = prefs.getLong(KEY_FCM_NEXT_RETRY_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_FCM_NEXT_RETRY_AT, value).apply()

    /**
     * Shop ka Reset PIN (v2.1 — server register jawab mein deta hai).
     * "" = server ne PIN nahi diya (purana server / purani registration)
     * — tab reset pehle jaisi tasdeeq se hota hai, jab tak dobara
     * register par PIN na mil jaye (PU-M8 / R-S13).
     */
    var resetPin: String
        get() = prefs.getString(KEY_RESET_PIN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RESET_PIN, value.trim()).apply()

    /**
     * FCM push token (Wave 5.4 — FcmManager set karta hai). Server ko
     * register / check-in par bheja jata hai; server unlock ya final
     * payment par isi token par push bhejta hai. "" = FCM band hai ya
     * token abhi nahi mila (tab 15 minute wala check-in hi chalta hai).
     */
    var fcmToken: String
        get() = prefs.getString(KEY_FCM_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FCM_TOKEN, value.trim()).apply()

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

    // --- v2.1 (PU-S9): overdue qist ka number — "Qist #3 of 6" ---

    /** Overdue qist ka number (masalan 3). -1 = server ne nahi bheja. */
    var installmentNumber: Int
        get() = prefs.getInt(KEY_INSTALLMENT_NUMBER, -1)
        set(value) = prefs.edit().putInt(KEY_INSTALLMENT_NUMBER, value).apply()

    /** Kul qiston ki tadaad (masalan 6). -1 = server ne nahi bheja. */
    var installmentTotal: Int
        get() = prefs.getInt(KEY_INSTALLMENT_TOTAL, -1)
        set(value) = prefs.edit().putInt(KEY_INSTALLMENT_TOTAL, value).apply()

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
        /** Encrypted file — SecurePrefs purani plain file se yahan migrate karta hai (PU-M7). */
        private const val PREFS_NAME_ENCRYPTED = "amg_lock_enc"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val KEY_DASHBOARD_ACCOUNT_ID = "dashboard_account_id"
        private const val KEY_RESET_PIN = "reset_pin"
        private const val KEY_RESET_PIN_ISSUED = "reset_pin_issued"
        private const val KEY_RESET_PIN_ATTEMPTS = "reset_pin_attempts"
        private const val KEY_RESET_PIN_LOCKED_UNTIL = "reset_pin_locked_until"
        private const val KEY_SERVER_URL_LOCKED = "server_url_locked"
        private const val KEY_SCHEDULE_JSON = "schedule_json"
        private const val KEY_GRACE_DAYS = "grace_days"
        private const val KEY_LOCAL_LOCK = "local_deadline_lock"
        private const val KEY_CONSEC_NOT_REGISTERED = "consec_not_registered"
        private const val KEY_COMPLETION_PENDING = "completion_pending"
        private const val KEY_FCM_FETCH_ATTEMPTS = "fcm_fetch_attempts"
        private const val KEY_FCM_NEXT_RETRY_AT = "fcm_next_retry_at"
        private const val KEY_FCM_TOKEN = "fcm_token"
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
        private const val KEY_INSTALLMENT_NUMBER = "installment_number"
        private const val KEY_INSTALLMENT_TOTAL = "installment_total"
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

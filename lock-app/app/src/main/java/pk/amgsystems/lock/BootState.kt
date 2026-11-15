package pk.amgsystems.lock

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Minimal boot state — device-protected storage mein (J2-p18).
 *
 * Masla: `LOCKED_BOOT_COMPLETED` par phone abhi unlock nahi hua hota,
 * is liye encrypted prefs (credential-encrypted storage) band hoti
 * hain — BootReceiver lock ki halat parh hi nahi sakta tha, aur
 * receiver `directBootAware` bhi nahi tha (is liye wo broadcast
 * milta hi nahi tha). Is ka hal: aik chhota sa device-protected
 * store (locked boot par bhi available) jis mein SIRF do cheezein
 * hoti hain — registered hai ya nahi, aur lock state kya hai.
 *
 * KOI SECRET NAHI: is file mein pairing code, reset PIN, server URL,
 * deviceId ya offline-code hash KABHI nahi likha jata (J2-M17).
 * Sirf "registered" (haan/naa) aur "lock_state" (ACTIVE/WARNING/
 * LOCKED/COMPLETED) — ye dekh kar koi phone unlock nahi kar sakta.
 *
 * [sync] har state change par call hota hai (LockManager ke lock /
 * unlock / completion methods + register + reset se). Storage band
 * ho (SecurePrefs.degraded) to sync kuch nahi karta — dead store ke
 * defaults (unregistered/ACTIVE) sahi boot state mita den ge.
 */
object BootState {

    private const val TAG = "BootState"
    private const val PREFS_NAME = "amg_lock_boot"
    private const val KEY_REGISTERED = "registered"
    private const val KEY_LOCK_STATE = "lock_state"

    private fun prefs(context: Context): SharedPreferences {
        val deviceProtected = context.createDeviceProtectedStorageContext()
        return deviceProtected.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Encrypted store se minimal state copy karein (har state change par). */
    fun sync(context: Context) {
        if (SecurePrefs.degraded) {
            Log.w(TAG, "Storage degraded — boot state NOT synced (dead-store defaults would erase the real state)")
            return
        }
        runCatching {
            val store = LockStateStore(context)
            // commit() — boot state pakka likhna zaroori hai (crash / reboot se pehle bhi).
            prefs(context).edit()
                .putBoolean(KEY_REGISTERED, store.isRegistered)
                .putString(KEY_LOCK_STATE, store.lockState.name)
                .commit()
        }.onFailure { Log.w(TAG, "Boot state sync failed", it) }
    }

    /** Locked boot par: kya phone registered hai AUR lock state LOCKED hai? */
    fun isLocked(context: Context): Boolean {
        return runCatching {
            val p = prefs(context)
            p.getBoolean(KEY_REGISTERED, false) &&
                p.getString(KEY_LOCK_STATE, LockState.ACTIVE.name) == LockState.LOCKED.name
        }.getOrDefault(false)
    }

    /** Locked boot par: kya phone registered hai? */
    fun isRegistered(context: Context): Boolean {
        return runCatching {
            prefs(context).getBoolean(KEY_REGISTERED, false)
        }.getOrDefault(false)
    }
}

package pk.amgsystems.lock

import android.content.Context
import java.security.MessageDigest

/**
 * One-time offline unlock code.
 *
 * Flow (matches the web dashboard server flow):
 *  1. When the server tells the app to LOCK, it also sends a fresh
 *     one-time offline code (plaintext, once). The dashboard shows the
 *     SAME code to the shop, so the shop can give it to the customer.
 *  2. The app NEVER stores the plaintext code. It stores only its
 *     SHA-256 hash (storeCode).
 *  3. Offline, the customer types the code on the lock screen.
 *     verifyAndBurn hashes what they typed and compares it.
 *     If it matches, the code is BURNED (deleted) immediately and the
 *     phone unlocks. The same code can never work twice.
 *  4. If the phone is online, the app ALSO tells the server the code
 *     was used, so the dashboard marks it used too.
 *
 * If the hash is empty (no code was ever issued) every attempt fails.
 */
class OfflineCodeStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Store only the hash of a newly issued code. Overwrites any old code. */
    fun storeCode(plainCode: String) {
        if (plainCode.isBlank()) return
        prefs.edit().putString(KEY_HASH, sha256(plainCode.trim().uppercase())).apply()
    }

    fun hasCode(): Boolean = prefs.getString(KEY_HASH, null) != null

    /**
     * Returns true only if the typed code matches the stored hash.
     * On success the code is burned (deleted) — one-time use.
     */
    fun verifyAndBurn(typedCode: String): Boolean {
        val stored = prefs.getString(KEY_HASH, null) ?: return false
        if (typedCode.isBlank()) return false
        val typedHash = sha256(typedCode.trim().uppercase())
        if (typedHash != stored) return false
        // Burn it: this code can never be used again.
        prefs.edit().remove(KEY_HASH).apply()
        return true
    }

    fun clear() {
        prefs.edit().remove(KEY_HASH).apply()
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PREFS_NAME = "amg_lock_offline"
        private const val KEY_HASH = "offline_code_hash"
    }
}

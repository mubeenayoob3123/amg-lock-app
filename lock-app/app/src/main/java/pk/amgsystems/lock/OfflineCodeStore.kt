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
 *
 * Wave 3.8 (A-M3) — offline reuse rokna:
 *  - Har istemal shuda code ka HASH alag se mehfooz rehta hai
 *    (usedHashes). Agar server dobara wohi code bhej de (is liye ke
 *    use ki khabar server tak nahi pohnchi), to storeCode usay
 *    dobara store nahi karta — code dobara nahi chal sakta.
 *  - Agar code offline istemal hua, to us ki khabar (pending report)
 *    mehfooz rehti hai aur agli kamyab check-in par server ko dobara
 *    bheji jati hai, jab tak server tasdeeq na kar de. Is ke liye
 *    code ka plaintext thori der ke liye rakhna parta hai (server ko
 *    code chahiye hota hai) — ye sirf tab tak rehta hai jab tak
 *    report kamyab na ho jaye, aur ye aik pehle se istemal shuda
 *    (jal chuka) code hota hai.
 */
class OfflineCodeStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Store only the hash of a newly issued code. Overwrites any old
     * code. REFUSES a code whose hash is already in the used set —
     * a used code must never come back to life (A-M3).
     */
    fun storeCode(plainCode: String) {
        if (plainCode.isBlank()) return
        val hash = sha256(plainCode.trim().uppercase())
        if (usedHashes().contains(hash)) return // pehle istemal ho chuka — dobara store nahi
        prefs.edit().putString(KEY_HASH, hash).apply()
    }

    fun hasCode(): Boolean = prefs.getString(KEY_HASH, null) != null

    /** Kya ye code pehle kabhi istemal ho chuka hai (dobara istemal ki koshish)? */
    fun wasAlreadyUsed(typedCode: String): Boolean {
        if (typedCode.isBlank()) return false
        return usedHashes().contains(sha256(typedCode.trim().uppercase()))
    }

    /**
     * Returns true only if the typed code matches the stored hash.
     * On success the code is burned (deleted) — one-time use — and
     * its hash is added to the used set so it can never be stored
     * again, even if the server re-issues the same code.
     */
    fun verifyAndBurn(typedCode: String): Boolean {
        val stored = prefs.getString(KEY_HASH, null) ?: return false
        if (typedCode.isBlank()) return false
        val typedHash = sha256(typedCode.trim().uppercase())
        if (typedHash != stored) return false
        // Burn it: this code can never be used again.
        val used = usedHashes().toMutableSet()
        used.add(typedHash)
        prefs.edit()
            .remove(KEY_HASH)
            .putStringSet(KEY_USED_HASHES, used)
            .apply()
        return true
    }

    /** Current (unused) code bhool jayein — used-code record rehta hai. */
    fun clear() {
        prefs.edit().remove(KEY_HASH).apply()
    }

    /** Sab kuch saaf — sirf permanent unlock / reset par. */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun usedHashes(): Set<String> =
        prefs.getStringSet(KEY_USED_HASHES, emptySet()) ?: emptySet()

    // --- Pending "code used" report (A-M3 retry) ---

    /** Report ke liye code rakh dein (jab offline istemal hua ho). */
    fun setPendingReport(code: String) {
        if (code.isBlank()) return
        prefs.edit().putString(KEY_PENDING_REPORT, code.trim().uppercase()).apply()
    }

    fun pendingReportCode(): String? = prefs.getString(KEY_PENDING_REPORT, null)

    fun clearPendingReport() {
        prefs.edit().remove(KEY_PENDING_REPORT).apply()
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PREFS_NAME = "amg_lock_offline"
        private const val KEY_HASH = "offline_code_hash"
        private const val KEY_USED_HASHES = "offline_code_used_hashes"
        private const val KEY_PENDING_REPORT = "offline_code_pending_report"
    }
}

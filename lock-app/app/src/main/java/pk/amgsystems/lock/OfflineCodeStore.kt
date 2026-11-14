package pk.amgsystems.lock

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * One-time offline unlock code.
 *
 * Flow (matches the web dashboard server flow):
 *  1. When the server tells the app to LOCK, it also sends a fresh
 *     one-time offline code (plaintext, once). The dashboard shows the
 *     SAME code to the shop, so the shop can give it to the customer.
 *  2. The app NEVER stores the plaintext code. It stores only its
 *     salted SHA-256 hash (storeCode) — hash = SHA-256(salt + code),
 *     salt har phone par alag random hota hai aur encrypted store
 *     mein rehta hai (PU-M8 / A-n8).
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
 *    bheji jati hai, jab tak server tasdeeq na kar de — ya mustaqil
 *    tor par mana na kar de (PU-N7: mustaqil mana par report saaf ho
 *    jati hai, hamesha retry nahi hoti). Is ke liye code ka plaintext
 *    thori der ke liye rakhna parta hai (server ko code chahiye hota
 *    hai) — ye sirf tab tak rehta hai jab tak report kamyab na ho
 *    jaye, aur ye aik pehle se istemal shuda (jal chuka) code hota
 *    hai. Ab ye bhi encrypted store mein rehta hai (PU-M7).
 *
 * Salt migration: is update se pehle hash baghair salt ke banta tha.
 * Purani install mein pehle se stored (unsalted) hash ab bhi aik
 * dafa chalta hai — verifyAndBurn salted ke saath purana unsalted
 * hash bhi check karta hai — taake pehle se jari code update ke
 * baad bekaar na ho jaye. Naye code hamesha salted hash se store
 * hote hain.
 */
class OfflineCodeStore(context: Context) {

    private val prefs = SecurePrefs.create(context, PREFS_NAME, PREFS_NAME_ENCRYPTED)

    /**
     * Store only the (salted) hash of a newly issued code. Overwrites
     * any old code. REFUSES a code whose hash is already in the used
     * set — a used code must never come back to life (A-M3).
     */
    fun storeCode(plainCode: String) {
        if (plainCode.isBlank()) return
        val code = plainCode.trim().uppercase()
        val used = usedHashes()
        if (used.contains(saltedHash(code)) || used.contains(legacyHash(code))) return // pehle istemal ho chuka — dobara store nahi
        prefs.edit().putString(KEY_HASH, saltedHash(code)).apply()
    }

    fun hasCode(): Boolean = prefs.getString(KEY_HASH, null) != null

    /** Kya ye code pehle kabhi istemal ho chuka hai (dobara istemal ki koshish)? */
    fun wasAlreadyUsed(typedCode: String): Boolean {
        if (typedCode.isBlank()) return false
        val code = typedCode.trim().uppercase()
        val used = usedHashes()
        return used.contains(saltedHash(code)) || used.contains(legacyHash(code))
    }

    /**
     * Returns true only if the typed code matches the stored hash.
     * On success the code is burned (deleted) — one-time use — and
     * its hash is added to the used set so it can never be stored
     * again, even if the server re-issues the same code.
     *
     * Purana (unsalted) hash bhi check hota hai — sirf is liye ke
     * update se pehle jari code aik dafa chal sake (salt migration).
     */
    fun verifyAndBurn(typedCode: String): Boolean {
        val stored = prefs.getString(KEY_HASH, null) ?: return false
        if (typedCode.isBlank()) return false
        val code = typedCode.trim().uppercase()
        val salted = saltedHash(code)
        val legacy = legacyHash(code)
        if (salted != stored && legacy != stored) return false
        // Burn it: this code can never be used again.
        val used = usedHashes().toMutableSet()
        used.add(salted)
        used.add(legacy)
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

    // --- Salted hash (PU-M8 / A-n8) ---

    /**
     * Per-device random salt (32 byte, hex). Pehli dafa zaroorat par
     * banta hai aur encrypted store mein rehta hai. Reset /
     * permanent unlock par clearAll ke saath mit jata hai — us ke
     * baad naya salt banta hai (purane hash us waqt tak bekaar hain,
     * kyunke used record bhi saaf ho chuka hota hai).
     */
    private fun salt(): String {
        val existing = prefs.getString(KEY_SALT, null)
        if (!existing.isNullOrBlank()) return existing
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val hex = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_SALT, hex).apply()
        return hex
    }

    /** Naya hash: SHA-256(salt + code). */
    private fun saltedHash(code: String): String = sha256(salt() + code)

    /** Purana hash (salt se pehle): SHA-256(code) — sirf migration ke liye. */
    private fun legacyHash(code: String): String = sha256(code)

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PREFS_NAME = "amg_lock_offline"
        /** Encrypted file — SecurePrefs purani plain file se yahan migrate karta hai (PU-M7). */
        private const val PREFS_NAME_ENCRYPTED = "amg_lock_offline_enc"
        private const val KEY_HASH = "offline_code_hash"
        private const val KEY_USED_HASHES = "offline_code_used_hashes"
        private const val KEY_PENDING_REPORT = "offline_code_pending_report"
        private const val KEY_SALT = "offline_code_salt"
    }
}

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
        if (allHashes(code).any { it in used }) return // pehle istemal ho chuka — dobara store nahi
        prefs.edit().putString(KEY_HASH, saltedHash(code)).apply()
    }

    fun hasCode(): Boolean = prefs.getString(KEY_HASH, null) != null

    /** Kya ye code pehle kabhi istemal ho chuka hai (dobara istemal ki koshish)? */
    fun wasAlreadyUsed(typedCode: String): Boolean {
        if (typedCode.isBlank()) return false
        val code = typedCode.trim().uppercase()
        val used = usedHashes()
        return allHashes(code).any { it in used }
    }

    /**
     * Returns true only if the typed code matches the stored hash.
     * On success the code is burned (deleted) — one-time use — and
     * its hash is added to the used set so it can never be stored
     * again, even if the server re-issues the same code.
     *
     * Purana (unsalted) hash bhi check hota hai — sirf is liye ke
     * update se pehle jari code aik dafa chal sake (salt migration).
     * Purana (sign-extended) hex bhi check hota hai — v1.3.0 tak hash
     * usi ghalat hex mein likha jata tha (J2-p14 migration).
     *
     * Burn SYNCHRONOUS hai (J2-p15, commit): apply() ke saath app
     * crash ho to jala hua code dobara zinda ho sakta tha.
     */
    fun verifyAndBurn(typedCode: String): Boolean {
        val stored = prefs.getString(KEY_HASH, null) ?: return false
        if (typedCode.isBlank()) return false
        val code = typedCode.trim().uppercase()
        if (allHashes(code).none { it == stored }) return false
        // Burn it: this code can never be used again.
        val used = usedHashes().toMutableSet()
        used.addAll(allHashes(code))
        val committed = prefs.edit()
            .remove(KEY_HASH)
            .putStringSet(KEY_USED_HASHES, used)
            .commit()
        if (!committed) {
            android.util.Log.e(TAG, "Code burn commit FAILED — code may still be usable, treating as NOT burned")
            return false
        }
        return true
    }

    // --- Offline-code koshishon ki rok (J2-m32) ---

    /** Ghalat code ki lagatar koshishen — 5 par 15 minute ki rok. */
    private var codeAttempts: Int
        get() = prefs.getInt(KEY_CODE_ATTEMPTS, 0)
        set(value) = prefs.edit().putInt(KEY_CODE_ATTEMPTS, value).apply()

    /** Code entry kab tak band hai (millis). 0 = koi rok nahi. */
    private var codeLockedUntil: Long
        get() = prefs.getLong(KEY_CODE_LOCKED_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_CODE_LOCKED_UNTIL, value).apply()

    /** Kya code likhna abhi band hai (5 ghalat koshishon ke baad)? Restart ke baad bhi band rehta hai. */
    fun isCodeLockedOut(): Boolean = System.currentTimeMillis() < codeLockedUntil

    /** Rok khatam hone mein kitne minute baqi hain (kam se kam 1). */
    fun codeLockoutMinutesLeft(): Long {
        val left = codeLockedUntil - System.currentTimeMillis()
        return if (left <= 0L) 0L else (left + 59_999L) / 60_000L
    }

    /**
     * Ghalat code ki koshish darj karein. Returns true agar is
     * koshish se 15 minute ki rok lag gayi ho.
     */
    fun noteWrongCodeAttempt(): Boolean {
        val attempts = codeAttempts + 1
        if (attempts >= MAX_CODE_ATTEMPTS) {
            codeAttempts = 0
            codeLockedUntil = System.currentTimeMillis() + CODE_LOCKOUT_MS
            return true
        }
        codeAttempts = attempts
        return false
    }

    /** Sahi code ke baad koshishon ka hisaab saaf karein. */
    fun resetCodeAttempts() {
        codeAttempts = 0
        codeLockedUntil = 0L
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
        prefs.edit().remove(KEY_PENDING_REPORT).remove(KEY_PENDING_RETRY_AFTER).apply()
    }

    /**
     * Pending report dobara kab bhejni hai (millis). 0 = koi backoff
     * nahi. Server 429 (too_many_attempts) ke baad 1 ghante ke liye
     * block karta hai — us doran har 15 minute ki check-in par
     * dobara bhejna bekaar hai (v2.1 / PU-S10).
     */
    var pendingReportRetryAfterAt: Long
        get() = prefs.getLong(KEY_PENDING_RETRY_AFTER, 0L)
        set(value) = prefs.edit().putLong(KEY_PENDING_RETRY_AFTER, value).apply()

    /** 429 ke baad backoff lagayein — 1 ghanta (server ke block jitna). */
    fun noteReportRateLimited() {
        pendingReportRetryAfterAt = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS
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
        val hex = toHex(bytes)
        prefs.edit().putString(KEY_SALT, hex).apply()
        return hex
    }

    /** Naya hash: SHA-256(salt + code). */
    private fun saltedHash(code: String): String = sha256(salt() + code)

    /** Purana hash (salt se pehle): SHA-256(code) — sirf migration ke liye. */
    private fun legacyHash(code: String): String = sha256(code)

    /**
     * Is code ke tamam mumkin hash forms — naya hex + purana
     * (sign-extended) hex, salted + unsalted. Sirf check karne ke
     * liye hai, taake v1.3.0 tak jari code update ke baad bhi aik
     * dafa chale (J2-p14 migration). Naye code hamesha salted +
     * sahi hex se store hote hain.
     */
    private fun allHashes(code: String): List<String> {
        val salt = salt()
        return listOf(
            sha256(salt + code),
            sha256Legacy(salt + code),
            sha256(code),
            sha256Legacy(code)
        )
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return toHex(bytes)
    }

    /** Purana hex (v1.3.0 tak) — sirf pehle se stored hash check karne ke liye. */
    private fun sha256Legacy(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return toHexLegacy(bytes)
    }

    /**
     * Sahi hex (J2-p14): byte ko pehle unsigned int banayein, warna
     * "%02x" negative byte ko sign-extend kar deta hai (0xFF ->
     * "ffffffff") aur hash ghair-mayari lamba ho jata hai.
     */
    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** Purana (ghalat) hex — sirf migration check ke liye, naya code kabhi is se nahi banta. */
    private fun toHexLegacy(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "OfflineCodeStore"
        private const val PREFS_NAME = "amg_lock_offline"
        /** Encrypted file — SecurePrefs purani plain file se yahan migrate karta hai (PU-M7). */
        private const val PREFS_NAME_ENCRYPTED = "amg_lock_offline_enc"
        private const val KEY_HASH = "offline_code_hash"
        private const val KEY_USED_HASHES = "offline_code_used_hashes"
        private const val KEY_PENDING_REPORT = "offline_code_pending_report"
        private const val KEY_PENDING_RETRY_AFTER = "offline_code_pending_retry_after"
        private const val KEY_SALT = "offline_code_salt"
        private const val KEY_CODE_ATTEMPTS = "offline_code_attempts"
        private const val KEY_CODE_LOCKED_UNTIL = "offline_code_locked_until"
        /** 429 (too_many_attempts) ke baad retry se pehle ka waqfa — server 1 ghante ke liye block karta hai. */
        private const val RATE_LIMIT_BACKOFF_MS = 60L * 60L * 1000L
        /** Ghalat code ki kitni koshishon ke baad rok lage (J2-m32). */
        private const val MAX_CODE_ATTEMPTS = 5
        /** Rok ki muddat — 15 minute (J2-m32). */
        private const val CODE_LOCKOUT_MS = 15L * 60L * 1000L
    }
}

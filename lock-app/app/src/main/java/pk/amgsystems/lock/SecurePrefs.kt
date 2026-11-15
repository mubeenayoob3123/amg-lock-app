package pk.amgsystems.lock

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import java.security.UnrecoverableKeyException

/**
 * Encrypted SharedPreferences (PU-M7 / A-M11 / Wave 5.8, fail-closed J2-M17).
 *
 * Dono stores (LockStateStore "amg_lock" aur OfflineCodeStore
 * "amg_lock_offline") encrypted files mein rehte hain — pairing code,
 * deviceId, server URL, reset PIN, offline-code hash aur salt plain
 * text mein phone par nahi milte. Koi phone ya backup parh kar ye
 * cheezein nahi nikaal sakta.
 *
 * Migration (pehli launch par, khud bakhud):
 *  - Purani plain file mein jo kuch hai, wo encrypted file mein
 *    copy ho jata hai, phir plain file khaali kar di jati hai.
 *    Pehle se registered phone baghair dobara register kiye chalte
 *    rehte hain — koi data nahi khota.
 *  - Copy pehle encrypted mein pakka likha jata hai (commit), phir
 *    plain saaf hoti hai — beech mein app band bhi ho jaye to data
 *    do mein se aik jagah zaroor rehta hai.
 *  - "Encrypted hamesha jeetta hai": sirf wo keys copy hoti hain jo
 *    encrypted mein pehle se na hon. Ye sirf purani (v1.2.0 se pehle
 *    ki) install ki aik-dafa migration ke liye hai — is update ke
 *    baad plain file mein kabhi naya data nahi likha jata, is liye
 *    "plain mein naya, encrypted mein purana" wali halat dobara
 *    nahi ban sakti.
 *
 * FAIL-CLOSED (J2-M17 — v1.4.0 se):
 *  - Secrets KABHI plain prefs mein nahi jate. Encrypted prefs na
 *    banein to app plain file par wapas NAHI jati (pehle jati thi —
 *    wo bug tha): teen dafa retry hota hai (chhota backoff), phir
 *    sirf memory mein rehne wala store milta hai (InMemoryPrefs) —
 *    jo kuch bhi likha jaye wo phone ki storage par kabhi nahi
 *    utarta, aur app band hote hi khatam ho jata hai. Encrypted
 *    file ko haath nahi lagaya jata, is liye agli launch par
 *    (masalan restart ke baad) registration wapas aa sakti hai.
 *  - Keystore ki AARZI ghalti (busy / locked / direct boot) par
 *    master key ya encrypted file KABHI delete nahi hoti — aarzi
 *    ghalti par delete karna registration hamesha ke liye mita
 *    deta tha. Key sirf tab delete hoti hai jab kharabi SAA BIT ho:
 *    UnrecoverableKeyException do alag launches mein aaye (pehli
 *    dafa sirf marker lagta hai, doosri dafa delete + dobara
 *    koshish), aur phone unlocked ho (direct boot mein kabhi nahi).
 *  - Jab fallback store istemal ho raha ho to [degraded] true hota
 *    hai — MainActivity is par saaf banner dikhati hai ("phone
 *    restart karein"), taake ye halat khamosh na rahe.
 *
 * Ghair-secret flags (sirf "key kharab lag rahi hai" jaisa marker)
 * aik alag PLAIN file (amg_lock_meta) mein rehte hain — is mein
 * koi secret, code ya PIN kabhi nahi likha jata.
 */
object SecurePrefs {

    private const val TAG = "SecurePrefs"

    /** Migration ho chuki hai ya nahi — encrypted file mein marker. */
    private const val KEY_MIGRATED = "amg_enc_migrated"

    /** Ghair-secret flags ki plain file — is mein KOI secret nahi hota. */
    private const val META_PREFS_NAME = "amg_lock_meta"
    private const val KEY_CORRUPT_SUSPECT_PREFIX = "corrupt_suspect_"

    /** Encrypted prefs banane ki koshishen (pehli + retry). */
    private const val CREATE_ATTEMPTS = 3

    /** Retry ke beech ka waqfa (millis) — pehli retry 150ms, doosri 300ms. */
    private const val RETRY_DELAY_BASE_MS = 150L

    /**
     * Kya is launch mein encrypted storage nahi khul saki (fallback
     * store chal raha hai)? True ho to UI banner dikhaye — aur koi
     * bhi code is halat mein registration "gum" samajh kar dobara
     * register ya reset ki koshish na kare (data mehfooz hai, sirf
     * is launch mein nazar nahi aa raha).
     */
    @Volatile
    var degraded: Boolean = false
        private set

    /** Aakhri create ghalti (log / debug ke liye) — UI par kabhi nahi dikhani. */
    @Volatile
    var lastError: String? = null
        private set

    /** Fallback (in-memory) stores — aik naam ka aik hi instance, taake sab callers aik hi halat dekhein. */
    private val fallbackStores = mutableMapOf<String, SharedPreferences>()

    /**
     * Encrypted prefs banayein. Na ban sakein to retry + in-memory
     * fallback — plain file par KABHI wapas nahi jate (J2-M17).
     * [plainName] purani plain file ka naam hai (migration ke liye),
     * [encryptedName] nayi encrypted file ka naam hai.
     */
    fun create(context: Context, plainName: String, encryptedName: String): SharedPreferences {
        synchronized(fallbackStores) {
            fallbackStores[encryptedName]?.let { return it }
        }
        var lastFailure: Throwable? = null
        repeat(CREATE_ATTEMPTS) { attempt ->
            runCatching { createEncrypted(context, encryptedName) }
                .onSuccess { encrypted ->
                    migratePlainToEncrypted(context, plainName, encrypted)
                    return encrypted
                }
                .onFailure { e ->
                    lastFailure = e
                    Log.w(TAG, "Encrypted prefs attempt ${attempt + 1}/$CREATE_ATTEMPTS failed for $encryptedName", e)
                    // Sirf SAABIT kharabi par key delete karein — aur wo
                    // bhi doosri tasdeeq ke baad, unlocked phone par.
                    if (isCorruptionSuspect(e)) {
                        if (wasCorruptionSuspected(context, encryptedName) && isUserUnlocked(context)) {
                            Log.e(TAG, "Key corruption confirmed twice for $encryptedName — clearing master key + file and retrying once")
                            clearCorruptionSuspect(context, encryptedName)
                            deleteMasterKey()
                            context.deleteSharedPreferences(encryptedName)
                        } else {
                            // Pehli dafa: sirf marker lagayein, kuch delete nahi.
                            markCorruptionSuspected(context, encryptedName)
                        }
                    }
                    if (attempt + 1 < CREATE_ATTEMPTS) {
                        runCatching { Thread.sleep(RETRY_DELAY_BASE_MS * (attempt + 1)) }
                    }
                }
        }
        // Teenon koshishen nakaam — FAIL CLOSED: sirf memory wala store.
        // Encrypted file ko haath nahi lagaya (agli launch par dobara koshish ho gi).
        lastError = lastFailure?.let { "${it.javaClass.simpleName}: ${it.message}" }
        degraded = true
        Log.e(TAG, "Encrypted prefs UNAVAILABLE for $encryptedName after $CREATE_ATTEMPTS attempts — using IN-MEMORY store (not persisted, nothing written to disk). Last error: $lastError")
        synchronized(fallbackStores) {
            return fallbackStores.getOrPut(encryptedName) { InMemoryPrefs() }
        }
    }

    private fun createEncrypted(context: Context, name: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Sirf wo ghalti jo key ke kharab hone ka SABOOT hai:
     * UnrecoverableKeyException (key wapas nahi mil sakti). Baqi sab
     * (KeyStoreException — keystore busy/locked, GeneralSecurityException
     * waghera) aarzi ho sakti hain — in par key delete karna mana hai.
     */
    private fun isCorruptionSuspect(e: Throwable): Boolean = e is UnrecoverableKeyException

    private fun isUserUnlocked(context: Context): Boolean {
        return runCatching {
            (context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked
        }.getOrDefault(true)
    }

    private fun metaPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(META_PREFS_NAME, Context.MODE_PRIVATE)

    private fun markCorruptionSuspected(context: Context, encryptedName: String) {
        runCatching {
            metaPrefs(context).edit()
                .putBoolean(KEY_CORRUPT_SUSPECT_PREFIX + encryptedName, true).apply()
        }
    }

    private fun wasCorruptionSuspected(context: Context, encryptedName: String): Boolean {
        return runCatching {
            metaPrefs(context).getBoolean(KEY_CORRUPT_SUSPECT_PREFIX + encryptedName, false)
        }.getOrDefault(false)
    }

    private fun clearCorruptionSuspect(context: Context, encryptedName: String) {
        runCatching {
            metaPrefs(context).edit()
                .remove(KEY_CORRUPT_SUSPECT_PREFIX + encryptedName).apply()
        }
    }

    private fun deleteMasterKey() {
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            // MasterKey.DEFAULT_MASTER_KEY_ALIAS ("_androidx_security_master_key_").
            ks.deleteEntry("_androidx_security_master_key_")
        }.onFailure { Log.w(TAG, "Could not delete master key", it) }
    }

    /**
     * Purani plain file se nayi encrypted file mein copy karein.
     * Sirf wo keys copy hoti hain jo encrypted mein pehle se maujood
     * na hon (encrypted hamesha jeetta hai). Copy ke baad plain file
     * khaali kar di jati hai. Marker lagne ke baad bhi plain mein
     * kuch reh jaye (purane version ke fallback ke doran likha gaya
     * ho) to wo bhi isi tareeqe se migrate ho jata hai — phir plain
     * file hamesha ke liye saaf rehti hai (naya plain likhna band hai).
     */
    private fun migratePlainToEncrypted(
        context: Context,
        plainName: String,
        encrypted: SharedPreferences
    ) {
        val plain = context.getSharedPreferences(plainName, Context.MODE_PRIVATE)
        val entries = plain.all
        if (entries.isEmpty()) {
            if (!encrypted.contains(KEY_MIGRATED)) {
                encrypted.edit().putBoolean(KEY_MIGRATED, true).apply()
            }
            return
        }
        val editor = encrypted.edit()
        var copied = 0
        for ((key, value) in entries) {
            if (encrypted.contains(key)) continue
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
                else -> Log.w(TAG, "Skipping unmigratable plain pref $key (${value?.javaClass?.name})")
            }
            copied++
        }
        editor.putBoolean(KEY_MIGRATED, true)
        // Pehle encrypted mein pakka likhein (commit), phir plain saaf karein.
        if (!editor.commit()) {
            Log.e(TAG, "Migration commit FAILED for $plainName — plain prefs kept (will retry next launch)")
            return
        }
        plain.edit().clear().apply()
        Log.i(TAG, "Migrated $copied plain prefs from $plainName to encrypted storage")
    }

    /**
     * Sirf memory mein rehne wala SharedPreferences — J2-M17 fallback.
     * Is mein likha kuch bhi phone ki storage par kabhi nahi utarta:
     * app band hote hi sab khatam. Isi liye ye secrets ke liye
     * mehfooz hai (plain file ki tarah leak nahi karta) — aur is
     * liye bhi ke is mein likhi "registration" agli launch par
     * dobara nazar nahi aaye gi (asli encrypted file barkarar hai).
     */
    private class InMemoryPrefs : SharedPreferences {

        private val data = HashMap<String, Any?>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        @Suppress("UNCHECKED_CAST")
        override fun getAll(): Map<String, *> = synchronized(data) { HashMap(data) }

        override fun getString(key: String, defValue: String?): String? =
            synchronized(data) { (data[key] as? String) ?: defValue }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            synchronized(data) { ((data[key] as? Set<String>)?.toSet()) ?: defValues }

        override fun getInt(key: String, defValue: Int): Int =
            synchronized(data) { (data[key] as? Int) ?: defValue }

        override fun getLong(key: String, defValue: Long): Long =
            synchronized(data) { (data[key] as? Long) ?: defValue }

        override fun getFloat(key: String, defValue: Float): Float =
            synchronized(data) { (data[key] as? Float) ?: defValue }

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            synchronized(data) { (data[key] as? Boolean) ?: defValue }

        override fun contains(key: String): Boolean = synchronized(data) { data.containsKey(key) }

        override fun edit(): SharedPreferences.Editor = InMemoryEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) {
            synchronized(listeners) { listeners.add(listener) }
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) {
            synchronized(listeners) { listeners.remove(listener) }
        }

        private inner class InMemoryEditor : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor =
                apply { pending[key] = value; removals.remove(key) }

            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor =
                apply { pending[key] = values?.toSet(); removals.remove(key) }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor =
                apply { pending[key] = value; removals.remove(key) }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor =
                apply { pending[key] = value; removals.remove(key) }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
                apply { pending[key] = value; removals.remove(key) }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
                apply { pending[key] = value; removals.remove(key) }

            override fun remove(key: String): SharedPreferences.Editor =
                apply { removals.add(key); pending.remove(key) }

            override fun clear(): SharedPreferences.Editor =
                apply { clearAll = true; pending.clear(); removals.clear() }

            override fun commit(): Boolean {
                applyChanges()
                return true
            }

            override fun apply() {
                applyChanges()
            }

            private fun applyChanges() {
                val changedKeys = mutableSetOf<String>()
                synchronized(data) {
                    if (clearAll) {
                        changedKeys.addAll(data.keys)
                        data.clear()
                    }
                    for (key in removals) {
                        if (data.remove(key) != null) changedKeys.add(key)
                    }
                    for ((key, value) in pending) {
                        if (value == null) {
                            if (data.remove(key) != null) changedKeys.add(key)
                        } else {
                            data[key] = value
                            changedKeys.add(key)
                        }
                    }
                }
                pending.clear()
                removals.clear()
                clearAll = false
                val snapshot = synchronized(listeners) { listeners.toList() }
                for (key in changedKeys) {
                    for (listener in snapshot) {
                        runCatching { listener.onSharedPreferenceChanged(this@InMemoryPrefs, key) }
                    }
                }
            }
        }
    }
}

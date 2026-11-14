package pk.amgsystems.lock

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/**
 * Encrypted SharedPreferences (PU-M7 / A-M11 / Wave 5.8).
 *
 * Dono stores (LockStateStore "amg_lock" aur OfflineCodeStore
 * "amg_lock_offline") ab encrypted files mein rehte hain — pairing
 * code, deviceId, server URL, reset PIN, offline-code hash aur salt
 * plain text mein phone par nahi milte. Koi phone ya backup parh
 * kar ye cheezein nahi nikaal sakta.
 *
 * Migration (pehli launch par, khud bakhud):
 *  - Purani plain file mein jo kuch hai, wo encrypted file mein
 *    copy ho jata hai, phir plain file khaali kar di jati hai.
 *    Pehle se registered phone baghair dobara register kiye chalte
 *    rehte hain — koi data nahi khota.
 *  - Copy pehle encrypted mein pakka likha jata hai (commit), phir
 *    plain saaf hoti hai — beech mein app band bhi ho jaye to data
 *    do mein se aik jagah zaroor rehta hai.
 *
 * Keystore kharab ho (bohat nayaab — masalan corrupt key):
 *  - Sirf keystore wali ghalti (KeyStoreException /
 *    UnrecoverableKeyException / GeneralSecurityException) par aik
 *    dafa master key + encrypted file delete kar ke dobara koshish
 *    hoti hai. Doosri qism ki ghalti par key ko haath nahi lagate
 *    (masalan device abhi lock ho to key delete karna data ganwa
 *    de ga).
 *  - Phir bhi na bane to app plain prefs par wapas chali jati hai
 *    taake app crash na ho — ye halat log mein saaf likhi jati hai
 *    ("FALLING BACK TO PLAIN"). Is halat mein naya data plain file
 *    mein likha jata hai aur agli kamyab launch par dobara migrate
 *    ho jata hai (encrypted hamesha jeetta hai — migration sirf wo
 *    keys copy karti hai jo encrypted mein pehle se na hon).
 */
object SecurePrefs {

    private const val TAG = "SecurePrefs"

    /** Migration ho chuki hai ya nahi — encrypted file mein marker. */
    private const val KEY_MIGRATED = "amg_enc_migrated"

    /**
     * Encrypted prefs banayein (ya na ban sakein to plain fallback).
     * [plainName] purani plain file ka naam hai (migration ke liye),
     * [encryptedName] nayi encrypted file ka naam hai.
     */
    fun create(context: Context, plainName: String, encryptedName: String): SharedPreferences {
        val encrypted = runCatching { createEncrypted(context, encryptedName) }
            .recoverCatching { e ->
                if (isKeyCorruption(e)) {
                    // Key kharab hai — nikaal kar nayi banayein (aik dafa).
                    Log.w(TAG, "Encrypted key looks corrupt, clearing master key + file and retrying once", e)
                    deleteMasterKey()
                    context.deleteSharedPreferences(encryptedName)
                    createEncrypted(context, encryptedName)
                } else {
                    throw e
                }
            }
            .getOrNull()
        if (encrypted == null) {
            // Aakhri rasta: plain prefs (app crash hone se behtar hai).
            Log.e(TAG, "Encrypted prefs unavailable for $encryptedName — FALLING BACK TO PLAIN prefs (data NOT encrypted)")
            return context.getSharedPreferences(plainName, Context.MODE_PRIVATE)
        }
        migratePlainToEncrypted(context, plainName, encrypted)
        return encrypted
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

    /** Sirf key kharab hone wali ghaltiyan — in par hi key delete karna theek hai. */
    private fun isKeyCorruption(e: Throwable): Boolean = when (e) {
        is java.security.KeyStoreException,
        is java.security.UnrecoverableKeyException,
        is java.security.GeneralSecurityException -> true
        else -> false
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
     * kuch reh jaye (fallback ke doran likha gaya ho) to wo bhi isi
     * tareeqe se aahista aahista migrate ho jata hai.
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
}

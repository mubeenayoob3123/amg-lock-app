package pk.amgsystems.lock

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

/**
 * QR provisioning + pairing QR (Wave 5.6, A-M13).
 *
 * Do alag QR hain — in ko milayein nahi:
 *
 * 1) PAIRING QR (in-app scan, MainActivity "QR Scan Karein"):
 *    Dashboard har account ke liye banata hai. App pehle se installed
 *    hai (QR provisioning ya adb se Device Owner bana hua hai); shop
 *    wala is QR ko scan karta hai taake Setup (server URL + pairing
 *    code) haath se likhne ki bajaye khud bhar jaye. Scan ke baad
 *    Register haath se dabana parta hai.
 *    EXACT JSON (dashboard D1 banata hai — field names na badlein):
 *      {"serverUrl": "https://amg-device-api.onrender.com",
 *       "pairingCode": "XXXXXXXX",
 *       "apkUrl": "<shop settings se, default khaali>"}
 *    apkUrl is scan mein istemal nahi hota (app pehle se installed
 *    hai) — wo provisioning QR ke liye hota hai (neeche).
 *
 * 2) PROVISIONING QR (factory reset + Device Owner):
 *    Android ka standard managed-provisioning format hai (dekhein
 *    QR-PROVISIONING.md). Is ke PROVISIONING_* keys (component name,
 *    signature checksum, APK download location, Wi-Fi) SYSTEM khud
 *    parhta hai — app ko ye kabhi nahi milte. Download location =
 *    apkUrl (shop settings se hosted release APK ka link). App ko
 *    sirf PROVISIONING_ADMIN_EXTRAS_BUNDLE milta hai, jis mein shop
 *    ye keys rakh sakti hai:
 *      "server_url"    — server URL (Setup pre-fill)
 *      "pairing_code"  — pairing code (Setup pre-fill)
 *    Ye bundle AmgDeviceAdminReceiver.onProfileProvisioningComplete
 *    mein aata hai aur applyProvisioningExtras() use store mein
 *    likhta hai, taake MainActivity khulne par Setup pehle se bhara ho.
 */
object QrProvisioning {

    // --- 1) Dashboard pairing QR ---

    /** Dashboard pairing QR ka parsed payload. "" = QR mein wo field nahi thi. */
    data class PairingQrPayload(
        val serverUrl: String,
        val pairingCode: String,
        val apkUrl: String,
    )

    /**
     * QR text parse karein: pehle dashboard JSON, warna saada 8-harf
     * pairing code (purana QR ya haath se bana hua). Null = ye QR AMG
     * ka pairing QR nahi lagta (MainActivity saaf ghalti dikhaye).
     * Pairing code ke baghair QR bekaar hai — server URL akela
     * pre-fill karne ka faida nahi, is liye code lazmi hai.
     */
    fun parsePairingQr(text: String): PairingQrPayload? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("{")) {
            return runCatching {
                val json = JSONObject(trimmed)
                val code = json.optString("pairingCode", "").trim().uppercase()
                if (code.isBlank()) return null
                PairingQrPayload(
                    serverUrl = json.optString("serverUrl", "").trim(),
                    pairingCode = code,
                    apkUrl = json.optString("apkUrl", "").trim(),
                )
            }.getOrNull()
        }
        // Fallback: saada pairing code (8 harf/digit).
        val code = trimmed.uppercase()
        if (code.length == 8 && code.all { it.isLetterOrDigit() }) {
            return PairingQrPayload(serverUrl = "", pairingCode = code, apkUrl = "")
        }
        return null
    }

    // --- 2) Full Android QR provisioning (admin extras bundle) ---

    /** Admin extras mein server URL ki key (provisioning QR ke bundle mein). */
    const val KEY_SERVER_URL = "server_url"

    /** Admin extras mein pairing code ki key (provisioning QR ke bundle mein). */
    const val KEY_PAIRING_CODE = "pairing_code"

    /**
     * Provisioning ke waqt mile admin extras ko encrypted store mein
     * likhein (Setup pre-fill ke liye). Bundle na ho to kuch nahi hota
     * — shop Setup haath se bhare gi. Code ki value kabhi log mein nahi.
     */
    fun applyProvisioningExtras(context: Context, intent: Intent) {
        val bundle = intent.getBundleExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
        ) ?: run {
            Log.i(TAG, "Provisioning complete — no admin extras bundle (manual Setup hoga)")
            return
        }
        val store = LockStateStore(context)
        var applied = 0
        val url = bundle.getString(KEY_SERVER_URL, "").orEmpty().trim()
        if (url.isNotBlank()) {
            store.serverUrl = url
            applied++
        }
        val code = bundle.getString(KEY_PAIRING_CODE, "").orEmpty().trim().uppercase()
        if (code.isNotBlank()) {
            store.pairingCode = code
            applied++
        }
        Log.i(TAG, "Provisioning admin extras applied: $applied field(s) pre-filled")
    }

    private const val TAG = "QrProvisioning"
}

package pk.amgsystems.lock

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Client for the AMG web dashboard server flow.
 *
 * The dashboard (amg-dashboard artifact) already implements this API
 * on the server side. If the server changes a field name, change it
 * HERE only — the rest of the app talks to these data classes.
 *
 * Endpoints (all POST, JSON, relative to the server URL set in Setup):
 *
 *   POST /api/device/register
 *     Request : { "pairingCode": "YD5NG6RK", "imei": "...", "model": "Samsung A54",
 *                 "androidVersion": "14" }
 *     Response: { "ok": true, "deviceId": "dev_...", "accountId": "...",
 *                 "shopName": "Al Arab Electronic", "shopPhone": "03xx-xxxxxxx" }
 *     Wrong code -> { "ok": false, "error": "invalid_pairing_code" }
 *
 *   POST /api/device/checkin
 *     Request : { "deviceId": "dev_...", "pairingCode": "YD5NG6RK" }
 *     Response: { "ok": true,
 *                 "instruction": "stay_unlocked" | "warn" | "lock_now" | "permanent_unlock",
 *                 "dueDate": "2026-12-10", "remainingAmount": 48000,
 *                 "graceDaysLeft": 3, "shopName": "...", "shopPhone": "...",
 *                 "offlineUnlockCode": "YY28LV",       // only with lock_now, fresh each lock
 *                 // Wave 3 (optional — purana server ye nahi bhejta):
 *                 "overdueAmount": 10000, "overdueDueDate": "2026-11-10",
 *                 "overdueCount": 1, "nextDueDate": "2026-12-10",
 *                 "monthlyAmount": 10000, "offlineUnlockUntil": "2026-11-15T..."
 *               }
 *     The server applies the policy itself: grace (3/5/7 days) expired
 *     -> lock_now; a payment covering the overdue installment -> the
 *     next check-in returns stay_unlocked; final payment -> permanent_unlock.
 *
 *   POST /api/device/offline-code-used
 *     Request : { "deviceId": "dev_...", "code": "YY28LV" }
 *     Response: { "ok": true } — tells the dashboard the code was burned.
 *
 * Network: HttpURLConnection + org.json (built into Android) on purpose —
 * no Retrofit dependency to break. All calls run on a background thread
 * (WorkManager / a plain Thread); never call these from the UI thread.
 *
 * Wave 3 (A-M2): check-in ab "null ya result" nahi deta — teen saaf
 * nataij deta hai (Success / ServerError / NetworkError), taake
 * "server ne mana kar diya" aur "internet nahi hai" aik jaise na lagein.
 */
class ServerApi(private val context: Context) {

    private val store = LockStateStore(context)

    private fun baseUrl(serverUrl: String): String = serverUrl.trim().trimEnd('/')

    /**
     * Register this phone with the pairing code from the dashboard.
     * [serverUrl] is used for THIS call only — it is saved into the
     * store only after a successful register (A-M8/F-9: a typo must
     * not poison all future check-ins).
     */
    fun register(pairingCode: String, serverUrl: String): RegisterResult {
        return try {
            val body = JSONObject().apply {
                put("pairingCode", pairingCode.trim().uppercase())
                put("imei", DeviceInfo.imei(context))
                put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("androidVersion", Build.VERSION.RELEASE)
            }
            val json = post(baseUrl(serverUrl), "/api/device/register", body, REGISTER_TIMEOUT_MS)
            if (json.optBoolean("ok", false)) {
                val deviceId = json.optString("deviceId", "")
                // A-n2: khaali deviceId ke saath "kamyab" register jhooti
                // registration hai — baad ki har check-in fail ho gi.
                if (deviceId.isBlank()) {
                    return RegisterResult.Failure("empty_device_id", ErrorKind.SERVER_ERROR)
                }
                // Sirf kamyabi par save karein (URL bhi — A-M8).
                store.serverUrl = serverUrl.trim()
                store.deviceId = deviceId
                store.pairingCode = pairingCode.trim().uppercase()
                store.accountId = json.opt("accountId")?.toString() ?: ""
                store.isRegistered = true
                store.shopName = json.optString("shopName", store.shopName)
                store.shopPhone = json.optString("shopPhone", store.shopPhone)
                RegisterResult.Success(deviceId, store.accountId)
            } else {
                val error = json.optString("error", "register_failed")
                val kind = if (error == "invalid_pairing_code") ErrorKind.WRONG_CODE else ErrorKind.SERVER_ERROR
                RegisterResult.Failure(error, kind)
            }
        } catch (e: Exception) {
            Log.e(TAG, "register failed", e)
            RegisterResult.Failure("network_error: ${e.message}", classifyNetworkError(e))
        }
    }

    /**
     * Ask the server what to do right now.
     * Teen nataij (A-M2): Success / ServerError (server ne jawab diya
     * magar mana kar diya, masalan device_not_registered) /
     * NetworkError (internet nahi, ya server so raha hai).
     */
    fun checkIn(): CheckInOutcome {
        return try {
            val body = JSONObject().apply {
                put("deviceId", store.deviceId)
                put("pairingCode", store.pairingCode)
            }
            val json = post(baseUrl(store.serverUrl), "/api/device/checkin", body, CHECKIN_TIMEOUT_MS)
            if (!json.optBoolean("ok", false)) {
                return CheckInOutcome.ServerError(json.optString("error", "server_error"))
            }
            // A-M1: gumshuda instruction ka default "stay_unlocked" NAHI
            // hai — khaali string aaye to PolicyEngine fail-safe rehta hai
            // (purani halat barkarar, unlock nahi hota).
            CheckInOutcome.Success(
                CheckInResult(
                    instruction = json.optString("instruction", ""),
                    dueDate = json.optString("dueDate", ""),
                    remainingAmount = json.optLong("remainingAmount", 0L),
                    graceDaysLeft = json.optInt("graceDaysLeft", 0),
                    shopName = json.optString("shopName", store.shopName),
                    shopPhone = json.optString("shopPhone", store.shopPhone),
                    offlineUnlockCode = json.optString("offlineUnlockCode", "").ifBlank { null },
                    // Wave 3.5 — OPTIONAL: purana server ye fields nahi
                    // bhejta; has() se check karein, warna null.
                    overdueAmount = if (json.has("overdueAmount")) json.optLong("overdueAmount", 0L) else null,
                    overdueDueDate = if (json.has("overdueDueDate")) json.optString("overdueDueDate", "") else null,
                    overdueCount = if (json.has("overdueCount")) json.optInt("overdueCount", 0) else null,
                    nextDueDate = if (json.has("nextDueDate")) json.optString("nextDueDate", "") else null,
                    monthlyAmount = if (json.has("monthlyAmount")) json.optLong("monthlyAmount", 0L) else null,
                    offlineUnlockUntil = if (json.has("offlineUnlockUntil")) json.optString("offlineUnlockUntil", "") else null
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "check-in failed (offline? cached state stays)", e)
            CheckInOutcome.NetworkError(classifyNetworkError(e))
        }
    }

    /**
     * Tell the server an offline code was used.
     * Returns true only if the server confirmed it — caller keeps a
     * pending retry (A-M3) when this returns false.
     */
    fun reportOfflineCodeUsed(code: String): Boolean {
        return try {
            val body = JSONObject().apply {
                put("deviceId", store.deviceId)
                put("code", code.trim().uppercase())
            }
            val json = post(baseUrl(store.serverUrl), "/api/device/offline-code-used", body, CHECKIN_TIMEOUT_MS)
            json.optBoolean("ok", false)
        } catch (e: Exception) {
            Log.w(TAG, "report offline-code-used failed (pending retry rakha jaye ga)", e)
            false
        }
    }

    /** Network exception ko saaf qism mein badlein (UI ke liye). */
    private fun classifyNetworkError(e: Exception): ErrorKind = when (e) {
        is UnknownHostException, is ConnectException -> ErrorKind.NO_INTERNET
        is SocketTimeoutException -> ErrorKind.SERVER_ASLEEP
        is HttpStatusException -> ErrorKind.SERVER_ERROR
        else -> {
            // MalformedURLException waghera: URL hi ghalat hai.
            if (e is java.net.MalformedURLException) ErrorKind.BAD_URL else ErrorKind.OTHER
        }
    }

    private fun post(base: String, path: String, body: JSONObject, timeoutMs: Int): JSONObject {
        val url = URL(base + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            // F-10 / A-n15: Render free server sone ke baad jagane mein
            // ~60 second leta hai — 15 second ka timeout pehli koshish
            // hamesha nakaam kar deta tha.
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-AMG-Device", Build.MODEL)
        }
        try {
            conn.outputStream.use { out ->
                OutputStreamWriter(out, Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val code = conn.responseCode
            // A-n1: errorStream null bhi ho sakta hai, aur error jawab
            // par inputStream exception phainkta hai — dono ko sambhalein.
            val stream = if (code in 200..299) {
                runCatching { conn.inputStream }.getOrNull()
            } else {
                conn.errorStream
            }
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            if (code !in 200..299) throw HttpStatusException(code, text)
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    /** HTTP ghair-2xx jawab — is mein server ka error chhupa hota hai. */
    class HttpStatusException(val status: Int, body: String) : RuntimeException("HTTP $status: $body")

    /** Saaf error qisam — UI is se seedha sadha paigham dikhata hai (F-11). */
    enum class ErrorKind {
        /** Pairing code ghalat hai. */
        WRONG_CODE,
        /** Internet nahi hai (DNS/connect fail). */
        NO_INTERNET,
        /** Server jawab dene mein dair kar raha hai (so raha hai / slow). */
        SERVER_ASLEEP,
        /** Server ne jawab diya magar ghalat / error. */
        SERVER_ERROR,
        /** Server URL hi ghalat likha hai. */
        BAD_URL,
        /** Koi aur masla. */
        OTHER
    }

    sealed interface RegisterResult {
        data class Success(val deviceId: String, val accountId: String) : RegisterResult
        data class Failure(val error: String, val kind: ErrorKind) : RegisterResult
    }

    sealed interface CheckInOutcome {
        data class Success(val result: CheckInResult) : CheckInOutcome
        /** Server ne jawab diya magar ok:false (masalan device_not_registered). */
        data class ServerError(val error: String) : CheckInOutcome
        /** Request server tak pohnchi hi nahi (internet / timeout). */
        data class NetworkError(val kind: ErrorKind) : CheckInOutcome
    }

    data class CheckInResult(
        val instruction: String,
        val dueDate: String,
        val remainingAmount: Long,
        val graceDaysLeft: Int,
        val shopName: String,
        val shopPhone: String,
        /** Fresh one-time code, present only when instruction == lock_now. */
        val offlineUnlockCode: String?,
        // --- Wave 3.5 optional fields (null = purana server, fallback istemal karein) ---
        /** Sirf OVERDUE qist/qiston ki baqi raqam (lock screen ye dikhaye). */
        val overdueAmount: Long?,
        /** Sab se pehli overdue qist ki tareekh (yyyy-MM-dd). */
        val overdueDueDate: String?,
        /** Kitni qistein overdue hain. */
        val overdueCount: Int?,
        /** Agli (na ada shuda) qist ki tareekh. */
        val nextDueDate: String?,
        /** Agli qist ki raqam (mahina-waar qist). */
        val monthlyAmount: Long?,
        /** Offline-code wali unlock window kab khatam hoti hai (ISO). */
        val offlineUnlockUntil: String?
    )

    companion object {
        private const val TAG = "ServerApi"
        private const val REGISTER_TIMEOUT_MS = 60_000
        private const val CHECKIN_TIMEOUT_MS = 60_000
    }
}

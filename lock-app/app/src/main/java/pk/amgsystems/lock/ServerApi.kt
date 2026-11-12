package pk.amgsystems.lock

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

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
 *                 "offlineUnlockCode": "YY28LV"        // only with lock_now, fresh each lock
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
 */
class ServerApi(private val context: Context) {

    private val store = LockStateStore(context)

    private fun baseUrl(): String = store.serverUrl.trimEnd('/')

    /** Register this phone with the pairing code from the dashboard. */
    fun register(pairingCode: String): RegisterResult {
        return try {
            val body = JSONObject().apply {
                put("pairingCode", pairingCode.trim().uppercase())
                put("imei", DeviceInfo.imei(context))
                put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("androidVersion", Build.VERSION.RELEASE)
            }
            val json = post("/api/device/register", body)
            if (json.optBoolean("ok", false)) {
                val deviceId = json.optString("deviceId", "")
                store.deviceId = deviceId
                store.pairingCode = pairingCode.trim().uppercase()
                store.isRegistered = true
                store.shopName = json.optString("shopName", store.shopName)
                store.shopPhone = json.optString("shopPhone", store.shopPhone)
                RegisterResult.Success(deviceId)
            } else {
                RegisterResult.Failure(json.optString("error", "register_failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "register failed", e)
            RegisterResult.Failure("network_error: ${e.message}")
        }
    }

    /** Ask the server what to do right now. Returns null on network failure. */
    fun checkIn(): CheckInResult? {
        return try {
            val body = JSONObject().apply {
                put("deviceId", store.deviceId)
                put("pairingCode", store.pairingCode)
            }
            val json = post("/api/device/checkin", body)
            if (!json.optBoolean("ok", false)) return null
            CheckInResult(
                instruction = json.optString("instruction", "stay_unlocked"),
                dueDate = json.optString("dueDate", ""),
                remainingAmount = json.optLong("remainingAmount", 0L),
                graceDaysLeft = json.optInt("graceDaysLeft", 0),
                shopName = json.optString("shopName", store.shopName),
                shopPhone = json.optString("shopPhone", store.shopPhone),
                offlineUnlockCode = json.optString("offlineUnlockCode", "").ifBlank { null }
            )
        } catch (e: Exception) {
            Log.e(TAG, "check-in failed (offline? cached state stays)", e)
            null // No internet -> caller keeps the cached lock state.
        }
    }

    /** Tell the server an offline code was used (best-effort, ignore failure). */
    fun reportOfflineCodeUsed(code: String) {
        try {
            val body = JSONObject().apply {
                put("deviceId", store.deviceId)
                put("code", code.trim().uppercase())
            }
            post("/api/device/offline-code-used", body)
        } catch (e: Exception) {
            Log.w(TAG, "report offline-code-used failed (will retry next check-in)", e)
        }
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val url = URL(baseUrl() + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-AMG-Device", Build.MODEL)
        }
        conn.outputStream.use { out ->
            OutputStreamWriter(out, Charsets.UTF_8).use { it.write(body.toString()) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        conn.disconnect()
        if (code !in 200..299) throw RuntimeException("HTTP $code: $text")
        return JSONObject(text)
    }

    sealed interface RegisterResult {
        data class Success(val deviceId: String) : RegisterResult
        data class Failure(val error: String) : RegisterResult
    }

    data class CheckInResult(
        val instruction: String,
        val dueDate: String,
        val remainingAmount: Long,
        val graceDaysLeft: Int,
        val shopName: String,
        val shopPhone: String,
        /** Fresh one-time code, present only when instruction == lock_now. */
        val offlineUnlockCode: String?
    )

    companion object {
        private const val TAG = "ServerApi"
    }
}

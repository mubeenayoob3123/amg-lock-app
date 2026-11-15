package pk.amgsystems.lock

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate

/**
 * Local deadline lock (J2-M14 — v1.4.0, Mubeen ka faisla).
 *
 * PEHLE KA MASLA: phone sirf tab lock hota tha jab check-in kamyab
 * ho. Internet band karke (airplane mode / SIM nikaal kar) customer
 * har lock se hamesha ke liye bach sakta tha — grace guzarne ke
 * baad bhi phone kabhi lock nahi hota tha. Offline code istemal
 * karke offline rehne par 24 ghante ki window bhi kabhi khatam nahi
 * hoti thi (window sirf server check-in par enforce hoti thi).
 *
 * NAYA RULE (V2 §1 ke fail-open-offline rule mein TABDEELI — Mubeen
 * ne 2026-11-15 ko manzoor kiya): phone ke paas aakhri sync wala
 * schedule hai, is liye wo khud jaanta hai ke qist kab due thi.
 * Agar phone aakhri sync wali deadline guzarne ke baad bhi offline
 * rahe to wo KHUD lock ho jata hai:
 *
 *   deadline = pehli na ada shuda qist ki tareekh + grace (din) + BUFFER (48 ghante)
 *
 * BUFFER (48 ghante) jaan boojh kar rakha hai — asal zindagi mein
 * internet 1-2 din band ho sakta hai (gaaon, recharge khatam, phone
 * kharab), is liye deadline guzarne ke foran baad lock nahi lagta.
 * 48 ghante ka waqfa genuine outage ke liye kaafi hai, aur cheating
 * ke liye bekaar hai (2 din baad bhi lock lag hi jata hai).
 *
 * SAFETY (ghalat lock na lage):
 *  - Schedule mein kuch overdue NA HO to kabhi lock nahi lagta.
 *  - Kabhi kamyab check-in hua hi na ho to lock nahi lagta (koi
 *    schedule hi nahi hai — andaze par lock nahi lagta).
 *  - Deadline guzarne KE BAAD koi kamyab check-in ho chuka ho to
 *    lock nahi lagta — server ne us ke baad faisla de diya hai
 *    (payment aa gayi ho to schedule mein nazar aata hai).
 *  - Storage band ho (SecurePrefs.degraded) to lock nahi lagta —
 *    ghair-bharosemand halat par lock nahi lagta.
 *  - COMPLETED / pehle se LOCKED phone par kuch nahi hota.
 *
 * KHULNA: local lock foran khul jata hai jab agli kamyab check-in
 * `stay_unlocked` / `warn` kahe (PolicyEngine localLock flag saaf
 * kar deta hai). Offline code se bhi khulta hai (jaise server lock).
 * Server ka `lock_now` aaye to local lock server lock mein badal
 * jata hai (flag saaf, halat LOCKED rehti hai).
 *
 * 24 GHANTE KI WINDOW: offline code ki window khatam ho jaye aur
 * qist abhi bhi due+grace se guzar chuki ho to phone wapas lock ho
 * jata hai (server agli check-in par waisa hi karta). Guzri hui
 * window ka record bhi saaf ho jata hai, taake status screen par
 * purani window nazar na aaye.
 *
 * Call sites: DeadlineCheckWorker (15 minute, BINA network constraint
 * ke — check-in worker network maangta hai, is liye offline kabhi
 * nahi chalta), BootReceiver (boot par), CheckInWorker (har tick
 * par), MainActivity (app kholne par). Koi network call nahi hoti —
 * sirf local parhna + lock lagana.
 */
object LocalDeadlineLock {

    private const val TAG = "LocalDeadlineLock"

    /** Deadline ke baad ka buffer — 48 ghante (genuine outage ke liye). */
    private const val BUFFER_MS = 48L * 60L * 60L * 1000L
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /**
     * Local deadline check karein. Returns true agar local lock
     * lagaya gaya ho. Har jagah se call karna mehfooz hai (worker,
     * boot, activity) — koi network nahi, sirf local faisla.
     */
    fun evaluate(context: Context): Boolean {
        val store = LockStateStore(context)
        if (!store.isRegistered || store.lockState == LockState.COMPLETED) return false
        if (store.lockState == LockState.LOCKED) return false // pehle se lock hai
        if (SecurePrefs.degraded) return false // schedule par bharosa nahi — andaze par lock nahi
        val lastCheckIn = store.lastCheckInAt
        if (lastCheckIn <= 0L) return false // kabhi kamyab check-in hua hi nahi — koi schedule nahi

        // 1) Pehli na ada shuda qist ki tareekh (schedule se, warna fallback fields se).
        val firstUnpaidDue = firstUnpaidDueDate(store) ?: return false // sab ada — lock nahi
        // Due tareekh abhi aayi hi nahi — kuch overdue nahi, lock nahi.
        if (!firstUnpaidDue.isBefore(DateHelper.today())) return false

        val now = System.currentTimeMillis()
        val graceDays = store.graceDays // -1 = purana server, grace nahi bheja

        // 2) Deadline = due tareekh + grace + 48 ghante ka buffer.
        val duePlusGraceMs: Long = if (graceDays >= 0) {
            val dueStart = DateHelper.startOfDayMillis(firstUnpaidDue.toString()) ?: return false
            dueStart + graceDays * DAY_MS
        } else {
            // Purana server: aakhri sync ke waqt kitni grace baqi thi, us se andaza.
            lastCheckIn + maxOf(store.graceDaysLeft, 0) * DAY_MS
        }
        val deadlineMs = duePlusGraceMs + BUFFER_MS
        val deadlinePassed = now > deadlineMs
        // Deadline guzarne ke baad kamyab check-in ho chuka ho to server
        // ne faisla de diya hai — local lock nahi lagta.
        val checkedInSinceDeadline = lastCheckIn > deadlineMs

        // 3) 24 ghante ki window khatam ho gayi ho, aur qist abhi bhi
        // due+grace se guzar chuki ho — server agli check-in par
        // dobara lock karta, is liye local bhi wapas lock lagata hai.
        val windowUntil = store.offlineUnlockUntil
        val windowExpired = windowUntil.isNotBlank() &&
            runCatching { Instant.parse(windowUntil).toEpochMilli() < now }.getOrDefault(false)
        if (windowExpired) {
            // Guzri hui window ka record saaf karein — status screen
            // par purani window nazar aana band ho (R2-S5 ka app half).
            store.offlineUnlockUntil = ""
        }
        val pastDuePlusGrace = now > duePlusGraceMs

        val shouldLock = (deadlinePassed && !checkedInSinceDeadline) ||
            (windowExpired && pastDuePlusGrace && !checkedInSinceDeadline)
        if (!shouldLock) return false

        Log.w(TAG, "Local deadline lock: first unpaid due $firstUnpaidDue, deadline passed, no check-in since — locking locally")
        LockManager(context).applyLocalDeadlineLock()
        return true
    }

    /**
     * Pehli (sab se pehle due) na ada shuda qist ki tareekh.
     * Schedule ho to us se (paidAmount < amount wali sab se pehli
     * due date); schedule mein sab ada hon to null (kuch overdue
     * nahi). Schedule na ho (purana server) to overdueDueDate /
     * nextDueDate / dueDate fallback hain.
     */
    private fun firstUnpaidDueDate(store: LockStateStore): LocalDate? {
        val json = store.scheduleJson
        if (json.isNotBlank()) {
            runCatching {
                val arr = JSONArray(json)
                var earliest: LocalDate? = null
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val amount = item.optLong("amount", 0L)
                    val paid = item.optLong("paidAmount", 0L)
                    if (paid >= amount) continue
                    val dueStr = item.optString("dueDate", "").trim()
                    if (dueStr.isBlank()) continue
                    val due = LocalDate.parse(dueStr)
                    if (earliest == null || due.isBefore(earliest)) earliest = due
                }
                // Schedule maujood hai: earliest null = sab ada (lock nahi).
                return earliest
            }.onFailure {
                Log.w(TAG, "Schedule JSON parse nahi hua — fallback fields istemal ho rahe hain", it)
            }
        }
        // Fallback (purana server, schedule nahi bhejta): pehli overdue
        // qist ki tareekh, warna agli qist ki tareekh.
        val dueStr = store.overdueDueDate.ifBlank { store.nextDueDate.ifBlank { store.dueDate } }
        if (dueStr.isBlank()) return null
        return runCatching { LocalDate.parse(dueStr.trim()) }.getOrNull()
    }
}

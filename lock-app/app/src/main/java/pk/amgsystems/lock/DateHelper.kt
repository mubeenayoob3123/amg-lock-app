package pk.amgsystems.lock

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

/**
 * Date helpers — sab tareekhein Pakistan time (Asia/Karachi) mein,
 * taake server (S-M1 fix) aur phone aik hi "aaj" par hon.
 * minSdk 26 hai, is liye java.time seedha istemal ho sakta hai.
 */
object DateHelper {

    private val PKT: ZoneId = ZoneId.of("Asia/Karachi")

    /** Aaj ki tareekh (Pakistan). */
    fun today(): LocalDate = LocalDate.now(PKT)

    /** "yyyy-MM-dd" wali tareekh se aaj tak kitne din guzar gaye (0 agar mustaqbil mein ho). */
    fun daysOverdue(dueDateIso: String): Int {
        if (dueDateIso.isBlank()) return 0
        return runCatching {
            val due = LocalDate.parse(dueDateIso.trim())
            ChronoUnit.DAYS.between(due, today()).toInt().coerceAtLeast(0)
        }.getOrDefault(0)
    }

    /** Aaj se [days] din baad ki tareekh, "12 Nov 2026" jaisi shakal mein. */
    fun datePlusDaysFormatted(days: Int): String {
        return runCatching {
            val d = today().plusDays(days.toLong())
            SimpleDateFormat("d MMM yyyy", Locale.getDefault())
                .format(Date.from(d.atStartOfDay(PKT).toInstant()))
        }.getOrDefault("")
    }

    /** "yyyy-MM-dd" ko "12 Nov 2026" jaisi shakal mein badlein. */
    fun formatIsoDate(iso: String): String {
        if (iso.isBlank()) return ""
        return runCatching {
            val d = LocalDate.parse(iso.trim())
            SimpleDateFormat("d MMM yyyy", Locale.getDefault())
                .format(Date.from(d.atStartOfDay(PKT).toInstant()))
        }.getOrDefault(iso)
    }

    /** Millis ko "12 Nov, 14:32" jaisi shakal mein badlein. */
    fun formatDateTime(millis: Long): String {
        if (millis <= 0L) return ""
        return SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(millis))
    }

    /**
     * ISO-8601 waqt ("2026-11-15T01:00:09.000Z") ko "15 Nov, 06:00"
     * (Pakistan waqt) mein badlein (PU-N2 / R-N8 — MainActivity mein
     * offline window ka kachcha ISO nahi dikhana). Parse na ho to
     * jaisa hai waisa wapas (kabhi khaali nahi).
     */
    fun formatIsoDateTime(iso: String): String {
        if (iso.isBlank()) return ""
        return runCatching {
            val dt = Instant.parse(iso.trim()).atZone(PKT)
            DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault()).format(dt)
        }.getOrDefault(iso)
    }

    /** Millis ko sirf waqt "14:32" mein badlein. */
    fun formatTime(millis: Long): String {
        if (millis <= 0L) return ""
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
    }
}

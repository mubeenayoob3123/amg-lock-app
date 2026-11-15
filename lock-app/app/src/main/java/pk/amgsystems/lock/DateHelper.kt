package pk.amgsystems.lock

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Date helpers — sab tareekhein AUR WAQT Pakistan time (Asia/Karachi)
 * mein, taake server aur phone aik hi "aaj" aur aik hi ghari par hon.
 *
 * J1-m72 fix: pehle formatDateTime/formatTime phone ke apne timezone
 * mein waqt dikhate thay (jabke tareekhein PKT mein thin) — ghalat
 * timezone wale phone par "Aakhri check" aur due tareekh mein ghanton
 * ka farq nazar aata tha. Ab har format PKT hai.
 *
 * java.time formatters thread-safe hain (SimpleDateFormat ke bar-aks,
 * jo shared istemal mein kharab ho jata hai) — is liye ye shared
 * formatter mehfooz hain. minSdk 26 hai, java.time seedha chalta hai.
 */
object DateHelper {

    private val PKT: ZoneId = ZoneId.of("Asia/Karachi")

    private val DATE_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    private val DATETIME_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
    private val TIME_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

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
            DATE_FMT.format(today().plusDays(days.toLong()))
        }.getOrDefault("")
    }

    /** "yyyy-MM-dd" ko "12 Nov 2026" jaisi shakal mein badlein. */
    fun formatIsoDate(iso: String): String {
        if (iso.isBlank()) return ""
        return runCatching {
            DATE_FMT.format(LocalDate.parse(iso.trim()))
        }.getOrDefault(iso)
    }

    /** Millis ko "12 Nov, 14:32" jaisi shakal mein badlein (Pakistan waqt). */
    fun formatDateTime(millis: Long): String {
        if (millis <= 0L) return ""
        return DATETIME_FMT.format(Instant.ofEpochMilli(millis).atZone(PKT))
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

    /** Millis ko sirf waqt "14:32" mein badlein (Pakistan waqt). */
    fun formatTime(millis: Long): String {
        if (millis <= 0L) return ""
        return TIME_FMT.format(Instant.ofEpochMilli(millis).atZone(PKT))
    }

    /**
     * "yyyy-MM-dd" tareekh ki shuruaat (PKT midnight) millis mein —
     * local deadline lock (J2-M14) ke liye. Parse na ho to null.
     */
    fun startOfDayMillis(isoDate: String): Long? {
        if (isoDate.isBlank()) return null
        return runCatching {
            LocalDate.parse(isoDate.trim()).atStartOfDay(PKT).toInstant().toEpochMilli()
        }.getOrNull()
    }
}

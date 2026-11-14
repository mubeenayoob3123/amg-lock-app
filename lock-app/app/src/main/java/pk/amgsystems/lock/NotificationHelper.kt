package pk.amgsystems.lock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Warning + completion notifications (F-19 / F-32).
 *
 * - Warning notification: jab server "warn" kehta hai, to customer ko
 *   phone par aik mustaqil (ongoing) notification milti hai — dabane
 *   par WarningActivity khulti hai. Warning khatam hote hi hata di
 *   jati hai. (Android 13+ par POST_NOTIFICATIONS permission chahiye —
 *   MainActivity maangti hai; permission na ho to notification chup
 *   chap nahi dikhti, crash nahi hota.)
 * - Completion notification: aakhri qist ke baad, CompletionActivity
 *   kholne ke liye (background se activity seedha kholna Android 10+
 *   par band hai, is liye notification hi rasta hai).
 */
object NotificationHelper {

    private const val CHANNEL_WARNING = "amg_warning"
    private const val CHANNEL_COMPLETION = "amg_completion"
    private const val NOTIF_WARNING_ID = 1001
    private const val NOTIF_COMPLETION_ID = 1002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WARNING,
                "Qist Warning",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Qist late hone par warning" }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_COMPLETION,
                "Phone Aap Ka Hai",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Aakhri qist ke baad completion" }
        )
    }

    /** Warning notification dikhayein / taaza karein (persistent). */
    fun showWarning(context: Context) {
        val store = LockStateStore(context)
        // PU-S1 / R-S14: overdueAmount (baqi hissa) pehle — partial
        // payment ke baad poori qist nahi, sirf baqi hissa dikhana hai.
        val amount = when {
            store.overdueAmount > 0 -> store.overdueAmount
            store.monthlyAmount > 0 -> store.monthlyAmount
            else -> store.remainingAmount
        }
        val body = buildString {
            if (amount > 0) append("Rs $amount ki qist baqi hai. ")
            if (store.graceDaysLeft > 0) {
                append("Grace period mein ${store.graceDaysLeft} din baqi hain — ")
            }
            append("us ke baad phone lock ho jaye ga. Abhi ada karein.")
        }
        val intent = Intent(context, WarningActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_WARNING)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.warning_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pending)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIF_WARNING_ID, notification)
        }
    }

    fun cancelWarning(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(NOTIF_WARNING_ID)
        }
    }

    /** Completion notification — dabane par CompletionActivity khulti hai. */
    fun showCompletion(context: Context) {
        val intent = Intent(context, CompletionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETION)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.completed_title))
            .setContentText(context.getString(R.string.completed_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.completed_body)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIF_COMPLETION_ID, notification)
        }
    }
}

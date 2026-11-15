package pk.amgsystems.lock

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Application class.
 * On first launch after provisioning it schedules the periodic check-in:
 * every 15 minutes (the shortest interval WorkManager allows) the app
 * asks the AMG server "lock karna hai ya nahi?" and applies the answer.
 * Notification channels bhi yahin bante hain (warning + completion).
 *
 * Note: MainActivity kholne par bhi aik check-in hota hai (agar aakhri
 * kamyab check-in ko 15 minute se zyada ho gaye hon) — taake payment
 * ke baad customer ko 15 minute ka intezar na karna pare (F-14).
 */
class LockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        scheduleCheckIn()
        // FCM (Wave 5.4): push token taaza karein — FCM band ho to
        // ye no-op hai (FcmManager khud check karta hai).
        FcmManager.refreshTokenIfAvailable(this)
    }

    private fun scheduleCheckIn() {
        // J1-p15: completion ke baad workers band rehte hain — app
        // dobara khulne par bhi schedule nahi hote.
        val store = LockStateStore(this)
        if (store.lockState == LockState.COMPLETED) {
            WorkManager.getInstance(this).cancelUniqueWork(CheckInWorker.WORK_NAME)
            DeadlineCheckWorker.cancel(this)
            return
        }
        val request = PeriodicWorkRequestBuilder<CheckInWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            CheckInWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        // J2-M14: offline deadline check (bina network constraint ke).
        DeadlineCheckWorker.schedule(this)
    }
}

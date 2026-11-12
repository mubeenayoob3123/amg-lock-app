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
 * A check-in is also forced every time MainActivity / LockActivity opens.
 */
class LockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        scheduleCheckIn()
    }

    private fun scheduleCheckIn() {
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
    }
}

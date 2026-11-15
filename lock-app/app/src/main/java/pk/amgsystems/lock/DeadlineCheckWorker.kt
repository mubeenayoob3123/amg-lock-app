package pk.amgsystems.lock

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Offline deadline check (J2-M14) — har 15 minute, BINA network
 * constraint ke.
 *
 * CheckInWorker ko network chahiye hota hai (NetworkType.CONNECTED),
 * is liye offline phone par wo kabhi nahi chalta — aur offline hi
 * wo halat hai jis mein local deadline lock ki zaroorat hai. Is liye
 * ye alag worker hai: sirf [LocalDeadlineLock.evaluate] chalata hai
 * (koi network call nahi), taake internet band hone par bhi deadline
 * guzarne par phone khud lock ho jaye.
 *
 * LockApp dono workers schedule karta hai. Completion par dono cancel
 * ho jate hain (J1-p15).
 */
class DeadlineCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching {
            LocalDeadlineLock.evaluate(applicationContext)
        }.onFailure { Log.w(TAG, "Deadline evaluation failed (ignored — next tick retries)", it) }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "amg-lock-deadline"
        private const val TAG = "DeadlineCheckWorker"

        /** Periodic deadline check schedule karein (15 minute, network ki shart nahi). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DeadlineCheckWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Deadline worker band karein (completion par). */
        fun cancel(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
        }
    }
}

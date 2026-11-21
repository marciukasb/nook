package lt.nook.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import lt.nook.app.NookApplication
import java.util.concurrent.TimeUnit

/** Sends queued changes once the phone is online again, even if Nook was closed. */
class FlushWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val engine = engineFor(applicationContext)
        engine.flush(force = true)
        val s = engine.state.value
        return when {
            s.status == Status.SignInAgain -> Result.success()   // needs the person, not a retry
            s.pending > 0 -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val NAME = "flush"
        var engineFor: (Context) -> SyncEngine = { (it.applicationContext as NookApplication).engine }

        fun schedule(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<FlushWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, req)
        }
    }
}

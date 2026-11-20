package ps.mgtik.hotspot.data.repository

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import ps.mgtik.hotspot.data.offline.OfflineSyncWorker
import ps.mgtik.hotspot.domain.repository.IOfflineScheduler
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OfflineSchedulerImpl - جدولة تنفيذ الطابور عبر WorkManager (§5.3).
 * القيد: اتصال متاح (Firestore يحتاج الإنترنت؛ شبكة الراوتر المحلية بلا إنترنت لا تكفي).
 */
@Singleton
class OfflineSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : IOfflineScheduler {

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private val workManager get() = WorkManager.getInstance(context)

    override fun scheduleSync() {
        val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE: أمر جديد يصل أثناء دورة جارية يُنفَّذ في دورة تليها بدل أن يضيع
        workManager.enqueueUniqueWork(UNIQUE_SYNC, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<OfflineSyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancelAll() {
        workManager.cancelUniqueWork(UNIQUE_SYNC)
        workManager.cancelUniqueWork(UNIQUE_PERIODIC)
    }

    private companion object {
        const val UNIQUE_SYNC = "mgtik_offline_sync"
        const val UNIQUE_PERIODIC = "mgtik_offline_sync_periodic"
        const val BACKOFF_SECONDS = 30L
        const val PERIOD_MINUTES = 15L
    }
}

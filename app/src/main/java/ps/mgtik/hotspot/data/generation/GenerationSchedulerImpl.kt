package ps.mgtik.hotspot.data.generation

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import ps.mgtik.hotspot.domain.repository.IGenerationScheduler
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GenerationSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : IGenerationScheduler {

    override fun schedule(executionId: String) {
        val request = OneTimeWorkRequestBuilder<GenerationWorker>()
            .setInputData(workDataOf(GenerationWorker.KEY_EXECUTION_ID to executionId))
            // الساغا تحتاج السحابة (إنترنت) والراوتر (LAN) معاً؛ تعذّر الراوتر يُعالَج بإعادة المحاولة داخلها
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        // KEEP: عامل واحد لكل تنفيذ (إن كان يعمل فلا يُنشأ ثانٍ؛ إلغاء/إعادة المحاولة يقرؤهما العامل الجاري من Room)
        WorkManager.getInstance(context).enqueueUniqueWork("mgtik_generation_$executionId", ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val TAG = "mgtik_generation"
    }
}

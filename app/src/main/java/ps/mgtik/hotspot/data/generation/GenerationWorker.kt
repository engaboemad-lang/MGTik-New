package ps.mgtik.hotspot.data.generation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.generation.GenerationSaga
import ps.mgtik.hotspot.domain.generation.SagaError
import ps.mgtik.hotspot.domain.generation.SagaOutcome
import ps.mgtik.hotspot.domain.repository.INotificationPublisher

/**
 * GenerationWorker - ينفّذ ساغا توليد واحدة في الخلفية بإشعار Foreground (تتجاوز الدفعات الكبيرة حدّ العشر دقائق).
 * - Completed/Cancelled/Failed ⇒ النتيجة نهائية ومحفوظة في Room ⇒ success.
 * - Suspended (راوتر/سحابة غير متاحين) ⇒ retry بتراجع أُسّي؛ بعد [MAX_ATTEMPTS] محاولات يُعلَّم التنفيذ فاشلاً
 *   (يبقى قابلاً لإعادة المحاولة اليدوية من S5).
 */
@HiltWorker
class GenerationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val factory: GenerationSagaFactory,
    private val runStore: RoomRunStore,
    private val notificationPublisher: INotificationPublisher
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = buildForegroundInfo()

    override suspend fun doWork(): Result {
        val executionId = inputData.getString(KEY_EXECUTION_ID) ?: return Result.failure()

        try {
            setForeground(buildForegroundInfo())
        } catch (e: IllegalStateException) {
            // نظام يمنع بدء خدمة أمامية الآن: نتابع بلا إشعار، والتنفيذ محفوظ ويُستأنف إن قُتل
        }

        val saga = factory.create()
        val outcome = try {
            saga.run(executionId)
        } catch (e: CancellationException) {
            throw e // إيقاف العامل: الحالة المحفوظة تُستأنف لاحقاً
        } catch (e: Exception) {
            // استثناء غير متوقع (Room/Firestore/...): الساغا لا تبتلعه (انظر توثيقها) والعامل مسؤول عنه.
            // بدونه ينتهي العامل فاشلاً بصمت وتبقى الحالة RUNNING بلا أي إشعار حتى إعادة تشغيل التطبيق.
            // يُعامل كعطل مؤقت: إعادة بتراجع أُسّي، وبعد المحاولات الأخيرة تُعلَّم الحالة فاشلة ويُبلَّغ المستخدم.
            val error = SagaError(UNEXPECTED_ERROR, e.localizedMessage ?: e.javaClass.simpleName, true)
            if (runAttemptCount >= MAX_ATTEMPTS) return failExhausted(saga, executionId, error)
            return Result.retry()
        }

        return when (outcome) {
            is SagaOutcome.Completed -> {
                notify { notificationPublisher.generationCompleted(executionId, outcome.partial, orderIdOf(executionId)) }
                Result.success()
            }
            SagaOutcome.Cancelled -> Result.success()
            is SagaOutcome.Failed -> {
                notify { notificationPublisher.generationFailed(executionId, outcome.error.message, orderIdOf(executionId)) }
                Result.success()
            }
            is SagaOutcome.Suspended ->
                if (runAttemptCount >= MAX_ATTEMPTS) failExhausted(saga, executionId, outcome.error) else Result.retry()
        }
    }

    private suspend fun failExhausted(saga: GenerationSaga, executionId: String, error: SagaError): Result {
        try {
            saga.failPermanently(executionId, SagaError(SagaError.ATTEMPTS_EXHAUSTED, error.message, false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // تعذّر حفظ الفشل (قاعدة البيانات): لا نعلن نجاحاً؛ المحاولة التالية/إقلاع التطبيق يعيدان المعالجة
            return Result.retry()
        }
        notify { notificationPublisher.generationFailed(executionId, error.message, orderIdOf(executionId)) }
        return Result.success()
    }

    /** الإشعار ثانوي: فشله لا يقلب نتيجة ساغا انتهت وحُفظت (وإعادة العامل بسببه كانت ستكرّر الإشعار). */
    private suspend fun notify(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // يُتجاهل عمداً
        }
    }

    /** معرّف الطلب لربط الإشعار بوجهته؛ فشل القراءة لا يمنع الإشعار. */
    private suspend fun orderIdOf(executionId: String): String? =
        try {
            runStore.load(executionId)?.orderId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }?.takeIf { it.isNotBlank() }

    private fun buildForegroundInfo(): ForegroundInfo {
        val ctx = applicationContext
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, ctx.getString(R.string.gen_notif_channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = ps.mgtik.hotspot.data.notification.NotificationIntents.plainLaunch(ctx)?.let {
            android.app.PendingIntent.getActivity(ctx, 0, it, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(ctx.getString(R.string.gen_notif_title))
            .setContentText(ctx.getString(R.string.gen_notif_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_EXECUTION_ID = "execution_id"
        const val MAX_ATTEMPTS = 12
        const val UNEXPECTED_ERROR = "ERR_GEN_UNEXPECTED"
        private const val CHANNEL_ID = "mgtik_generation"
        private const val NOTIFICATION_ID = 7301
    }
}

package ps.mgtik.hotspot.data.offline

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import ps.mgtik.hotspot.data.subscription.SubscriptionEngine

/**
 * OfflineSyncWorker - عامل WorkManager الذي ينفّذ طابور الأوامر (§5.3) ثم يفحص الاشتراكات المستحقة.
 * success: لا شيء قابل للإعادة (ما فشل نهائياً يبقى مسجَّلاً بسببه ولا يُعاد تلقائياً).
 * retry: بقيت أوامر تعطلت لسبب مؤقت، أو أوامر راوتر اشتراكات لم تكتمل ⇒ يعيد WorkManager الجدولة بتراجع أُسّي.
 *
 * فحص الاشتراكات (تفعيل مجدول/انتهاء صلاحية/تجديد تلقائي/إعادة أمر عالق) يعيش هنا عمداً: العامل الدوري الوحيد في
 * المشروع، فلا جدولة موازية. دقته بدقة الدورة (15 دقيقة كحد أدنى لـ WorkManager الدوري) وبشرط اتصال إنترنت.
 */
@HiltWorker
class OfflineSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val processor: OfflineCommandProcessor,
    private val subscriptionEngine: SubscriptionEngine,
    private val auth: ps.mgtik.hotspot.domain.repository.IAuthRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // بلا جلسة سحابية لا تُنفَّذ الأوامر: كانت ستُرفض بصلاحيات وتُعلَّم فاشلة نهائياً فتضيع. تبقى محفوظة، ويجدول الدخولُ التالي دورةً جديدة.
        if (auth.getCurrentUser() == null) return Result.success()
        val summary = try {
            processor.processPending()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // خطأ خارج تنفيذ الأوامر نفسها (مثلاً قراءة الطابور من Room): الأوامر المحفوظة لا تضيع، فنعيد المحاولة بتراجع،
            // وبعد المحاولات الأخيرة نفشل العامل بدل حلقة لا تنتهي (الدورة الدورية كل 15 دقيقة تتابع).
            return if (runAttemptCount >= MAX_UNEXPECTED_ATTEMPTS) Result.failure() else Result.retry()
        }
        // فحص الاشتراكات مستقل عن نتيجة الطابور: فشله لا يُسقط الأوامر المنفَّذة، وبقاء أوامر راوتر عالقة يعيد الجدولة
        val subscriptionsPending = try {
            subscriptionEngine.sweep().stillPending > 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            true
        }
        return if (summary.retryLater > 0 || subscriptionsPending) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_UNEXPECTED_ATTEMPTS = 5
    }
}

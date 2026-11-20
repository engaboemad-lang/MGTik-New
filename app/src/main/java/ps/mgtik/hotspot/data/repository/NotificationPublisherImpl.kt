package ps.mgtik.hotspot.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.model.MgtikNotification
import ps.mgtik.hotspot.domain.model.NotificationCategory
import ps.mgtik.hotspot.domain.model.NotificationTarget
import ps.mgtik.hotspot.domain.repository.INotificationPublisher
import ps.mgtik.hotspot.domain.repository.INotificationRepository
import ps.mgtik.hotspot.data.notification.SystemNotifier
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NotificationPublisherImpl - يبني نص الإشعار بلغة الجهاز وقت الحدث ويكتبه عبر [INotificationRepository].
 * أي فشل في الكتابة (شبكة/جلسة) يُتجاهل عمداً: الإشعار ثانوي ولا يُفشل الدفع أو التوليد.
 */
@Singleton
class NotificationPublisherImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: INotificationRepository,
    private val systemNotifier: SystemNotifier,
    private val prefs: ps.mgtik.hotspot.data.local.prefs.AppPreferences
) : INotificationPublisher {

    override suspend fun paymentRecorded(paymentId: String, invoiceId: String, amountMinor: Long) {
        if (!prefs.notifyBilling.value) return // إعداد المستخدم: إشعارات الفواتير والمدفوعات
        val amount = ps.mgtik.hotspot.core.util.formatMinor(amountMinor)
        publish(
            id = "PAY-$paymentId",
            category = NotificationCategory.BILLING,
            title = context.getString(R.string.notif_payment_title),
            message = context.getString(R.string.notif_payment_message, amount, invoiceId),
            targetType = NotificationTarget.INVOICE,
            targetId = invoiceId
        )
    }

    override suspend fun generationCompleted(executionId: String, partial: Boolean, orderId: String?) {
        if (!prefs.notifyGeneration.value) return
        publish(
            id = "GEN-$executionId",
            category = NotificationCategory.SYSTEM,
            title = context.getString(
                if (partial) R.string.notif_gen_partial_title else R.string.notif_gen_done_title
            ),
            message = context.getString(
                if (partial) R.string.notif_gen_partial_message else R.string.notif_gen_done_message
            ),
            targetType = orderId?.let { NotificationTarget.ORDER },
            targetId = orderId
        )
    }

    override suspend fun generationFailed(executionId: String, reason: String, orderId: String?) {
        if (!prefs.notifyGeneration.value) return
        publish(
            id = "GEN-$executionId",
            category = NotificationCategory.SYSTEM,
            title = context.getString(R.string.notif_gen_failed_title),
            message = context.getString(R.string.notif_gen_failed_message, reason),
            targetType = orderId?.let { NotificationTarget.ORDER },
            targetId = orderId
        )
    }

    override suspend fun queueCommandFailed(commandId: String, reason: String) {
        publish(
            id = "QCF-$commandId",
            category = NotificationCategory.SYSTEM,
            title = context.getString(R.string.notif_queue_failed_title),
            message = context.getString(R.string.notif_queue_failed_message, reason),
            targetType = NotificationTarget.OFFLINE_QUEUE,
            targetId = "queue"
        )
    }

    private suspend fun publish(
        id: String,
        category: NotificationCategory,
        title: String,
        message: String,
        targetType: String? = null,
        targetId: String? = null
    ) {
        // إشعار النظام أولاً ومستقلاً عن السحابة: يظهر حتى لو كان الفشل بسبب انقطاع الاتصال
        systemNotifier.show(id, title, message, targetType, targetId)
        try {
            repository.addNotification(
                MgtikNotification(
                    id = id,
                    title = title,
                    message = message,
                    category = category,
                    isRead = false,
                    timestamp = System.currentTimeMillis(),
                    targetType = targetType,
                    targetId = targetId
                )
            )
        } catch (e: CancellationException) {
            throw e // runCatching كان يبتلع إلغاء الكوروتين أيضاً فيستمر العمل بعد إيقاف العامل
        } catch (e: Exception) {
            // الإشعار ثانوي: فشله لا يفشّل الدفع أو التوليد
        }
    }
}

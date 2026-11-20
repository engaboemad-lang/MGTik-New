package ps.mgtik.hotspot.data.notification

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import ps.mgtik.hotspot.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SystemNotifier - إشعار نظام محلي (شريط الإشعارات) لأحداث التطبيق، يُرسَل من داخل التطبيق/Workers مباشرة
 * (لا FCM ولا خادم). يعمل والتطبيق مغلق أو في الخلفية ما دامت العملية/العامل يعمل، وبنقرة يفتح الوجهة.
 * المعرّف حتمي (من معرّف الحدث) فإعادة محاولة العامل تحدّث الإشعار نفسه ولا تكرّره.
 * لا يرمي أبداً: الإشعار ثانوي ولا يُفشل العملية الأصلية.
 */
@Singleton
class SystemNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @SuppressLint("MissingPermission") // areNotificationsEnabled() يغطي إذن POST_NOTIFICATIONS (Android 13+)
    fun show(eventId: String, title: String, message: String, targetType: String?, targetId: String?) {
        try {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            ensureChannel()
            val launch = NotificationIntents.openNotification(context, targetType, targetId) ?: return
            val pending = PendingIntent.getActivity(
                context, eventId.hashCode(), launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            manager.notify(eventId.hashCode(), notification)
        } catch (e: SecurityException) {
            // الإذن سُحب أثناء العمل: يُتجاهل
        } catch (e: RuntimeException) {
            // أي عطل في النظام لا يجوز أن يفشّل العملية الأصلية
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_alerts), NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private companion object {
        const val CHANNEL_ID = "mgtik_alerts"
    }
}

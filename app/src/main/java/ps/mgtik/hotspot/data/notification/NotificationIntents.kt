package ps.mgtik.hotspot.data.notification

import android.content.Context
import android.content.Intent

/**
 * NotificationIntents - عقد فتح التطبيق من إشعار النظام (يعمل والتطبيق مغلقاً أو في الخلفية).
 * Intent صريح لنشاط الإطلاق + Action/Extras؛ MainActivity تقرؤها في onCreate (إقلاع بارد) وonNewIntent (التطبيق حيّ).
 */
object NotificationIntents {
    const val ACTION_OPEN_NOTIFICATION = "ps.mgtik.hotspot.action.OPEN_NOTIFICATION"
    const val EXTRA_TARGET_TYPE = "ps.mgtik.hotspot.extra.TARGET_TYPE"
    const val EXTRA_TARGET_ID = "ps.mgtik.hotspot.extra.TARGET_ID"

    /** فتح التطبيق فقط (إشعار التوليد الجاري). null إن تعذّر إيجاد نشاط الإطلاق. */
    fun plainLaunch(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)

    /** فتح مركز الإشعارات ثم الوجهة [targetType]/[targetId] إن وُجدت. */
    fun openNotification(context: Context, targetType: String?, targetId: String?): Intent? =
        plainLaunch(context)?.apply {
            action = ACTION_OPEN_NOTIFICATION
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            targetType?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_TARGET_TYPE, it) }
            targetId?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_TARGET_ID, it) }
        }
}

/** طلب فتح قادم من إشعار نظام. */
data class NotificationLaunch(val targetType: String?, val targetId: String?) {
    companion object {
        fun from(intent: Intent?): NotificationLaunch? =
            if (intent?.action == NotificationIntents.ACTION_OPEN_NOTIFICATION)
                NotificationLaunch(
                    intent.getStringExtra(NotificationIntents.EXTRA_TARGET_TYPE),
                    intent.getStringExtra(NotificationIntents.EXTRA_TARGET_ID)
                )
            else null
    }
}

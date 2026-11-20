package ps.mgtik.hotspot.data.notification

import ps.mgtik.hotspot.domain.model.RouterConnectionState
import ps.mgtik.hotspot.domain.model.RouterStatus

/**
 * ما يجب أن يعرضه الإشعار الدائم لحالة الاتصال (المهمة 3.4). نقي بالكامل: القرار من (الخيار، ظهور التطبيق، RouterStatus).
 * المساواة بين الخطط هي أساس منع التكرار: خطة مطابقة لما هو معروض لا تُعاد نشرها، فنبضة فاشلة لا تنتج إشعاراً جديداً.
 */
sealed interface ConnectionNotificationPlan {
    /** لا إشعار (يُزال إن كان معروضاً). */
    data object None : ConnectionNotificationPlan

    /** حالة وسيطة (فحص/حفظ إعدادات): يبقى المعروض كما هو. */
    data object Keep : ConnectionNotificationPlan

    /** [connectedSinceElapsed] بـ elapsedRealtime؛ يحوّله العارض إلى وقت جدار لـ setWhen. */
    data class Connected(
        val routerOs: String,
        val host: String,
        val connectedSinceElapsed: Long,
        val cpuPercent: Int,
        val freeRamMb: Long
    ) : ConnectionNotificationPlan

    data class Reconnecting(val host: String) : ConnectionNotificationPlan

    data class Disconnected(val host: String, val errorCode: String?) : ConnectionNotificationPlan
}

object ConnectionNotificationPlanner {

    /**
     * @param enabled الخيار الصريح في الإعدادات.
     * @param appVisible التطبيق ظاهر: قرار Android 15 (انظر PR) = الإشعار أثناء ظهور التطبيق فقط دون خدمة دائمة.
     */
    fun plan(enabled: Boolean, appVisible: Boolean, status: RouterStatus): ConnectionNotificationPlan {
        if (!enabled || !appVisible) return ConnectionNotificationPlan.None
        // وضع عدم الاتصال/تسجيل الخروج/غير مُهيّأ: يُزال الإشعار
        if (status.offlineMode || status.loggedOut || status.state == RouterConnectionState.NOT_CONFIGURED) {
            return ConnectionNotificationPlan.None
        }
        return when (status.state) {
            RouterConnectionState.CONNECTED -> {
                val since = status.connectedSince ?: return ConnectionNotificationPlan.Keep
                ConnectionNotificationPlan.Connected(
                    routerOs = status.info?.osMajor.orEmpty(),
                    host = status.config.host,
                    connectedSinceElapsed = since,
                    cpuPercent = status.health.cpuLoadPercent,
                    freeRamMb = status.health.freeMemoryMb
                )
            }
            RouterConnectionState.CONNECTING,
            RouterConnectionState.RECONNECTING -> ConnectionNotificationPlan.Reconnecting(status.config.host)
            RouterConnectionState.DISCONNECTED,
            RouterConnectionState.CONNECTION_FAILED ->
                ConnectionNotificationPlan.Disconnected(status.config.host, status.errorCode)
            RouterConnectionState.CONFIGURING -> ConnectionNotificationPlan.Keep
            RouterConnectionState.NOT_CONFIGURED -> ConnectionNotificationPlan.None
        }
    }
}

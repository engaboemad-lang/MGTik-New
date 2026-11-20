package ps.mgtik.hotspot.ui.components.router

import ps.mgtik.hotspot.domain.model.LinkQualityLevel
import ps.mgtik.hotspot.domain.model.RouterConnectionState
import ps.mgtik.hotspot.domain.model.RouterInterruption
import ps.mgtik.hotspot.domain.model.RouterStatus

/** مدة مجزأة للعرض («2س 14د»)؛ التنسيق النصي يتبع لغة الواجهة في طبقة Compose. */
data class DurationParts(val days: Long, val hours: Long, val minutes: Long) {
    val isUnderMinute: Boolean get() = days == 0L && hours == 0L && minutes == 0L

    companion object {
        fun of(millis: Long): DurationParts {
            val totalMinutes = (millis.coerceAtLeast(0L)) / 60_000L
            return DurationParts(totalMinutes / (24 * 60), (totalMinutes / 60) % 24, totalMinutes % 60)
        }
    }
}

/** إشعارات الحالة المعروضة في الشريط الموحّد (رسالة واحدة لكل نوع، لا تكرار). */
enum class ConnectionNotice { UNSTABLE, ROUTER_REBOOTED, NETWORK_LOST, OFF_ROUTER_NETWORK, SLOW, INTERMITTENT }

data class ReconnectInfo(val attempt: Int, val retryInSeconds: Long?)

/** ما يعرضه شريط الاتصال الموحّد وشاشة التشخيص؛ مصدره RouterStatus نفسه الذي يبني منه الإشعار الدائم. */
data class ConnectionPresentation(
    val state: RouterConnectionState,
    val connectedFor: DurationParts?,
    val lastOutageAgo: DurationParts?,
    val reconnect: ReconnectInfo?,
    val notices: List<ConnectionNotice>,
    val quality: LinkQualityLevel?
)

/**
 * دالة نقية: (حالة الراوتر، الوقت الحالي elapsedRealtime) ← عرض. كل المدد من elapsedRealtime فلا تتأثر بتغيير ساعة الهاتف.
 */
object ConnectionPresenter {

    fun present(status: RouterStatus, nowElapsed: Long): ConnectionPresentation {
        val connected = status.state == RouterConnectionState.CONNECTED
        val connectedFor = if (connected) status.connectedSince?.let { DurationParts.of(nowElapsed - it) } else null
        val lastOutage = status.lastDisconnectAt?.let { DurationParts.of(nowElapsed - it) }
        val reconnecting = status.state == RouterConnectionState.RECONNECTING && status.reconnectAttempt > 0
        val reconnect = if (reconnecting) {
            ReconnectInfo(status.reconnectAttempt, status.nextRetryAt?.let { ((it - nowElapsed).coerceAtLeast(0L) + 999L) / 1000L })
        } else null

        val notices = buildList {
            if (status.unstable) add(ConnectionNotice.UNSTABLE)
            if (!connected) when (status.interruption) {
                RouterInterruption.ROUTER_REBOOTED -> add(ConnectionNotice.ROUTER_REBOOTED)
                RouterInterruption.NETWORK_LOST -> add(ConnectionNotice.NETWORK_LOST)
                RouterInterruption.OFF_ROUTER_NETWORK -> add(ConnectionNotice.OFF_ROUTER_NETWORK)
                RouterInterruption.ROUTER_UNREACHABLE, null -> Unit
            }
            if (connected) when (status.quality) {
                LinkQualityLevel.SLOW -> add(ConnectionNotice.SLOW)
                LinkQualityLevel.INTERMITTENT -> add(ConnectionNotice.INTERMITTENT)
                LinkQualityLevel.GOOD, null -> Unit
            }
        }
        return ConnectionPresentation(status.state, connectedFor, lastOutage, reconnect, notices, if (connected) status.quality else null)
    }
}

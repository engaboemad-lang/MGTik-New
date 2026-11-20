package ps.mgtik.hotspot.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.core.network.RouterConfig
import ps.mgtik.hotspot.domain.model.LinkQualityLevel
import ps.mgtik.hotspot.domain.model.RouterConnectionState
import ps.mgtik.hotspot.domain.model.RouterInterruption
import ps.mgtik.hotspot.domain.model.RouterStatus
import ps.mgtik.hotspot.ui.components.router.ConnectionNotice
import ps.mgtik.hotspot.ui.components.router.ConnectionPresenter
import ps.mgtik.hotspot.ui.components.router.DurationParts

/** عرض مدة الاتصال وآخر انقطاع وإعادة الاتصال وإشعارات السبب والجودة (شريط الاتصال الموحّد). */
class ConnectionPresenterTest {

    private val cfg = RouterConfig(host = "192.168.88.1")
    private val minute = 60_000L
    private val hour = 60 * minute

    @Test fun durationPartsExample_2h14m() {
        val d = DurationParts.of(2 * hour + 14 * minute + 20_000)
        assertEquals(0L, d.days); assertEquals(2L, d.hours); assertEquals(14L, d.minutes)
    }

    @Test fun durationUnderMinuteAndDays() {
        assertTrue(DurationParts.of(45_000).isUnderMinute)
        val d = DurationParts.of(26 * hour + 5 * minute)
        assertEquals(1L, d.days); assertEquals(2L, d.hours); assertEquals(5L, d.minutes)
    }

    @Test fun negativeElapsedClampsToZero() = assertTrue(DurationParts.of(-5).isUnderMinute)

    @Test fun connectedShowsSinceAndLastOutage() {
        val s = RouterStatus(state = RouterConnectionState.CONNECTED, config = cfg, connectedSince = 1_000, lastDisconnectAt = 500)
        val p = ConnectionPresenter.present(s, nowElapsed = 1_000 + 2 * hour + 14 * minute)
        assertEquals(2L, p.connectedFor!!.hours)
        assertEquals(14L, p.connectedFor!!.minutes)
        assertEquals(2L, p.lastOutageAgo!!.hours)
    }

    @Test fun disconnectedHasNoConnectedDuration() {
        val s = RouterStatus(state = RouterConnectionState.DISCONNECTED, config = cfg, lastDisconnectAt = 0)
        val p = ConnectionPresenter.present(s, nowElapsed = 3 * hour)
        assertNull(p.connectedFor)
        assertEquals(3L, p.lastOutageAgo!!.hours)
    }

    @Test fun reconnectCountdownRoundsUp() {
        val s = RouterStatus(state = RouterConnectionState.RECONNECTING, config = cfg, reconnectAttempt = 3, nextRetryAt = 10_500)
        val p = ConnectionPresenter.present(s, nowElapsed = 5_000)
        assertEquals(3, p.reconnect!!.attempt)
        assertEquals(6L, p.reconnect!!.retryInSeconds)
    }

    @Test fun noReconnectInfoBeforeFirstAttempt() {
        val s = RouterStatus(state = RouterConnectionState.RECONNECTING, config = cfg)
        assertNull(ConnectionPresenter.present(s, 0).reconnect)
    }

    @Test fun unstableIsASingleNotice() {
        val s = RouterStatus(state = RouterConnectionState.RECONNECTING, config = cfg, unstable = true)
        assertEquals(listOf(ConnectionNotice.UNSTABLE), ConnectionPresenter.present(s, 0).notices)
    }

    @Test fun interruptionReasonsAreDistinct() {
        fun notices(r: RouterInterruption) = ConnectionPresenter.present(
            RouterStatus(state = RouterConnectionState.RECONNECTING, config = cfg, interruption = r), 0
        ).notices
        assertEquals(listOf(ConnectionNotice.NETWORK_LOST), notices(RouterInterruption.NETWORK_LOST))
        assertEquals(listOf(ConnectionNotice.ROUTER_REBOOTED), notices(RouterInterruption.ROUTER_REBOOTED))
        assertEquals(listOf(ConnectionNotice.OFF_ROUTER_NETWORK), notices(RouterInterruption.OFF_ROUTER_NETWORK))
        assertTrue(notices(RouterInterruption.ROUTER_UNREACHABLE).isEmpty())
    }

    @Test fun qualityNoticesOnlyWhileConnected() {
        val slow = RouterStatus(state = RouterConnectionState.CONNECTED, config = cfg, connectedSince = 0, quality = LinkQualityLevel.SLOW)
        assertEquals(listOf(ConnectionNotice.SLOW), ConnectionPresenter.present(slow, 0).notices)
        val good = slow.copy(quality = LinkQualityLevel.GOOD)
        assertTrue(ConnectionPresenter.present(good, 0).notices.isEmpty())
        assertEquals(LinkQualityLevel.GOOD, ConnectionPresenter.present(good, 0).quality)
        val stale = slow.copy(state = RouterConnectionState.RECONNECTING, connectedSince = null)
        assertNull(ConnectionPresenter.present(stale, 0).quality)
        assertFalse(ConnectionPresenter.present(stale, 0).notices.contains(ConnectionNotice.SLOW))
    }

    @Test fun staleInterruptionNotShownOnceConnected() {
        val s = RouterStatus(state = RouterConnectionState.CONNECTED, config = cfg, connectedSince = 0, interruption = RouterInterruption.NETWORK_LOST)
        assertTrue(ConnectionPresenter.present(s, 0).notices.isEmpty())
    }
}

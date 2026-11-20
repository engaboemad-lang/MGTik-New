package ps.mgtik.hotspot.data.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.core.network.RouterConfig
import ps.mgtik.hotspot.domain.model.RouterConnectionState
import ps.mgtik.hotspot.domain.model.RouterHealth
import ps.mgtik.hotspot.domain.model.RouterStatus

/** المهمة 3.4: تحديث الإشعار عند تغير الحالة، وعدم تكراره لكل نبضة فاشلة. */
class ConnectionNotificationPlannerTest {

    private val cfg = RouterConfig(host = "192.168.88.1", username = "admin", password = "x")

    private fun status(
        state: RouterConnectionState,
        since: Long? = null,
        err: String? = null,
        offline: Boolean = false,
        loggedOut: Boolean = false,
        cpu: Int = 10
    ) = RouterStatus(
        state = state, config = cfg, errorCode = err, offlineMode = offline, loggedOut = loggedOut,
        connectedSince = since, health = RouterHealth(cpuLoadPercent = cpu, freeMemoryMb = 300)
    )

    private fun plan(s: RouterStatus, enabled: Boolean = true, visible: Boolean = true) =
        ConnectionNotificationPlanner.plan(enabled, visible, s)

    @Test fun disabledOptionShowsNothing() =
        assertEquals(ConnectionNotificationPlan.None, plan(status(RouterConnectionState.CONNECTED, since = 1), enabled = false))

    @Test fun notShownWhileAppInBackground_noForegroundService() =
        assertEquals(ConnectionNotificationPlan.None, plan(status(RouterConnectionState.CONNECTED, since = 1), visible = false))

    @Test fun connectedShowsChronometerBaseAndStats() {
        val p = plan(status(RouterConnectionState.CONNECTED, since = 5_000, cpu = 12))
        assertTrue(p is ConnectionNotificationPlan.Connected)
        p as ConnectionNotificationPlan.Connected
        assertEquals(5_000L, p.connectedSinceElapsed)
        assertEquals(12, p.cpuPercent)
        assertEquals("192.168.88.1", p.host)
    }

    @Test fun reconnectingState() =
        assertEquals(ConnectionNotificationPlan.Reconnecting("192.168.88.1"), plan(status(RouterConnectionState.RECONNECTING)))

    @Test fun disconnectedState() =
        assertEquals(
            ConnectionNotificationPlan.Disconnected("192.168.88.1", "ERR_TIMEOUT"),
            plan(status(RouterConnectionState.DISCONNECTED, err = "ERR_TIMEOUT"))
        )

    @Test fun removedOnRouterLogoutAndOfflineMode() {
        assertEquals(ConnectionNotificationPlan.None, plan(status(RouterConnectionState.DISCONNECTED, loggedOut = true)))
        assertEquals(ConnectionNotificationPlan.None, plan(status(RouterConnectionState.DISCONNECTED, offline = true)))
    }

    @Test fun notConfiguredShowsNothing() =
        assertEquals(ConnectionNotificationPlan.None, plan(status(RouterConnectionState.NOT_CONFIGURED)))

    @Test fun configuringKeepsWhatIsShown() =
        assertEquals(ConnectionNotificationPlan.Keep, plan(status(RouterConnectionState.CONFIGURING)))

    @Test fun stateChangesProduceDifferentPlans() {
        val a = plan(status(RouterConnectionState.CONNECTED, since = 1))
        val b = plan(status(RouterConnectionState.RECONNECTING))
        val c = plan(status(RouterConnectionState.DISCONNECTED, err = "E"))
        assertTrue(a != b && b != c && a != c)
    }

    @Test fun repeatedFailedPulsesDoNotChangeThePlan_noDuplicateNotifications() {
        // نبضات فاشلة متتالية أثناء الانقطاع: نفس الحالة والرمز ⇒ خطة مطابقة ⇒ distinctUntilChanged لا ينشر شيئاً
        val plans = (1..5).map { plan(status(RouterConnectionState.DISCONNECTED, err = "ERR_TIMEOUT")) }
        assertEquals(1, plans.toSet().size)
        val reconnecting = (1..5).map { plan(status(RouterConnectionState.RECONNECTING)) }
        assertEquals(1, reconnecting.toSet().size)
    }
}

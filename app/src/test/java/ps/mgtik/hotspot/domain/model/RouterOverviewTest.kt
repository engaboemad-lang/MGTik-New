package ps.mgtik.hotspot.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouterOverviewTest {

    private val host = "192.168.88.1"
    private val info = RouterInfo(name = "RB4011iGS+", ip = host, osMajor = "RouterOS 7.14", uptimeFormatted = "3d04:10:00")
    private val health = RouterHealth(isOnline = true, cpuLoadPercent = 12, freeMemoryMb = 300L, uptimeString = "3d04:10:00")

    private fun status(
        state: RouterConnectionState,
        configLoad: ConfigLoadState = ConfigLoadState.LOADED,
        hostValue: String = host,
        info: RouterInfo? = null,
        health: RouterHealth = RouterHealth()
    ) = RouterStatus(
        state = state,
        config = RouterConfig(host = hostValue),
        configLoad = configLoad,
        info = info,
        health = health
    )

    @Test fun connectedShowsRealMeasurementsAndModel() {
        val o = status(RouterConnectionState.CONNECTED, info = info, health = health).toOverview()
        assertEquals(RouterLinkState.CONNECTED, o.link)
        assertEquals("RB4011iGS+", o.model)
        assertEquals("RouterOS 7.14", o.osVersion)
        assertEquals(host, o.host)
        assertEquals(12, o.cpuLoadPercent)
        assertEquals(300L, o.freeMemoryMb)
    }

    @Test fun modelIsNeverUptimeOrAddress() {
        val o = status(RouterConnectionState.CONNECTED, info = info, health = health).toOverview()
        assertNotEquals(health.uptimeString, o.model)
        assertNotEquals(host, o.model)
    }

    @Test fun realZeroCpuWhileConnectedStaysZeroNotUnavailable() {
        val o = status(RouterConnectionState.CONNECTED, info = info, health = health.copy(cpuLoadPercent = 0)).toOverview()
        assertEquals(0, o.cpuLoadPercent)
    }

    @Test fun connectedWithoutFirstMeasurementIsUnavailableNotZero() {
        val o = status(RouterConnectionState.CONNECTED, info = info, health = RouterHealth()).toOverview()
        assertEquals(RouterLinkState.CONNECTED, o.link)
        assertNull(o.cpuLoadPercent)
        assertNull(o.freeMemoryMb)
        assertEquals("RB4011iGS+", o.model)
    }

    @Test fun disconnectedHasNoMeasurementsButKeepsConfiguredHost() {
        val o = status(RouterConnectionState.DISCONNECTED).toOverview()
        assertEquals(RouterLinkState.DISCONNECTED, o.link)
        assertNull(o.cpuLoadPercent)
        assertNull(o.freeMemoryMb)
        assertNull(o.model)
        assertNull(o.osVersion)
        assertEquals(host, o.host)
    }

    @Test fun connectingAndReconnectingAreNotReportedAsDisconnected() {
        listOf(RouterConnectionState.CONNECTING, RouterConnectionState.RECONNECTING, RouterConnectionState.CONFIGURING).forEach {
            val o = status(it).toOverview()
            assertEquals(it.name, RouterLinkState.CONNECTING, o.link)
            assertNull(o.cpuLoadPercent)
        }
    }

    @Test fun configStillLoadingIsConnectingNotDisconnected() {
        val o = status(RouterConnectionState.DISCONNECTED, configLoad = ConfigLoadState.LOADING, hostValue = "").toOverview()
        assertEquals(RouterLinkState.CONNECTING, o.link)
    }

    @Test fun noSavedConfigIsNotConfigured() {
        val o = status(RouterConnectionState.NOT_CONFIGURED, hostValue = "").toOverview()
        assertEquals(RouterLinkState.NOT_CONFIGURED, o.link)
        assertEquals("", o.host)
    }

    @Test fun cloudConfigUnavailableIsNotReportedAsNotConfigured() {
        val o = status(RouterConnectionState.DISCONNECTED, configLoad = ConfigLoadState.UNAVAILABLE, hostValue = "").toOverview()
        assertEquals(RouterLinkState.DISCONNECTED, o.link)
    }

    @Test fun failedConnectionIsDisconnected() {
        val o = status(RouterConnectionState.CONNECTION_FAILED).toOverview()
        assertEquals(RouterLinkState.DISCONNECTED, o.link)
    }
}

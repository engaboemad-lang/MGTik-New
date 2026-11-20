package ps.mgtik.hotspot.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.core.result.ApiError

class RouterEndpointTest {

    private val base = RouterConfig(host = "192.168.88.1", password = "p")

    @Test
    fun `fields that do not affect the connection are ignored`() {
        val other = base.copy(isConnected = !base.isConnected, autoSyncIntervalSeconds = 99)
        assertTrue(base.sameEndpoint(other))
    }

    @Test
    fun `any endpoint field change is a different endpoint`() {
        assertFalse(base.sameEndpoint(base.copy(host = "10.0.0.1")))
        assertFalse(base.sameEndpoint(base.copy(port = 443)))
        assertFalse(base.sameEndpoint(base.copy(apiPort = 8729)))
        assertFalse(base.sameEndpoint(base.copy(username = "u")))
        assertFalse(base.sameEndpoint(base.copy(password = "q")))
        assertFalse(base.sameEndpoint(base.copy(useSsl = true)))
        assertFalse(base.sameEndpoint(base.copy(connectionType = RouterConnectionType.API)))
        assertFalse(base.sameEndpoint(null))
    }

    @Test
    fun `connectivity codes are separated from command rejections`() {
        assertTrue(ApiError.isRouterConnectivityCode(ApiError.CODE_TIMEOUT))
        assertTrue(ApiError.isRouterConnectivityCode(ApiError.CODE_NETWORK_UNAVAILABLE))
        assertTrue(ApiError.isRouterConnectivityCode(ApiError.CODE_ROUTER_AUTH_FAILED))
        assertFalse(ApiError.isRouterConnectivityCode(ApiError.CODE_ROUTER_REJECTED))
        assertFalse(ApiError.isRouterConnectivityCode(ApiError.CODE_ROUTER_OFFLINE))
        assertEquals(true, ApiError.routerOffline().retryable)
    }
}

package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterInputValidatorTest {

    @Test
    fun `valid hosts are accepted`() {
        assertTrue(RouterInputValidator.isHostValid("192.168.88.1"))
        assertTrue(RouterInputValidator.isHostValid("10.0.0.254"))
        assertTrue(RouterInputValidator.isHostValid("router.local"))
        assertTrue(RouterInputValidator.isHostValid("my-router.example.com"))
    }

    @Test
    fun `invalid hosts are rejected`() {
        assertFalse(RouterInputValidator.isHostValid(""))
        assertFalse(RouterInputValidator.isHostValid("   "))
        assertFalse(RouterInputValidator.isHostValid("192.168.88.256"))
        assertFalse(RouterInputValidator.isHostValid("192.168.88"))
        assertFalse(RouterInputValidator.isHostValid("http://192.168.88.1"))
        assertFalse(RouterInputValidator.isHostValid("192.168.88.1:80"))
        assertFalse(RouterInputValidator.isHostValid("192.168.88.1/rest"))
        assertFalse(RouterInputValidator.isHostValid("bad host"))
    }

    @Test
    fun `port must be within 1 to 65535`() {
        assertEquals(80, RouterInputValidator.parsePort("80"))
        assertEquals(65535, RouterInputValidator.parsePort("65535"))
        assertNull(RouterInputValidator.parsePort("0"))
        assertNull(RouterInputValidator.parsePort("65536"))
        assertNull(RouterInputValidator.parsePort(""))
        assertNull(RouterInputValidator.parsePort("99999999999"))
    }

    @Test
    fun `binary api ports are flagged`() {
        assertTrue(RouterInputValidator.isBinaryApiPort(8728))
        assertTrue(RouterInputValidator.isBinaryApiPort(8729))
        assertFalse(RouterInputValidator.isBinaryApiPort(80))
        assertFalse(RouterInputValidator.isBinaryApiPort(null))
    }

    @Test
    fun `ssl toggle swaps only default ports`() {
        assertEquals("443", RouterInputValidator.portAfterSslToggle("80", true))
        assertEquals("80", RouterInputValidator.portAfterSslToggle("443", false))
        assertEquals("443", RouterInputValidator.portAfterSslToggle("", true))
        assertEquals("8080", RouterInputValidator.portAfterSslToggle("8080", true))
    }

    @Test
    fun `validate rejects bad host or port and accepts defaults`() {
        assertNull(RouterInputValidator.validate(ps.mgtik.hotspot.core.network.RouterConfig()))
        val bad = RouterInputValidator.validate(ps.mgtik.hotspot.core.network.RouterConfig(host = "", port = 70000))
        assertEquals(setOf("host", "port"), bad!!.fieldErrors!!.keys)
    }

    @Test
    fun `api port is validated unless connection type is REST only`() {
        val auto = ps.mgtik.hotspot.core.network.RouterConfig(apiPort = 0)
        assertEquals(setOf("apiPort"), RouterInputValidator.validate(auto)!!.fieldErrors!!.keys)
        val restOnly = auto.copy(connectionType = ps.mgtik.hotspot.core.network.RouterConnectionType.REST)
        assertNull(RouterInputValidator.validate(restOnly))
    }

    @Test
    fun `rest port is not validated for API only (RouterOS 6)`() {
        val v6 = ps.mgtik.hotspot.core.network.RouterConfig(
            port = 0, connectionType = ps.mgtik.hotspot.core.network.RouterConnectionType.API
        )
        assertNull(RouterInputValidator.validate(v6))
    }

    @Test
    fun `api ssl toggle swaps only default api ports`() {
        assertEquals("8729", RouterInputValidator.apiPortAfterSslToggle("8728", true))
        assertEquals("8728", RouterInputValidator.apiPortAfterSslToggle("8729", false))
        assertEquals("8729", RouterInputValidator.apiPortAfterSslToggle("", true))
        assertEquals("9000", RouterInputValidator.apiPortAfterSslToggle("9000", true))
    }
}

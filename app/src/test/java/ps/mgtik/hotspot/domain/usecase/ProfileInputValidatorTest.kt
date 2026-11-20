package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileInputValidatorTest {

    @Test
    fun `router name accepts letters digits dash underscore only`() {
        assertTrue(ProfileInputValidator.isRouterNameValid("Gold_10-M"))
        assertFalse(ProfileInputValidator.isRouterNameValid(""))
        assertFalse(ProfileInputValidator.isRouterNameValid("  "))
        assertFalse(ProfileInputValidator.isRouterNameValid("my profile"))
        assertFalse(ProfileInputValidator.isRouterNameValid("a/b"))
        assertFalse(ProfileInputValidator.isRouterNameValid("a.b"))
    }

    @Test
    fun `price converts to minor units without floating point error`() {
        assertEquals(1999L, ProfileInputValidator.parsePriceMinor("19.99"))
        assertEquals(1250L, ProfileInputValidator.parsePriceMinor("12.5"))
        assertEquals(500L, ProfileInputValidator.parsePriceMinor("5"))
        assertEquals(0L, ProfileInputValidator.parsePriceMinor("0"))
        assertEquals(101L, ProfileInputValidator.parsePriceMinor("1.005"))
    }

    @Test
    fun `invalid prices are rejected`() {
        assertNull(ProfileInputValidator.parsePriceMinor(""))
        assertNull(ProfileInputValidator.parsePriceMinor("1.2.3"))
        assertNull(ProfileInputValidator.parsePriceMinor("abc"))
        assertNull(ProfileInputValidator.parsePriceMinor("-5"))
        assertNull(ProfileInputValidator.parsePriceMinor("99999999999"))
    }

    @Test
    fun `rate limit parses RouterOS decimal units`() {
        assertEquals(ProfileInputValidator.RateLimit(10_000, 10_000), ProfileInputValidator.parseRateLimit("10M/10M"))
        assertEquals(ProfileInputValidator.RateLimit(512, 1_000), ProfileInputValidator.parseRateLimit("512k/1M"))
        assertEquals(ProfileInputValidator.RateLimit(8_000, 8_000), ProfileInputValidator.parseRateLimit("8m"))
        assertEquals(ProfileInputValidator.RateLimit(2_000, 2_000), ProfileInputValidator.parseRateLimit("2000000"))
    }

    @Test
    fun `blank rate limit means unlimited and garbage is rejected`() {
        assertTrue(ProfileInputValidator.parseRateLimit("")!!.isUnlimited)
        assertNull(ProfileInputValidator.parseRateLimit("fast"))
        assertNull(ProfileInputValidator.parseRateLimit("10M/10M/10M"))
        assertNull(ProfileInputValidator.parseRateLimit("10X/10M"))
        assertNull(ProfileInputValidator.parseRateLimit("99999G"))
    }

    @Test
    fun `rate limit formats back to RouterOS syntax`() {
        assertEquals("10M/10M", ProfileInputValidator.formatRateLimit(10_000, 10_000))
        assertEquals("512k/1M", ProfileInputValidator.formatRateLimit(512, 1_000))
        assertNull(ProfileInputValidator.formatRateLimit(0, 0))
    }
}

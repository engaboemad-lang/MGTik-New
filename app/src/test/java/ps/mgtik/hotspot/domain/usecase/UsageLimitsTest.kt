package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ps.mgtik.hotspot.domain.usecase.UsageLimits.Parsed

class UsageLimitsTest {

    @Test
    fun `empty input means unspecified, never zero`() {
        assertEquals(Parsed.Unspecified, UsageLimits.parseUptime("", UptimeUnit.HOURS))
        assertEquals(Parsed.Unspecified, UsageLimits.parseUptime("   ", UptimeUnit.DAYS))
        assertEquals(Parsed.Unspecified, UsageLimits.parseTransfer("", TransferUnit.GB))
    }

    @Test
    fun `zero and negative and non numeric are invalid (RouterOS reads 0 as unlimited)`() {
        for (raw in listOf("0", "00", "-1", "abc", "1.5")) {
            assertEquals("uptime '$raw'", Parsed.Invalid, UsageLimits.parseUptime(raw, UptimeUnit.HOURS))
        }
        for (raw in listOf("0", "0.0", "-2", "x", "1..2")) {
            assertEquals("transfer '$raw'", Parsed.Invalid, UsageLimits.parseTransfer(raw, TransferUnit.MB))
        }
    }

    @Test
    fun `uptime converts to seconds and rejects overflow`() {
        assertEquals(Parsed.Value(1_800L), UsageLimits.parseUptime("30", UptimeUnit.MINUTES))
        assertEquals(Parsed.Value(7_200L), UsageLimits.parseUptime("2", UptimeUnit.HOURS))
        assertEquals(Parsed.Value(30L * 86_400), UsageLimits.parseUptime("30", UptimeUnit.DAYS))
        assertEquals(Parsed.Value(UsageLimits.MAX_UPTIME_SECONDS / 60 * 60), UsageLimits.parseUptime((UsageLimits.MAX_UPTIME_SECONDS / 60).toString(), UptimeUnit.MINUTES))
        assertEquals(Parsed.Invalid, UsageLimits.parseUptime("99999999999", UptimeUnit.DAYS))
        assertEquals(Parsed.Invalid, UsageLimits.parseUptime("9223372036854775807", UptimeUnit.DAYS)) // overflow آمن
    }

    @Test
    fun `transfer converts to bytes using binary units and truncates fractions`() {
        assertEquals(Parsed.Value(1_048_576L), UsageLimits.parseTransfer("1", TransferUnit.MB))
        assertEquals(Parsed.Value(1_073_741_824L), UsageLimits.parseTransfer("1", TransferUnit.GB))
        assertEquals(Parsed.Value(1_610_612_736L), UsageLimits.parseTransfer("1.5", TransferUnit.GB))
        assertEquals(Parsed.Value(524_288L), UsageLimits.parseTransfer("0.5", TransferUnit.MB))
        assertEquals(Parsed.Invalid, UsageLimits.parseTransfer("0.0000001", TransferUnit.MB)) // أقل من بايت ⇒ 0 ⇒ مرفوض
        assertEquals(Parsed.Invalid, UsageLimits.parseTransfer("99999999999", TransferUnit.GB)) // فوق السقف
    }

    @Test
    fun `validity predicates treat null as valid and zero as invalid`() {
        assertTrue(UsageLimits.isUptimeValid(null)); assertTrue(UsageLimits.isTransferValid(null))
        assertFalse(UsageLimits.isUptimeValid(0)); assertFalse(UsageLimits.isTransferValid(0))
        assertFalse(UsageLimits.isUptimeValid(-5)); assertFalse(UsageLimits.isTransferValid(-5))
        assertTrue(UsageLimits.isUptimeValid(1)); assertTrue(UsageLimits.isTransferValid(UsageLimits.MAX_TRANSFER_BYTES))
        assertFalse(UsageLimits.isTransferValid(UsageLimits.MAX_TRANSFER_BYTES + 1))
        assertNull(null as Long?)
    }
}

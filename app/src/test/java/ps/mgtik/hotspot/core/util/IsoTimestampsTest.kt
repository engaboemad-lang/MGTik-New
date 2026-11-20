package ps.mgtik.hotspot.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class IsoTimestampsTest {
    private val jerusalem = ZoneId.of("Asia/Jerusalem")
    private val utc = ZoneId.of("UTC")

    @Test fun boundsConvertLocalMidnightToUtc_winter() {
        // نوفمبر: UTC+2 → منتصف الليل المحلي = 22:00 UTC من اليوم السابق
        assertEquals(
            "2026-11-17T22:00:00.000Z" to "2026-11-18T22:00:00.000Z",
            localDayBoundsIso("2026-11-18", "2026-11-18", jerusalem)
        )
    }

    @Test fun boundsConvertLocalMidnightToUtc_summer() {
        // يوليو: UTC+3
        assertEquals(
            "2026-07-14T21:00:00.000Z" to "2026-07-15T21:00:00.000Z",
            localDayBoundsIso("2026-07-15", "2026-07-15", jerusalem)
        )
    }

    @Test fun paymentAtLocal0030BelongsToLocalDayNotUtcDay() {
        // 00:30 محلي في 18 نوفمبر = 22:30 UTC في 17 نوفمبر
        val iso = "2026-11-17T22:30:00.000Z"
        assertTrue(isWithinDateRange(iso, "2026-11-18", "2026-11-18", jerusalem))
        assertFalse(isWithinDateRange(iso, "2026-11-17", "2026-11-17", jerusalem))
    }

    @Test fun paymentAtLocal2330StaysInSameLocalDay() {
        // 23:30 محلي في 18 نوفمبر = 21:30 UTC في اليوم نفسه
        val iso = "2026-11-18T21:30:00.000Z"
        assertTrue(isWithinDateRange(iso, "2026-11-18", "2026-11-18", jerusalem))
        assertFalse(isWithinDateRange(iso, "2026-11-19", "2026-11-19", jerusalem))
    }

    @Test fun boundaryIsInclusiveStartExclusiveEnd() {
        assertTrue(isWithinDateRange("2026-11-17T22:00:00.000Z", "2026-11-18", "2026-11-18", jerusalem))
        assertFalse(isWithinDateRange("2026-11-18T22:00:00.000Z", "2026-11-18", "2026-11-18", jerusalem))
        assertTrue(isWithinDateRange("2026-11-18T21:59:59.999Z", "2026-11-18", "2026-11-18", jerusalem))
    }

    @Test fun multiDayRangeIsInclusiveOfBothEnds() {
        assertTrue(isWithinDateRange("2026-11-10T00:00:00.000Z", "2026-11-10", "2026-11-12", utc))
        assertTrue(isWithinDateRange("2026-11-12T23:59:59.999Z", "2026-11-10", "2026-11-12", utc))
        assertFalse(isWithinDateRange("2026-11-13T00:00:00.000Z", "2026-11-10", "2026-11-12", utc))
    }

    @Test fun dstTransitionDayStillUsesActualOffsets() {
        // نهاية التوقيت الصيفي في إسرائيل 2026-10-25: اليوم 25 ساعة، وحدّه الأخير UTC+2
        assertEquals(
            "2026-10-24T21:00:00.000Z" to "2026-10-25T22:00:00.000Z",
            localDayBoundsIso("2026-10-25", "2026-10-25", jerusalem)
        )
    }

    @Test fun unreadableInputsAreNeverInRange() {
        assertFalse(isWithinDateRange("", "2026-11-18", "2026-11-18", jerusalem))
        assertFalse(isWithinDateRange("2026-11-18T10:00:00.000Z", "garbage", "2026-11-18", jerusalem))
        assertNull(localDayBoundsIso("2026-11-18", "x", jerusalem))
    }
}

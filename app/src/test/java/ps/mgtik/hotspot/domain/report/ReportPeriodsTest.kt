package ps.mgtik.hotspot.domain.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ReportPeriodsTest {
    private val today = LocalDate.of(2026, 11, 18)

    @Test fun presets() {
        assertEquals("2026-11-12" to "2026-11-18", ReportPeriods.preset(ReportPreset.LAST_7_DAYS, today))
        assertEquals("2026-10-20" to "2026-11-18", ReportPeriods.preset(ReportPreset.LAST_30_DAYS, today))
        assertEquals("2026-11-01" to "2026-11-18", ReportPeriods.preset(ReportPreset.THIS_MONTH, today))
        assertEquals("2026-10-01" to "2026-10-31", ReportPeriods.preset(ReportPreset.LAST_MONTH, today))
        assertEquals("2026-02-01" to "2026-02-28", ReportPeriods.preset(ReportPreset.LAST_MONTH, LocalDate.of(2026, 3, 5)))
        assertEquals("2025-12-01" to "2025-12-31", ReportPeriods.preset(ReportPreset.LAST_MONTH, LocalDate.of(2026, 1, 3)))
    }

    @Test fun previousRangeHasSameLengthAndIsAdjacent() {
        assertEquals("2026-11-05" to "2026-11-11", ReportPeriods.previousRange("2026-11-12", "2026-11-18"))
        assertEquals("2026-11-17" to "2026-11-17", ReportPeriods.previousRange("2026-11-18", "2026-11-18"))
        assertEquals("2026-08-31" to "2026-09-30", ReportPeriods.previousRange("2026-10-01", "2026-10-31"))
    }

    @Test fun previousRangeRejectsBadInput() {
        assertNull(ReportPeriods.previousRange("2026-11-18", "2026-11-12"))
        assertNull(ReportPeriods.previousRange("x", "2026-11-12"))
    }

    @Test fun deltaPercent() {
        assertEquals(50, ReportPeriods.deltaPercent(150, 100))
        assertEquals(-25, ReportPeriods.deltaPercent(75, 100))
        assertNull(ReportPeriods.deltaPercent(10, 0))
    }
}

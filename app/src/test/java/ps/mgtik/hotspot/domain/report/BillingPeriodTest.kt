package ps.mgtik.hotspot.domain.report

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillingPeriodTest {
    @Test fun thisMonthRollsOverAutomaticallyWithToday() {
        val p = BillingPeriod() // الافتراضي
        assertEquals("2026-11-01" to "2026-11-20", p.range(LocalDate.of(2026, 11, 20)))
        assertEquals("2026-12-01" to "2026-12-01", p.range(LocalDate.of(2026, 12, 1)))
    }

    @Test fun lastMonthHandlesYearBoundaryAndLeapFebruary() {
        val p = BillingPeriod(BillingPreset.LAST_MONTH)
        assertEquals("2025-12-01" to "2025-12-31", p.range(LocalDate.of(2026, 1, 15)))
        assertEquals("2024-02-01" to "2024-02-29", p.range(LocalDate.of(2024, 3, 5)))
    }

    @Test fun otherPresetsAndCustom() {
        val d = LocalDate.of(2026, 11, 20)
        assertEquals("2026-11-20" to "2026-11-20", BillingPeriod(BillingPreset.TODAY).range(d))
        assertEquals("2026-10-22" to "2026-11-20", BillingPeriod(BillingPreset.LAST_30_DAYS).range(d))
        assertNull(BillingPeriod(BillingPreset.ALL_TIME).range(d))
        assertEquals("2026-01-01" to "2026-01-31", BillingPeriod(null, "2026-01-01" to "2026-01-31").range(d))
    }
}

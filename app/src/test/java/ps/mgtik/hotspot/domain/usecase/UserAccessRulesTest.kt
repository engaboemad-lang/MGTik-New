package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.model.RouterUserSnapshot
import java.time.Instant

class UserAccessRulesTest {
    private val now = Instant.parse("2026-11-10T10:00:00Z")
    private val future = now.plusSeconds(3600)
    private val past = now.minusSeconds(1)

    @Test fun healthy_user_can_use() {
        val s = RouterUserSnapshot(limitUptimeSeconds = 100, usedUptimeSeconds = 10, limitTransferBytes = 1000, usedBytes = 10)
        assertTrue(UserAccessRules.canUse(s, future, now)); assertNull(UserAccessRules.primary(UserAccessRules.blocks(s, future, now)))
    }
    @Test fun time_expiry_is_distinct_from_quota_exhaustion() {
        val s = RouterUserSnapshot(limitUptimeSeconds = 100, usedUptimeSeconds = 10)
        assertEquals(setOf(AccessBlock.TIME_EXPIRED), UserAccessRules.blocks(s, past, now))
        assertEquals(setOf(AccessBlock.TIME_EXPIRED), UserAccessRules.blocks(s, now, now)) // لحظة الانتهاء نفسها منتهية
    }
    @Test fun uptime_and_transfer_exhaustion_are_separate_blocks() {
        assertEquals(setOf(AccessBlock.UPTIME_EXHAUSTED), UserAccessRules.blocks(RouterUserSnapshot(limitUptimeSeconds = 100, usedUptimeSeconds = 100, limitTransferBytes = 5, usedBytes = 1), future, now))
        assertEquals(setOf(AccessBlock.TRANSFER_EXHAUSTED), UserAccessRules.blocks(RouterUserSnapshot(limitUptimeSeconds = 100, usedUptimeSeconds = 1, limitTransferBytes = 5, usedBytes = 9), future, now))
    }
    @Test fun disabled_has_priority_and_blocks_can_combine() {
        val s = RouterUserSnapshot(disabled = true, limitTransferBytes = 5, usedBytes = 5)
        val b = UserAccessRules.blocks(s, past, now)
        assertEquals(setOf(AccessBlock.DISABLED, AccessBlock.TIME_EXPIRED, AccessBlock.TRANSFER_EXHAUSTED), b)
        assertEquals(AccessBlock.DISABLED, UserAccessRules.primary(b))
    }
    @Test fun unknown_usage_or_no_limit_is_never_treated_as_exhausted() {
        assertTrue(UserAccessRules.canUse(RouterUserSnapshot(limitUptimeSeconds = 100, usedUptimeSeconds = null), null, now))
        assertTrue(UserAccessRules.canUse(RouterUserSnapshot(limitUptimeSeconds = null, usedUptimeSeconds = 99999), null, now))
    }
    @Test fun historical_totals_do_not_count_against_the_current_period() {
        val s = RouterUserSnapshot(limitTransferBytes = 100, usedBytes = 10, totalBytes = 10_000)
        assertFalse(AccessBlock.TRANSFER_EXHAUSTED in UserAccessRules.blocks(s, future, now))
    }
    @Test fun remaining_never_negative() {
        val s = RouterUserSnapshot(limitUptimeSeconds = 100, usedUptimeSeconds = 150, limitTransferBytes = 10, usedBytes = 3)
        assertEquals(0L, UserAccessRules.remainingUptimeSeconds(s)); assertEquals(7L, UserAccessRules.remainingBytes(s))
        assertNull(UserAccessRules.remainingBytes(RouterUserSnapshot()))
    }

    @Test fun download_and_upload_limits_are_separate_blocks() {
        val dl = RouterUserSnapshot(limitDownloadBytes = 100, usedDownloadBytes = 100, limitUploadBytes = 100, usedUploadBytes = 5)
        assertEquals(setOf(AccessBlock.DOWNLOAD_EXHAUSTED), UserAccessRules.blocks(dl, future, now))
        val ul = RouterUserSnapshot(limitDownloadBytes = 100, usedDownloadBytes = 5, limitUploadBytes = 100, usedUploadBytes = 101)
        assertEquals(setOf(AccessBlock.UPLOAD_EXHAUSTED), UserAccessRules.blocks(ul, future, now))
        assertEquals(AccessBlock.DOWNLOAD_EXHAUSTED, UserAccessRules.primary(UserAccessRules.blocks(dl.copy(usedUploadBytes = 500), future, now)))
    }
    @Test fun unknown_or_unlimited_direction_never_blocks_and_remaining_is_clamped() {
        assertTrue(UserAccessRules.canUse(RouterUserSnapshot(usedDownloadBytes = 999, usedUploadBytes = 999), future, now))
        assertTrue(UserAccessRules.canUse(RouterUserSnapshot(limitDownloadBytes = 10), future, now))
        val s = RouterUserSnapshot(limitDownloadBytes = 100, usedDownloadBytes = 30, limitUploadBytes = 10, usedUploadBytes = 50)
        assertEquals(70L, UserAccessRules.remainingDownloadBytes(s)); assertEquals(0L, UserAccessRules.remainingUploadBytes(s))
        assertNull(UserAccessRules.remainingDownloadBytes(RouterUserSnapshot()))
    }
}

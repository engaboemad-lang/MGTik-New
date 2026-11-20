package ps.mgtik.hotspot.domain.usecase

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.LiveSession
import ps.mgtik.hotspot.domain.model.SessionChanges
import ps.mgtik.hotspot.domain.model.SessionEndReason
import ps.mgtik.hotspot.domain.model.TrackedSession
import ps.mgtik.hotspot.domain.model.UserSessionStats
import ps.mgtik.hotspot.domain.repository.ISessionHistoryRepository

class SessionAccountingTest {

    private val t0 = 1_000_000_000_000L

    private fun live(
        user: String = "u1", mac: String = "AA:BB:CC:00:00:01",
        uptime: Long? = 100, up: Long = 1_000, down: Long = 5_000, ip: String = "10.0.0.2"
    ) = LiveSession("*1", user, ip, mac, "p1", uptime, up, down)

    private fun key(user: String = "u1", mac: String = "AA:BB:CC:00:00:01") =
        SessionAccounting.matchKey(user, mac)

    @Test fun new_connection_opens_session_with_estimated_start_and_counters() {
        val r = SessionAccounting.reconcile(emptyMap(), listOf(live(uptime = 100)), t0)
        val s = r.open.getValue(key())
        assertEquals(t0 - 100_000, s.startedAtMs)
        assertFalse(s.startEstimated)
        assertEquals(1_000L, s.uploadBytes)
        assertEquals(5_000L, s.downloadBytes)
        assertEquals(1, r.changes.upserts.size)
        assertTrue(r.changes.closes.isEmpty())
    }

    @Test fun unknown_uptime_marks_start_estimated() {
        val r = SessionAccounting.reconcile(emptyMap(), listOf(live(uptime = null)), t0)
        assertTrue(r.open.getValue(key()).startEstimated)
    }

    @Test fun same_session_adds_only_the_delta_never_double_counts() {
        val first = SessionAccounting.reconcile(emptyMap(), listOf(live(uptime = 100, up = 1_000, down = 5_000)), t0)
        val second = SessionAccounting.reconcile(first.open, listOf(live(uptime = 115, up = 1_500, down = 6_000)), t0 + 15_000)
        // قراءة ثالثة بالأرقام نفسها لا تغيّر شيئاً
        val third = SessionAccounting.reconcile(second.open, listOf(live(uptime = 130, up = 1_500, down = 6_000)), t0 + 30_000)
        val s = third.open.getValue(key())
        assertEquals(1_500L, s.uploadBytes)
        assertEquals(6_000L, s.downloadBytes)
        assertTrue(third.changes.closes.isEmpty())
    }

    @Test fun counter_reset_inside_session_counts_new_reading_fully() {
        val first = SessionAccounting.reconcile(emptyMap(), listOf(live(uptime = 100, up = 10_000, down = 50_000)), t0)
        // العدّاد نزل (تصفير) والجلسة نفسها (uptime متصل): نضيف القراءة الجديدة كاملة لا قيمة سالبة
        val second = SessionAccounting.reconcile(first.open, listOf(live(uptime = 115, up = 300, down = 700)), t0 + 15_000)
        val s = second.open.getValue(key())
        assertEquals(10_300L, s.uploadBytes)
        assertEquals(50_700L, s.downloadBytes)
    }

    @Test fun uptime_going_backwards_means_new_session_old_one_closed_as_reconnected() {
        val first = SessionAccounting.reconcile(emptyMap(), listOf(live(uptime = 600, up = 9_000, down = 9_000)), t0)
        val second = SessionAccounting.reconcile(first.open, listOf(live(uptime = 20, up = 100, down = 200)), t0 + 15_000)
        assertEquals(1, second.changes.closes.size)
        val closed = second.changes.closes.single()
        assertEquals(SessionEndReason.RECONNECTED, closed.endReason)
        assertEquals(9_000L, closed.uploadBytes) // الجلسة القديمة بقيمها الأخيرة
        val fresh = second.open.getValue(key())
        assertEquals(100L, fresh.uploadBytes)
        assertNotEquals(closed.sessionKey, fresh.sessionKey)
    }

    @Test fun router_reboot_with_longer_gap_is_new_session_even_if_uptime_not_lower() {
        // الجلسة القديمة uptime=100 عند t0؛ بعد ساعة ظهرت جلسة uptime=3000 (> القديم) لكن بدايتها المحسوبة مختلفة جداً
        val first = SessionAccounting.reconcile(emptyMap(), listOf(live(uptime = 100)), t0)
        val second = SessionAccounting.reconcile(first.open, listOf(live(uptime = 3_000)), t0 + 3_600_000)
        assertEquals(1, second.changes.closes.size)
        assertEquals(SessionEndReason.RECONNECTED, second.changes.closes.single().endReason)
    }

    @Test fun long_observation_gap_same_session_is_kept_open_when_start_consistent() {
        val first = SessionAccounting.reconcile(emptyMap(), listOf(live(uptime = 100, up = 1_000, down = 1_000)), t0)
        // أُغلق التطبيق ساعة ثم عاد: uptime = 100 + 3600 (متسق) والعدّادات كبرت
        val second = SessionAccounting.reconcile(first.open, listOf(live(uptime = 3_700, up = 4_000, down = 9_000)), t0 + 3_600_000)
        assertTrue(second.changes.closes.isEmpty())
        assertEquals(4_000L, second.open.getValue(key()).uploadBytes)
    }

    @Test fun disappeared_session_is_closed_with_last_observed_values_and_estimated_end_after_gap() {
        val first = SessionAccounting.reconcile(emptyMap(), listOf(live(uptime = 100, up = 2_000, down = 8_000)), t0)
        val closedQuick = SessionAccounting.reconcile(first.open, emptyList(), t0 + 15_000).changes.closes.single()
        assertEquals(SessionEndReason.DISAPPEARED, closedQuick.endReason)
        assertEquals(t0, closedQuick.endedAtMs)
        assertFalse(closedQuick.endEstimated)
        val closedLate = SessionAccounting.reconcile(first.open, emptyList(), t0 + 600_000).changes.closes.single()
        assertTrue(closedLate.endEstimated)
        assertEquals(2_000L, closedLate.uploadBytes)
        assertEquals(8_000L, closedLate.downloadBytes)
    }

    @Test fun open_session_update_is_throttled_to_the_persist_interval() {
        val first = SessionAccounting.reconcile(emptyMap(), listOf(live(uptime = 100)), t0)
        val soon = SessionAccounting.reconcile(first.open, listOf(live(uptime = 115, up = 2_000)), t0 + 15_000)
        assertTrue(soon.changes.upserts.isEmpty())
        // الحالة في الذاكرة تتقدم رغم عدم الكتابة
        assertEquals(2_000L, soon.open.getValue(key()).uploadBytes)
        val later = SessionAccounting.reconcile(soon.open, listOf(live(uptime = 100 + 301, up = 3_000)), t0 + SessionAccounting.PERSIST_INTERVAL_MS + 1_000)
        assertEquals(1, later.changes.upserts.size)
    }

    @Test fun two_users_and_duplicate_rows_are_handled_independently() {
        val rows = listOf(live(user = "a"), live(user = "b", mac = "AA:BB:CC:00:00:02"), live(user = "a"))
        val r = SessionAccounting.reconcile(emptyMap(), rows, t0)
        assertEquals(2, r.open.size)
        assertEquals(2, r.changes.upserts.size)
    }

    @Test fun session_key_is_stable_for_same_start() {
        val a = SessionAccounting.sessionKeyFor("u", "AA:BB", t0)
        val b = SessionAccounting.sessionKeyFor("u", "aa:bb", t0 + 10_000)
        assertEquals(a, b)
    }

    // ───────────── SessionTracker: لا يُعتمد الجديد قبل نجاح التثبيت ─────────────

    private class FakeRepo(var openSessions: List<TrackedSession> = emptyList()) : ISessionHistoryRepository {
        var failApply = false
        val applied = mutableListOf<SessionChanges>()
        override suspend fun loadOpenSessions(): AppResult<List<TrackedSession>> = AppResult.Success(openSessions)
        override suspend fun applyChanges(changes: SessionChanges): AppResult<Unit> =
            if (failApply) AppResult.Error(ApiError.networkUnavailable()) else { applied += changes; AppResult.Success(Unit) }
        override suspend fun getUserStats(username: String): AppResult<UserSessionStats?> = AppResult.Success(null)
        override suspend fun getSessionHistory(username: String, limit: Int): AppResult<List<TrackedSession>> = AppResult.Success(emptyList())
    }

    @Test fun tracker_failed_persist_is_retried_without_losing_or_duplicating() = runBlocking {
        val repo = FakeRepo()
        val tracker = SessionTracker(repo)
        repo.failApply = true
        assertTrue(tracker.onLiveSessions(listOf(live(uptime = 100)), t0) is AppResult.Error)
        repo.failApply = false
        assertTrue(tracker.onLiveSessions(listOf(live(uptime = 115)), t0 + 15_000) is AppResult.Success)
        // الجلسة فُتحت مرة واحدة فقط في السجل
        assertEquals(1, repo.applied.sumOf { it.upserts.size })
        // ثم اختفت: تُغلق مرة واحدة
        assertTrue(tracker.onLiveSessions(emptyList(), t0 + 30_000) is AppResult.Success)
        assertEquals(1, repo.applied.sumOf { it.closes.size })
        // جولة فارغة أخرى لا تعيد الإغلاق
        tracker.onLiveSessions(emptyList(), t0 + 45_000)
        assertEquals(1, repo.applied.sumOf { it.closes.size })
    }

    @Test fun tracker_resumes_open_sessions_from_cloud_without_reopening() = runBlocking {
        val seeded = SessionAccounting.reconcile(emptyMap(), listOf(live(uptime = 100, up = 1_000, down = 1_000)), t0).open.values.toList()
        val repo = FakeRepo(openSessions = seeded)
        val tracker = SessionTracker(repo)
        tracker.onLiveSessions(listOf(live(uptime = 115, up = 1_200, down = 1_300)), t0 + 15_000)
        assertEquals(0, repo.applied.sumOf { it.upserts.size })
        assertEquals(0, repo.applied.sumOf { it.closes.size })
    }

    @Test fun tracker_applies_admin_disconnect_reason_hint() = runBlocking {
        val repo = FakeRepo()
        val tracker = SessionTracker(repo)
        tracker.onLiveSessions(listOf(live(uptime = 100)), t0)
        tracker.hintEndReason("u1", SessionEndReason.DISCONNECTED_BY_ADMIN, t0 + 5_000)
        tracker.onLiveSessions(emptyList(), t0 + 15_000)
        assertEquals(SessionEndReason.DISCONNECTED_BY_ADMIN, repo.applied.flatMap { it.closes }.single().endReason)
    }

    @Test fun tracker_reset_forgets_state() = runBlocking {
        val repo = FakeRepo()
        val tracker = SessionTracker(repo)
        tracker.onLiveSessions(listOf(live()), t0)
        tracker.reset()
        repo.openSessions = emptyList()
        tracker.onLiveSessions(emptyList(), t0 + 15_000)
        assertNull(repo.applied.flatMap { it.closes }.firstOrNull())
    }
}

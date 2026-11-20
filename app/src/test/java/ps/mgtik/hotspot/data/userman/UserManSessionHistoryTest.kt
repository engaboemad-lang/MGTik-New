package ps.mgtik.hotspot.data.userman

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.core.network.RouterConfig
import ps.mgtik.hotspot.core.network.RouterConnectionManager
import ps.mgtik.hotspot.core.network.RouterOsRestClient
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.data.repository.RouterRepositoryImpl
import ps.mgtik.hotspot.domain.model.LiveConfirmation
import ps.mgtik.hotspot.domain.model.UserManConfig
import java.lang.reflect.Proxy

/** سجل الجلسات التاريخي من User Manager، منع الاحتساب المزدوج، وتأكيد الجلسات الحية على NAS (عالقة/غير مُتحقَّق). */
class UserManSessionHistoryTest {

    private val cfg = RouterConfig(host = "10.0.0.1", port = 8728, username = "a", password = "b")

    private fun repo(api: SessFakeApi): RouterRepositoryImpl {
        val settings = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ps.mgtik.hotspot.domain.repository.ISettingsRepository::class.java)) { _, m, _ ->
            if (m.name == "getUserManConfig") flowOf(UserManConfig()) else null
        } as ps.mgtik.hotspot.domain.repository.ISettingsRepository
        val state = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ps.mgtik.hotspot.domain.repository.IRouterStateManager::class.java)) { _, _, _ -> null }
            as ps.mgtik.hotspot.domain.repository.IRouterStateManager
        val manager = RouterConnectionManager(RouterOsRestClient()).also { it.apiOverride = api }
        return RouterRepositoryImpl(state, manager, UserManSource(manager, settings))
    }

    private fun v7(): SessFakeApi = SessFakeApi("7.16.1").apply {
        row("/user-manager/session", "user" to "ann", "active" to "false", "status" to "stop", "acct-session-id" to "S1",
            "started" to "2026-11-01 08:00:00", "ended" to "2026-11-01 09:00:00", "uptime" to "1h", "upload" to "10", "download" to "90",
            "calling-station-id" to "AA:00", "user-address" to "10.0.0.9", "nas-ip-address" to "10.0.0.1", "terminate-cause" to "User-Request")
        // الصف نفسه مكرر بعدّادات أقدم (مثلاً مزامنة محاسبة مزدوجة): يجب ألا يُحتسب مرتين
        row("/user-manager/session", "user" to "ann", "active" to "false", "status" to "stop", "acct-session-id" to "S1",
            "started" to "2026-11-01 08:00:00", "uptime" to "30m", "upload" to "5", "download" to "40")
        row("/user-manager/session", "user" to "ann", "active" to "true", "status" to "start", "acct-session-id" to "S2",
            "started" to "2026-11-10 10:00:00", "uptime" to "5m", "upload" to "1", "download" to "9", "calling-station-id" to "AA:00", "user-address" to "10.0.0.9")
        row("/user-manager/session", "user" to "bob", "active" to "false", "status" to "stop", "acct-session-id" to "B1", "uptime" to "1h")
        row("/system/clock", "date" to "2026-11-10", "time" to "10:30:00")
    }

    @Test fun history_returns_ended_and_current_newest_first_with_real_fields_and_no_duplicates() {
        val r = runBlocking { repo(v7()).getUserSessionRecords(cfg, "ann") } as AppResult.Success
        assertEquals(listOf("S2", "S1"), r.data.map { it.acctSessionId })
        val ended = r.data.last()
        assertEquals("User-Request", ended.terminateCause)
        assertEquals("10.0.0.1", ended.nasAddress)
        assertEquals("2026-11-01 09:00:00", ended.endedRaw)
        assertEquals(10L, ended.uploadBytes); assertEquals(90L, ended.downloadBytes) // الصف الأكبر عدّادات يُبقى
        assertTrue(r.data.first().isLive); assertFalse(ended.isLive)
    }

    @Test fun history_does_not_include_other_users_and_unsupported_fields_stay_empty() {
        val r = runBlocking { repo(v7()).getUserSessionRecords(cfg, "bob") } as AppResult.Success
        val s = r.data.single()
        assertEquals("", s.terminateCause); assertEquals("", s.nasAddress); assertEquals(null, s.endedRaw)
    }

    @Test fun summary_counts_each_session_once() {
        val rows = runBlocking { UserManV7Dialect().sessionHistory(v7(), "ann") }
        val sum = UmSessionHistory.summarize(rows + rows) // إدخال مكرر عمداً
        assertEquals(2, sum.totalSessions); assertEquals(1, sum.completedSessions); assertEquals(1, sum.currentSessions)
        assertEquals(10L + 1L, sum.totalUploadBytes); assertEquals(90L + 9L, sum.totalDownloadBytes); assertEquals(110L, sum.totalBytes)
        assertEquals(3_600L + 300L, sum.totalUptimeSeconds)
    }

    @Test fun usage_sum_does_not_double_count_a_duplicated_accounting_row() {
        val u = runBlocking { UserManV7Dialect().usage(v7(), "ann") }
        assertEquals(11L, u.uploadBytes); assertEquals(99L, u.downloadBytes)
    }

    @Test fun v6_history_reads_till_time_as_end() {
        val api = SessFakeApi("6.49.10").apply {
            row("/tool/user-manager/session", "user" to "ann", "active" to "false", "status" to "stop", "acct-session-id" to "S1",
                "from-time" to "nov/01/2026 08:00:00", "till-time" to "nov/01/2026 09:00:00", "uptime" to "1h", "upload" to "3", "download" to "4")
        }
        val rows = runBlocking { UserManV6Dialect().sessionHistory(api, "ann") }
        assertEquals("nov/01/2026 09:00:00", rows.single().endedRaw)
        assertTrue(api.requests.any { it == "print /tool/user-manager/session" })
    }

    // ---------------------------------------------------------------- الجلسات الحية: تأكيد NAS

    private fun liveApi(): SessFakeApi = SessFakeApi("7.16.1").apply {
        row("/user-manager/session", "user" to "real", "active" to "true", "status" to "start", "user-address" to "10.0.0.5",
            "calling-station-id" to "AA:01", "started" to "2026-11-10 10:00:00", "uptime" to "5m", "upload" to "1", "download" to "2")
        row("/user-manager/session", "user" to "stuck", "active" to "true", "status" to "start", "user-address" to "10.0.0.6",
            "calling-station-id" to "AA:02", "started" to "2026-11-09 10:00:00", "uptime" to "9h", "upload" to "1", "download" to "2")
        row("/system/clock", "date" to "2026-11-10", "time" to "10:30:00")
    }

    @Test fun session_unknown_to_nas_is_stale_and_not_listed_as_connected() {
        val api = liveApi().apply { row("/ip/hotspot/active", "user" to "real", "mac-address" to "AA:01", "address" to "10.0.0.5") }
        val snap = (runBlocking { repo(api).getLiveSnapshot(cfg) } as AppResult.Success).data
        assertEquals(listOf("real"), snap.sessions.map { it.username })
        assertEquals(LiveConfirmation.CONFIRMED, snap.sessions.single().confirmation)
        assertEquals(listOf("stuck"), snap.stale.map { it.username })
        val list = (runBlocking { repo(api).getLiveSessions(cfg) } as AppResult.Success).data
        assertEquals(listOf("real"), list.map { it.username })
        // لا حذف ولا تعديل لأي سجل في UM أثناء القراءة
        assertTrue(api.requests.none { it.startsWith("remove") || it.startsWith("set") || it.startsWith("add") })
    }

    @Test fun unreadable_nas_keeps_sessions_but_marks_them_unverified_not_stale() {
        val api = liveApi().apply { rejectPrefix = "/ip/hotspot" }
        val snap = (runBlocking { repo(api).getLiveSnapshot(cfg) } as AppResult.Success).data
        assertEquals(setOf("real", "stuck"), snap.sessions.map { it.username }.toSet())
        assertTrue(snap.sessions.all { it.confirmation == LiveConfirmation.UNVERIFIED })
        assertTrue(snap.stale.isEmpty())
    }

    @Test fun one_nas_row_confirms_only_one_of_two_sessions_of_the_same_user() {
        val api = SessFakeApi("7.16.1").apply {
            row("/user-manager/session", "user" to "twin", "active" to "true", "status" to "start", "user-address" to "10.0.0.7", "calling-station-id" to "AA:07", "uptime" to "1m")
            row("/user-manager/session", "user" to "twin", "active" to "true", "status" to "start", "user-address" to "10.0.0.8", "calling-station-id" to "AA:08", "uptime" to "2m")
            row("/ip/hotspot/active", "user" to "TWIN", "mac-address" to "AA:08", "address" to "10.0.0.8")
        }
        val snap = (runBlocking { repo(api).getLiveSnapshot(cfg) } as AppResult.Success).data
        assertEquals(listOf("AA:08"), snap.sessions.map { it.macAddress })
        assertEquals(listOf("AA:07"), snap.stale.map { it.macAddress })
    }

    @Test fun empty_live_list_does_not_query_nas() {
        val api = SessFakeApi("7.16.1")
        val snap = (runBlocking { repo(api).getLiveSnapshot(cfg) } as AppResult.Success).data
        assertTrue(snap.sessions.isEmpty() && snap.stale.isEmpty())
        assertFalse(api.requests.any { it.contains("/ip/hotspot/active") })
    }
}

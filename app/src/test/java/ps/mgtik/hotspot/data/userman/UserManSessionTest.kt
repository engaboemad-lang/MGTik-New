package ps.mgtik.hotspot.data.userman

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.core.network.RouterApi
import ps.mgtik.hotspot.core.network.RouterConfig
import ps.mgtik.hotspot.core.network.RouterConnectionManager
import ps.mgtik.hotspot.core.network.RouterConnectionType
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.data.repository.RouterRepositoryImpl
import ps.mgtik.hotspot.domain.model.DisconnectOutcome
import ps.mgtik.hotspot.domain.model.UserManConfig
import java.lang.reflect.Proxy

/** راوتر وهمي: جداول بالمسار، يسجّل كل طلب، ويمكن جعل الحذف لا يفعل شيئاً (لمحاكاة فصل لم يتحقق). */
internal class SessFakeApi(var version: String) : RouterApi {
    override val kind = RouterConnectionType.API
    override val port = 8728
    val tables = mutableMapOf<String, MutableList<MutableMap<String, String>>>()
    val requests = mutableListOf<String>()
    var removeIsNoop = false
    var rejectPrefix: String? = null
    private var seq = 0

    fun row(path: String, vararg kv: Pair<String, String>): String {
        val id = "*${++seq}"
        tables.getOrPut(path) { mutableListOf() }.add(mutableMapOf(*kv).also { it[".id"] = id })
        return id
    }

    override suspend fun print(path: String, query: Map<String, String>, props: List<String>): List<JsonObject> {
        requests += "print $path"
        rejectPrefix?.let { if (path.startsWith(it)) throw ps.mgtik.hotspot.core.network.RouterCommandRejected("no such command or directory") }
        if (path == "/system/resource") return listOf(JsonObject(mapOf("version" to JsonPrimitive(version))))
        return tables[path].orEmpty().filter { r -> query.all { (k, v) -> r[k] == v } }
            .map { r -> JsonObject(r.mapValues { JsonPrimitive(it.value) }) }
    }
    override suspend fun add(path: String, attrs: Map<String, Any?>) { requests += "add $path" }
    override suspend fun set(path: String, ref: String, attrs: Map<String, Any?>) { requests += "set $path" }
    override suspend fun remove(path: String, ref: String): Boolean {
        requests += "remove $path $ref"
        if (removeIsNoop) return true
        return tables[path]?.removeAll { it[".id"] == ref } ?: false
    }
    override suspend fun action(path: String, action: String, ref: String?) { requests += "action $path" }
    override fun close() = Unit
}

class UserManSessionTest {

    private val cfg = RouterConfig(host = "10.0.0.1", port = 8728, username = "a", password = "b")

    private fun v7Api() = SessFakeApi("7.16.1").apply {
        // موثَّق في RouterOS 7: user-address / started / calling-station-id / active / status
        row("/user-manager/session", "user" to "live1", "active" to "true", "status" to "start", "user-address" to "10.0.0.5",
            "calling-station-id" to "AA:BB:CC:00:00:01", "started" to "2026-11-10 10:00:00", "uptime" to "5m", "upload" to "100", "download" to "900")
        row("/user-manager/session", "user" to "old1", "active" to "false", "status" to "stop", "uptime" to "1h", "upload" to "5", "download" to "5")
        row("/user-manager/session", "user" to "ended1", "active" to "true", "status" to "stop,interim", "uptime" to "2h")
        row("/user-manager/session", "user" to "noflag", "status" to "start", "uptime" to "9m")
        row("/system/clock", "date" to "2026-11-10", "time" to "10:30:00")
    }

    @Test fun v7_live_sessions_exclude_historical_and_ended_records() {
        val live = runBlocking { UserManV7Dialect().liveSessions(v7Api()) }
        assertEquals(listOf("live1"), live.map { it.username })
        val s = live.single()
        assertEquals("10.0.0.5", s.address)
        assertEquals("AA:BB:CC:00:00:01", s.macAddress)
        assertEquals(100L, s.uploadBytes)
        assertEquals(900L, s.downloadBytes)
    }

    @Test fun uptime_comes_from_router_clock_minus_start_not_from_stale_reported_uptime() {
        val s = runBlocking { UserManV7Dialect().liveSessions(v7Api()) }.single()
        assertEquals(30 * 60L, s.uptimeSeconds) // 10:00 → 10:30، لا "5m" المبلَّغة
    }

    @Test fun uptime_falls_back_to_reported_when_clock_or_start_is_unparseable() {
        val api = v7Api().apply { tables["/system/clock"]!!.clear() }
        assertEquals(300L, runBlocking { UserManV7Dialect().liveSessions(api) }.single().uptimeSeconds)
        val api2 = v7Api().apply { tables["/user-manager/session"]!![0]["started"] = "garbage" }
        assertEquals(300L, runBlocking { UserManV7Dialect().liveSessions(api2) }.single().uptimeSeconds)
    }

    @Test fun v6_reads_its_own_path_and_field_names() {
        val api = SessFakeApi("6.49.10").apply {
            row("/tool/user-manager/session", "user" to "u6", "active" to "yes", "user-ip" to "10.1.1.2", "calling-station-id" to "AA:AA:AA:AA:AA:AA",
                "from-time" to "nov/10/2026 08:00:00", "uptime" to "1m", "upload" to "7", "download" to "8")
            row("/tool/user-manager/session", "user" to "hist", "active" to "no", "uptime" to "3h")
            row("/system/clock", "date" to "nov/10/2026", "time" to "09:00:00")
        }
        val live = runBlocking { UserManV6Dialect().liveSessions(api) }
        assertEquals(listOf("u6"), live.map { it.username })
        assertEquals("10.1.1.2", live.single().address)
        assertEquals(3600L, live.single().uptimeSeconds)
        assertFalse(api.requests.any { it.contains("/ip/hotspot") })
    }

    @Test fun router_datetime_parser_handles_both_formats_and_rejects_garbage() {
        val iso = UmUnits.parseRouterDateTime("2026-11-10 10:00:00")!!
        val ros = UmUnits.parseRouterDateTime("nov/10/2026 10:00:00")!!
        assertEquals(iso, ros)
        assertEquals(3600L, UmUnits.parseRouterDateTime("2026-11-10T11:00:00")!! - iso)
        assertNull(UmUnits.parseRouterDateTime(""))
        assertNull(UmUnits.parseRouterDateTime("xyz/10/2026 10:00:00"))
        assertNull(UmUnits.parseRouterDateTime("2026-13-40 10:00:00"))
        assertNull(UmUnits.parseRouterDateTime(null))
    }

    // ------------------------------------------------------------------ NAS

    private fun nasRow(api: SessFakeApi, user: String, mac: String, ip: String) =
        api.row("/ip/hotspot/active", "user" to user, "mac-address" to mac, "address" to ip)

    @Test fun nas_disconnect_by_username_removes_all_of_that_users_sessions_only() {
        val api = SessFakeApi("7.16")
        nasRow(api, "alice", "AA:00", "10.0.0.2"); nasRow(api, "ALICE", "BB:00", "10.0.0.3"); nasRow(api, "bob", "CC:00", "10.0.0.4")
        val r = runBlocking { RouterRepositoryImpl.disconnectOnNas(api, "alice") }
        assertEquals(2, r.matched); assertEquals(2, r.removed); assertEquals(0, r.remaining)
        assertEquals(listOf("bob"), api.tables["/ip/hotspot/active"]!!.map { it["user"] })
    }

    @Test fun nas_disconnect_with_mac_removes_only_the_matching_session_and_never_another_users() {
        val api = SessFakeApi("7.16")
        nasRow(api, "alice", "AA:00", "10.0.0.2"); nasRow(api, "alice", "BB:00", "10.0.0.3"); nasRow(api, "bob", "AA:00", "10.0.0.9")
        val r = runBlocking { RouterRepositoryImpl.disconnectOnNas(api, "alice", macAddress = "aa:00") }
        assertEquals(1, r.removed)
        assertEquals(setOf("alice|BB:00", "bob|AA:00"), api.tables["/ip/hotspot/active"]!!.map { it["user"] + "|" + it["mac-address"] }.toSet())
    }

    @Test fun nas_disconnect_reports_remaining_when_removal_did_not_take_effect() {
        val api = SessFakeApi("7.16").apply { removeIsNoop = true }
        nasRow(api, "alice", "AA:00", "10.0.0.2")
        val r = runBlocking { RouterRepositoryImpl.disconnectOnNas(api, "alice") }
        assertEquals(1, r.removed); assertEquals(1, r.remaining)
    }

    // ------------------------------------------------------------------ المستودع

    private fun repo(api: SessFakeApi): RouterRepositoryImpl {
        val settings = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ps.mgtik.hotspot.domain.repository.ISettingsRepository::class.java)) { _, m, _ ->
            if (m.name == "getUserManConfig") flowOf(UserManConfig()) else null
        } as ps.mgtik.hotspot.domain.repository.ISettingsRepository
        val state = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ps.mgtik.hotspot.domain.repository.IRouterStateManager::class.java)) { _, _, _ -> null }
            as ps.mgtik.hotspot.domain.repository.IRouterStateManager
        val manager = RouterConnectionManager(ps.mgtik.hotspot.core.network.RouterOsRestClient()).also { it.apiOverride = api }
        return RouterRepositoryImpl(state, manager, UserManSource(manager, settings))
    }

    @Test fun live_sessions_are_read_from_user_manager_never_from_hotspot_active() {
        val api = v7Api().apply {
            nasRow(this, "live1", "AA:BB:CC:00:00:01", "10.0.0.5")
            row("/user-manager/user", "name" to "live1", "disabled" to "false")
            row("/user-manager/user-profile", "user" to "live1", "profile" to "gold", "state" to "running-active")
        }
        val r = runBlocking { repo(api).getLiveSessions(cfg) }
        val list = (r as AppResult.Success).data
        assertEquals(listOf("live1"), list.map { it.username })
        assertEquals("gold", list.single().profile)
        // NAS يُقرأ للتأكيد فقط (print)؛ لا كتابة عليه ولا يكون مصدر القائمة
        assertTrue(api.requests.filter { it.contains("/ip/hotspot/active") }.all { it.startsWith("print ") })
        assertEquals(ps.mgtik.hotspot.domain.model.LiveConfirmation.CONFIRMED, list.single().confirmation)
    }

    @Test fun single_session_disconnect_matches_by_user_and_mac_then_verifies() {
        val api = v7Api()
        nasRow(api, "live1", "AA:BB:CC:00:00:01", "10.0.0.5"); nasRow(api, "live1", "FF:FF:FF:00:00:02", "10.0.0.6"); nasRow(api, "other", "AA:BB:CC:00:00:01", "10.0.0.7")
        val sid = api.tables["/user-manager/session"]!![0][".id"]!!
        val r = runBlocking { repo(api).disconnectSession(cfg, sid) }
        assertEquals(DisconnectOutcome.DISCONNECTED, (r as AppResult.Success).data)
        assertEquals(setOf("live1|FF:FF:FF:00:00:02", "other|AA:BB:CC:00:00:01"),
            api.tables["/ip/hotspot/active"]!!.map { it["user"] + "|" + it["mac-address"] }.toSet())
    }

    @Test fun disconnect_failure_is_an_error_not_a_success() {
        val api = v7Api().apply { removeIsNoop = true }
        nasRow(api, "live1", "AA:BB:CC:00:00:01", "10.0.0.5")
        val sid = api.tables["/user-manager/session"]!![0][".id"]!!
        val r = runBlocking { repo(api).disconnectSession(cfg, sid) }
        assertTrue(r is AppResult.Error)
        assertTrue((r as AppResult.Error).error.retryable)
    }

    @Test fun session_already_ended_is_already_gone_and_user_without_nas_session_is_already_gone() {
        val api = v7Api()
        assertEquals(DisconnectOutcome.ALREADY_GONE, (runBlocking { repo(api).disconnectSession(cfg, "*999") } as AppResult.Success).data)
        assertEquals(DisconnectOutcome.ALREADY_GONE, (runBlocking { repo(api).disconnectUserSessions(cfg, "nobody") } as AppResult.Success).data)
    }

    @Test fun disconnect_user_sessions_cuts_every_session_and_verifies() {
        val api = SessFakeApi("7.16.1")
        nasRow(api, "u", "AA:00", "10.0.0.2"); nasRow(api, "u", "BB:00", "10.0.0.3")
        assertEquals(DisconnectOutcome.DISCONNECTED, (runBlocking { repo(api).disconnectUserSessions(cfg, "u") } as AppResult.Success).data)
        assertTrue(api.tables["/ip/hotspot/active"]!!.isEmpty())
    }

    @Test fun unknown_router_version_is_a_clear_error_and_never_guesses_v6() {
        val api = SessFakeApi("").apply { row("/system/clock", "date" to "2026-11-10", "time" to "10:30:00") }
        val r = runBlocking { repo(api).getLiveSessions(cfg) }
        assertEquals(UserManSource.ERR_ROUTER_VERSION_UNKNOWN, (r as AppResult.Error).error.code)
        assertFalse(api.requests.any { it.contains("user-manager") })
    }

    @Test fun path_mismatch_after_upgrade_rereads_version_and_retries_once_on_the_new_dialect() {
        val api = v7Api().apply { version = "6.49.10"; nasRow(this, "live1", "AA:BB:CC:00:00:01", "10.0.0.5") }
        val repo = repo(api)
        // الطلب الأول يخزّن v6 ثم تُرقّى الراوتر إلى v7 ويرفض مسارات v6
        runBlocking { repo.getLiveSessions(cfg) }
        api.version = "7.16.1"; api.rejectPrefix = "/tool/user-manager"
        val r = runBlocking { repo.getLiveSessions(cfg) }
        assertEquals(listOf("live1"), (r as AppResult.Success).data.map { it.username })
    }

    @Test fun path_mismatch_with_same_generation_is_not_retried_or_switched() {
        val api = v7Api().apply { rejectPrefix = "/user-manager" }
        val r = runBlocking { repo(api).getLiveSessions(cfg) }
        assertTrue(r is AppResult.Error)
        assertFalse(api.requests.any { it.contains("/tool/user-manager") })
    }
}

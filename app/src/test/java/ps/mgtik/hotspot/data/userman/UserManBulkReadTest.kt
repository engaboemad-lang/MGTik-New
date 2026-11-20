package ps.mgtik.hotspot.data.userman

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.core.network.RouterApi
import ps.mgtik.hotspot.core.network.RouterConnectionType

/** راوتر وهمي يعدّ كل طلب print: لقياس أن قراءة الدفعات لا تكبر مع عدد المستخدمين. */
private class CountingApi : RouterApi {
    override val kind = RouterConnectionType.API
    override val port = 8728
    val tables = mutableMapOf<String, MutableList<Map<String, String>>>()
    val prints = mutableListOf<String>()
    override suspend fun print(path: String, query: Map<String, String>, props: List<String>): List<JsonObject> {
        prints += path
        return (tables[path] ?: emptyList()).filter { row -> query.all { (k, v) -> row[k] == v } }
            .map { row -> JsonObject(row.mapValues { JsonPrimitive(it.value) }) }
    }
    val adds = mutableListOf<String>()
    var swallowAdds = false
    override suspend fun add(path: String, attrs: Map<String, Any?>) {
        adds += path
        if (!swallowAdds) tables.getOrPut(path) { mutableListOf() } += attrs.mapValues { it.value.toString() }
    }
    override suspend fun set(path: String, ref: String, attrs: Map<String, Any?>) = Unit
    override suspend fun remove(path: String, ref: String) = true
    override suspend fun action(path: String, action: String, ref: String?) = Unit
    override suspend fun command(path: String, attrs: Map<String, Any?>) = Unit
    override fun close() = Unit
}

class UserManBulkReadTest {
    private val n = 500

    private fun v7(): CountingApi = CountingApi().also { api ->
        api.tables["/user-manager/user"] = (1..n).map { mapOf(".id" to "*$it", "name" to "u$it", "comment" to "MGTIK-B1", "disabled" to "false") }.toMutableList()
        // مستخدمون بأكثر من فترة: الأحدث (الأخير) هو المعتمد
        api.tables["/user-manager/user-profile"] = ((1..n).map { mapOf("user" to "u$it", "profile" to "old") } +
            (1..n).map { mapOf("user" to "u$it", "profile" to "p${it % 3}") }).toMutableList()
    }

    @Test fun v7_listUsers_isTwoRequestsFor500Users_andKeepsLatestPackage() = runBlocking {
        val api = v7()
        val users = UserManV7Dialect().listUsers(api)
        assertEquals(n, users.size)
        assertEquals(2, api.prints.size)
        assertEquals("p1", users.first { it.name == "u1" }.packageName)
    }

    @Test fun v7_names_isOneRequest_andPackages_isOneRequest() = runBlocking {
        val api = v7()
        val d = UserManV7Dialect()
        assertEquals(n, d.listUserNames(api).size)
        assertEquals(1, api.prints.size)
        api.prints.clear()
        assertEquals(n, d.userPackages(api).size)
        assertEquals(1, api.prints.size)
    }

    @Test fun v7_usersOnPackage_usesOneRequest() = runBlocking {
        val api = v7()
        val on = UserManV7Dialect().usersOnPackage(api, "p0")
        assertEquals(1, api.prints.size)
        assertTrue(on.isNotEmpty() && on.all { it.removePrefix("u").toInt() % 3 == 0 })
    }

    /** مقارنة: النمط القديم (استعلام باقة لكل مستخدم) = 1 + 500 طلباً؛ الجديد = 2. */
    @Test fun before_after_requestCount_for500Users() = runBlocking {
        val before = v7()
        val d = UserManV7Dialect()
        before.print("/user-manager/user")
        d.listUserNames(before).forEach { (name, _) -> before.print("/user-manager/user-profile", mapOf("user" to name), listOf("profile")) }
        val after = v7()
        d.listUsers(after)
        println("PERF v7 listUsers n=$n requests before=${before.prints.size} after=${after.prints.size}")
        assertEquals(n + 2, before.prints.size)
        assertEquals(2, after.prints.size)
    }
}

class UserManVersionDetectionTest {
    @Test fun unknownVersion_isNullNotV6() {
        org.junit.Assert.assertNull(UserManSource.dialectForVersion(""))
        org.junit.Assert.assertNull(UserManSource.dialectForVersion("abc"))
        assertEquals(UmGeneration.V7, UserManSource.dialectForVersion("7.16")!!.generation)
        assertEquals(UmGeneration.V6, UserManSource.dialectForVersion("6.48.6 (long-term)")!!.generation)
    }

    @Test fun pathMismatchDetection() {
        assertTrue(UserManSource.looksLikePathMismatch("no such command or directory (user-manager)"))
        assertTrue(!UserManSource.looksLikePathMismatch("failure: already have such user"))
    }
}

class UserManReadinessGateTest {
    private fun c(k: String, ok: Boolean) = UmCheck(k, ok, "")
    @Test fun blocking_excludes_secret_match_and_passing_checks() {
        val checks = listOf(c(UmCheckKeys.PACKAGE, true), c(UmCheckKeys.RADIUS, false), c(UmCheckKeys.SECRET_MATCH, false), c(UmCheckKeys.USE_RADIUS, false))
        assertEquals(listOf(UmCheckKeys.RADIUS, UmCheckKeys.USE_RADIUS), UserManPlanner.blockingChecks(checks).map { it.key })
        assertTrue(UserManPlanner.blockingChecks(listOf(c(UmCheckKeys.SECRET_MATCH, false))).isEmpty())
    }
}

class UserManActivationTest {
    private fun api(vararg rows: Map<String, String>) = CountingApi().also { it.tables["/user-manager/user-profile"] = rows.toMutableList() }
    private fun row(profile: String, state: String? = null) = buildMap { put("user", "u1"); put("profile", profile); state?.let { put("state", it) } }
    private val d = UserManV7Dialect()

    @Test fun first_activation_adds_one_row() = runBlocking {
        val a = api(); d.activatePackage(a, "u1", "gold")
        assertEquals(1, a.adds.size)
    }

    @Test fun retry_of_plain_activation_does_not_duplicate() = runBlocking {
        val a = api(); d.activatePackage(a, "u1", "gold"); d.activatePackage(a, "u1", "gold")
        assertEquals(1, a.adds.size)
    }

    @Test fun lost_ack_renewal_retry_does_not_queue_a_second_period() = runBlocking {
        val a = api(row("gold", "running-active"))
        d.activatePackage(a, "u1", "gold", renew = true)   // يضيف فترة منتظرة
        assertEquals(1, a.adds.size)
        a.tables["/user-manager/user-profile"]!![1] = row("gold", "waiting")
        d.activatePackage(a, "u1", "gold", renew = true)   // إعادة المحاولة: يوجد منتظر ⇒ تخطٍّ
        assertEquals(1, a.adds.size)
    }

    @Test fun renewal_after_an_expired_period_adds_a_new_one() = runBlocking {
        val a = api(row("gold", "used")); d.activatePackage(a, "u1", "gold", renew = true)
        assertEquals(1, a.adds.size)
    }

    @Test fun package_change_adds_even_if_old_package_is_running() = runBlocking {
        val a = api(row("silver", "running-active")); d.activatePackage(a, "u1", "gold")
        assertEquals(1, a.adds.size)
    }

    @Test fun unverified_activation_is_a_retryable_error() {
        val a = api().also { it.swallowAdds = true }
        val e = try { runBlocking { d.activatePackage(a, "u1", "gold") }; null } catch (e: ps.mgtik.hotspot.core.network.RouterApiException) { e }
        assertEquals("ERR_USER_ROUTER_ACTIVATE_UNVERIFIED", e!!.error.code)
        assertTrue(e.error.retryable)
    }
}

class UserManPeriodUsageTest {
    private fun sess(started: String, up: Long, down: Long, uptime: String) =
        mapOf("user" to "u1", "started" to started, "upload" to "$up", "download" to "$down", "uptime" to uptime)

    private fun api(state: String?, start: String?) = CountingApi().also { a ->
        a.tables["/user-manager/session"] = mutableListOf(
            sess("2026-10-01 10:00:00", 100, 200, "1h"),   // فترة سابقة
            sess("2026-11-05 08:00:00", 10, 20, "30m"),    // الفترة الحالية
            sess("2026-11-06 08:00:00", 1, 2, "10m")
        )
        a.tables["/user-manager/user-profile"] = mutableListOf(
            mapOf("user" to "u1", "profile" to "old", "state" to "used", "start-time" to "2026-10-01 09:00:00"),
            buildMap { put("user", "u1"); put("profile", "gold"); state?.let { put("state", it) }; start?.let { put("start-time", it) } }
        )
    }

    @Test fun period_excludes_sessions_before_the_current_activation_but_total_keeps_history() = runBlocking {
        val a = api("running-active", "2026-11-05 07:00:00")
        val d = UserManV7Dialect()
        val p = d.periodUsage(a, "u1"); val t = d.usage(a, "u1")
        assertEquals(40L * 60, p.uptimeSeconds); assertEquals(11L, p.uploadBytes); assertEquals(22L, p.downloadBytes)
        assertEquals(100L * 60, t.uptimeSeconds); assertEquals(111L, t.uploadBytes)
    }

    @Test fun unknown_period_start_falls_back_to_total_never_hides_usage() = runBlocking {
        val d = UserManV7Dialect()
        assertEquals(d.usage(api("running-active", null), "u1"), d.periodUsage(api("running-active", null), "u1"))
        assertEquals(d.usage(api(null, null), "u1"), d.periodUsage(api(null, null), "u1"))
    }

    @Test fun waiting_renewal_does_not_move_the_period_start() = runBlocking {
        val a = api("running-active", "2026-11-05 07:00:00")
        a.tables["/user-manager/user-profile"]!!.add(mapOf("user" to "u1", "profile" to "gold", "state" to "waiting"))
        assertEquals(11L, UserManV7Dialect().periodUsage(a, "u1").uploadBytes)
    }
}

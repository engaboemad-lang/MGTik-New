package ps.mgtik.hotspot.data.userman

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.core.network.RouterApi
import ps.mgtik.hotspot.core.network.RouterConnectionType

/** راوتر وهمي في الذاكرة: جداول بحسب المسار، .id تلقائي، ويسجّل الأوامر الكاملة (command). */
private class FakeRouterApi : RouterApi {
    override val kind = RouterConnectionType.API
    override val port = 8728
    val tables = mutableMapOf<String, MutableList<MutableMap<String, String>>>()
    val commands = mutableListOf<Pair<String, Map<String, Any?>>>()
    private var seq = 0

    private fun table(path: String) = tables.getOrPut(path) { mutableListOf() }

    override suspend fun print(path: String, query: Map<String, String>, props: List<String>): List<JsonObject> =
        table(path).filter { row -> query.all { (k, v) -> row[k] == v } }
            .map { row -> JsonObject(row.mapValues { JsonPrimitive(it.value) }) }

    override suspend fun add(path: String, attrs: Map<String, Any?>) {
        val row = attrs.mapValues { it.value.toString() }.toMutableMap()
        row[".id"] = "*${++seq}"
        table(path).add(row)
    }

    override suspend fun set(path: String, ref: String, attrs: Map<String, Any?>) {
        val row = table(path).first { it[".id"] == ref }
        attrs.forEach { (k, v) -> row[k] = v.toString() }
    }

    override suspend fun remove(path: String, ref: String): Boolean = table(path).removeAll { it[".id"] == ref }

    override suspend fun setSingle(path: String, attrs: Map<String, Any?>) {
        val row = table(path).first()
        attrs.forEach { (k, v) -> row[k] = v.toString() }
    }

    override suspend fun action(path: String, action: String, ref: String?) = Unit

    override suspend fun command(path: String, attrs: Map<String, Any?>) {
        commands += path to attrs
    }

    override fun close() = Unit
}

class UserManDialectTest {

    private val spec = UmPackageSpec(
        name = "gold-1m", rxKbps = 512, txKbps = 1024, sharedUsers = 2,
        uptimeSeconds = 7_200, transferBytes = 1_073_741_824L, validitySeconds = 86_400
    )

    @Test
    fun `dialect is chosen from the RouterOS major version`() {
        assertEquals(UmGeneration.V7, UserManSource.dialectForVersion("7.14.1 (stable)")!!.generation)
        assertEquals(UmGeneration.V6, UserManSource.dialectForVersion("6.49.10 (long-term)")!!.generation)
        assertNull(UserManSource.dialectForVersion(""))
        assertNull(UserManSource.dialectForVersion("garbled"))
    }

    @Test
    fun `v7 upsert writes limitation profile and link then is idempotent`() = runBlocking {
        val api = FakeRouterApi(); val d = UserManV7Dialect()
        d.upsertPackage(api, spec); d.upsertPackage(api, spec)
        assertEquals(1, api.tables["/user-manager/limitation"]!!.size)
        assertEquals(1, api.tables["/user-manager/profile"]!!.size)
        assertEquals(1, api.tables["/user-manager/profile-limitation"]!!.size)
        val lim = api.tables["/user-manager/limitation"]!!.single()
        assertEquals("mgtik-gold-1m", lim["name"]); assertEquals("512k", lim["rate-limit-rx"]); assertEquals("1024k", lim["rate-limit-tx"])
        assertEquals("7200s", lim["uptime-limit"]); assertEquals("1073741824", lim["transfer-limit"])
        val prof = api.tables["/user-manager/profile"]!!.single()
        assertEquals("86400s", prof["validity"]); assertEquals("2", prof["override-shared-users"])
        val back = d.listPackages(api).single()
        assertEquals(spec, UmPackageSpec(back.name, back.rxKbps, back.txKbps, back.sharedUsers, back.uptimeSeconds, back.transferBytes, back.validitySeconds))
    }

    @Test
    fun `v7 user lifecycle create disable renew delete`() = runBlocking {
        val api = FakeRouterApi(); val d = UserManV7Dialect()
        d.upsertPackage(api, spec)
        d.createUser(api, "u1", "pw", "MGTIK-1", "gold-1m", disabled = false)
        assertEquals("gold-1m", d.findUser(api, "u1")!!.packageName)
        d.setDisabled(api, "u1", true)
        assertTrue(d.findUser(api, "u1")!!.disabled)
        d.activatePackage(api, "u1", "gold-1m", renew = true)
        assertEquals(2, api.tables["/user-manager/user-profile"]!!.size)
        assertTrue(d.deleteUser(api, "u1"))
        assertNull(d.findUser(api, "u1"))
        assertTrue(api.tables["/user-manager/user-profile"]!!.isEmpty())
        assertFalse(d.deleteUser(api, "u1"))
    }

    @Test
    fun `v6 activation uses create-and-activate-profile with customer and user id`() = runBlocking {
        val api = FakeRouterApi(); val d = UserManV6Dialect()
        d.upsertPackage(api, spec)
        assertEquals("admin", api.tables["/tool/user-manager/profile"]!!.single()["owner"])
        d.createUser(api, "u1", "pw", "", "gold-1m", disabled = false)
        val (path, attrs) = api.commands.single()
        assertEquals("/tool/user-manager/user/create-and-activate-profile", path)
        assertEquals("admin", attrs["customer"]); assertEquals("gold-1m", attrs["profile"])
        assertEquals(api.tables["/tool/user-manager/user"]!!.single()[".id"], attrs["numbers"])
        assertNotNull(d.findUser(api, "u1"))
    }

    @Test
    fun `unit parsers handle routeros formats`() {
        assertEquals(93_784L, UmUnits.parseDurationSeconds("1d2h3m4s"))
        assertEquals(600L, UmUnits.parseDurationSeconds("00:10:00"))
        assertNull(UmUnits.parseDurationSeconds("abc"))
        assertEquals(512, UmUnits.parseRateKbps("512k")); assertEquals(2_000, UmUnits.parseRateKbps("2M"))
        assertEquals(1_500, UmUnits.parseRateKbps("1500000")); assertEquals(0, UmUnits.parseRateKbps(null))
    }

    // ───────────── تدقيق User Manager: إصلاحات مثبتة بالاختبار (محاكاة فقط، ليست دليل تشغيل حقيقي) ─────────────

    @Test
    fun `byte counters parse plain and routeros display formats without silently zeroing`() {
        assertEquals(5_368_709_120L, UmUnits.parseBytes("5368709120"))
        assertEquals(5_368_709_120L, UmUnits.parseBytes("5368709120B"))
        assertEquals(1_073_741_824L, UmUnits.parseBytes("1GiB"))
        assertEquals(1_048_576L, UmUnits.parseBytes("1.0 MiB"))
        assertNull(UmUnits.parseBytes("abc")); assertNull(UmUnits.parseBytes(""))
    }

    @Test
    fun `v7 session counters with unit suffix are counted`() = runBlocking {
        val api = FakeRouterApi(); val d = UserManV7Dialect()
        api.tables["/user-manager/session"] = mutableListOf(
            mutableMapOf(".id" to "*1", "user" to "u1", "upload" to "2048B", "download" to "1KiB", "uptime" to "1m")
        )
        val u = d.usage(api, "u1")
        assertEquals(2048L, u.uploadBytes); assertEquals(1024L, u.downloadBytes)
    }

    @Test
    fun `period start is end-time minus validity and unknown when open ended`() {
        val end = UmUnits.parseRouterDateTime("2026-11-06 08:00:00")!!
        assertEquals(end - 86_400L, UmUnits.periodStartSeconds("2026-11-06 08:00:00", 86_400L))
        assertNull(UmUnits.periodStartSeconds("unlimited", 86_400L))
        assertNull(UmUnits.periodStartSeconds("not-yet-running", 86_400L))
        assertNull(UmUnits.periodStartSeconds("2026-11-06 08:00:00", null))
        assertNull(UmUnits.periodStartSeconds("2026-11-06 08:00:00", 0L))
    }

    @Test
    fun `v7 period usage uses end-time and profile validity since user-profile has no start-time`() = runBlocking {
        val api = FakeRouterApi(); val d = UserManV7Dialect()
        api.tables["/user-manager/profile"] = mutableListOf(mutableMapOf(".id" to "*p", "name" to "gold", "validity" to "1d"))
        api.tables["/user-manager/user-profile"] = mutableListOf(
            mutableMapOf(".id" to "*a", "user" to "u1", "profile" to "gold", "state" to "running-active", "end-time" to "2026-11-06 08:00:00")
        )
        api.tables["/user-manager/session"] = mutableListOf(
            mutableMapOf(".id" to "*s1", "user" to "u1", "started" to "2026-11-04 09:00:00", "upload" to "100", "download" to "200", "uptime" to "1h"),
            mutableMapOf(".id" to "*s2", "user" to "u1", "started" to "2026-11-05 10:00:00", "upload" to "10", "download" to "20", "uptime" to "30m")
        )
        val p = d.periodUsage(api, "u1")
        assertEquals(10L, p.uploadBytes); assertEquals(20L, p.downloadBytes); assertEquals(1_800L, p.uptimeSeconds)
        assertEquals(110L, d.usage(api, "u1").uploadBytes)
    }

    @Test
    fun `unlinked own limitation is not reported as the package limits and does not match the spec`() = runBlocking {
        val api = FakeRouterApi(); val d = UserManV7Dialect()
        d.upsertPackage(api, spec)
        assertTrue(d.listPackages(api).single().matches(spec))
        api.tables["/user-manager/profile-limitation"]!!.clear() // إنشاء جزئي: القيد موجود والرابط ضائع
        val partial = d.listPackages(api).single()
        assertFalse(partial.limitationLinked); assertFalse(partial.matches(spec))
        assertTrue(partial.limitationOrphaned) // موجودة وغير مربوطة = إنشاء ناقص (يُمنع بسببها الإنشاء/التوليد)
        assertEquals(0, partial.rxKbps); assertNull(partial.uptimeSeconds)
        d.upsertPackage(api, spec) // إعادة المحاولة تُكمل الرابط دون تكرار
        assertTrue(d.listPackages(api).single().matches(spec))
        assertEquals(1, api.tables["/user-manager/profile-limitation"]!!.size)
    }

    @Test
    fun `package match detects value drift`() = runBlocking {
        val api = FakeRouterApi(); val d = UserManV7Dialect()
        d.upsertPackage(api, spec)
        val onRouter = d.listPackages(api).single()
        assertTrue(onRouter.matches(spec))
        assertFalse(onRouter.matches(spec.copy(txKbps = 2048)))
        assertFalse(onRouter.matches(spec.copy(transferBytes = 0L)))
        assertFalse(onRouter.matches(spec.copy(validityUnlimited = true)))
        assertTrue(onRouter.matches(spec.copy(transferBytes = null, validitySeconds = null))) // null = لا تُلمس
    }

    @Test
    fun `rename restores the profile-limitation link under the new names`() = runBlocking {
        val api = FakeRouterApi(); val d = UserManV7Dialect()
        d.upsertPackage(api, spec)
        assertTrue(d.renamePackage(api, "gold-1m", "gold-2m"))
        val links = api.tables["/user-manager/profile-limitation"]!!
        assertTrue(links.any { it["profile"] == "gold-2m" && it["limitation"] == "mgtik-gold-2m" })
        assertTrue(d.listPackages(api).single().limitationLinked)
    }

    @Test
    fun `v6 re-activation of the already running package is skipped but explicit renew goes through`() = runBlocking {
        val api = FakeRouterApi(); val d = UserManV6Dialect()
        d.upsertPackage(api, spec)
        d.createUser(api, "u1", "pw", "", "gold-1m", disabled = false)
        assertEquals(1, api.commands.size)
        api.tables["/tool/user-manager/user"]!!.single()["actual-profile"] = "gold-1m"
        d.activatePackage(api, "u1", "gold-1m")               // إعادة محاولة: لا تفعيل ثانٍ
        assertEquals(1, api.commands.size)
        d.activatePackage(api, "u1", "gold-1m", renew = true) // تجديد صريح: فترة جديدة
        assertEquals(2, api.commands.size)
    }

    @Test
    fun `radius incoming accept is checked advisory-only and fixed by provisioning`() = runBlocking {
        val api = FakeRouterApi(); val d = UserManV7Dialect()
        val cfg = ps.mgtik.hotspot.domain.model.UserManConfig(radiusSecret = "s")
        api.tables["/user-manager"] = mutableListOf(mutableMapOf(".id" to "*0", "enabled" to "yes", "use-profiles" to "yes"))
        // قائمة غير مقروءة = لا فحص
        assertTrue(d.inspect(api, cfg, "7.14").checks.none { it.key == UmCheckKeys.INCOMING })
        assertTrue(d.provision(api, cfg).none { it.step == UmStep.INCOMING })

        api.tables["/radius/incoming"] = mutableListOf(mutableMapOf(".id" to "*1", "accept" to "no"))
        val check = d.inspect(api, cfg, "7.14").checks.single { it.key == UmCheckKeys.INCOMING }
        assertFalse(check.ok)
        assertEquals(setOf(UmStep.INCOMING), UserManPlanner.stepsFor(listOf(check)))
        assertTrue(UserManPlanner.blockingChecks(listOf(check)).isEmpty()) // لا يوقف التوليد على إعداد كان يعمل

        val step = d.provision(api, cfg).single { it.step == UmStep.INCOMING }
        assertEquals(UmAction.UPDATED, step.action)
        assertEquals("yes", api.tables["/radius/incoming"]!!.single()["accept"])
        assertEquals(UmAction.UNCHANGED, d.provision(api, cfg).single { it.step == UmStep.INCOMING }.action)
    }

    @Test
    fun `unlimited shared users on the router is flagged not read as one and never matches a numeric spec`() = runBlocking {
        val api = FakeRouterApi(); val d = UserManV7Dialect()
        d.upsertPackage(api, spec)
        api.tables["/user-manager/profile"]!!.single()["override-shared-users"] = "unlimited"
        val p = d.listPackages(api).single()
        assertTrue(p.sharedUnlimited); assertFalse(p.matches(spec))
        d.upsertPackage(api, spec) // التطبيق يعيد فرض العدد المحدد
        assertFalse(d.listPackages(api).single().sharedUnlimited)
    }

    @Test
    fun `manual package with only a foreign limitation is not orphaned`() = runBlocking {
        val api = FakeRouterApi(); val d = UserManV7Dialect()
        d.upsertPackage(api, spec)
        assertFalse(d.listPackages(api).single().limitationOrphaned)
        // باقة يدوية: قيد التطبيق غير موجود أصلاً، والمربوط قيد باسم آخر
        api.tables["/user-manager/limitation"]!!.single()["name"] = "manual-lim"
        api.tables["/user-manager/profile-limitation"]!!.single()["limitation"] = "manual-lim"
        val p = d.listPackages(api).single()
        assertFalse(p.limitationOrphaned)
        assertFalse(p.limitationLinked) // لا تطابق spec التطبيق، لكنها ليست ناقصة الإنشاء
        assertNotNull(p.uptimeSeconds)  // القيم تُقرأ من القيد المربوط فعلاً
    }
}

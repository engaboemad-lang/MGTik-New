package ps.mgtik.hotspot.data.userman

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.core.network.RouterApi
import ps.mgtik.hotspot.core.network.RouterCommandRejected
import ps.mgtik.hotspot.core.network.RouterConnectionType
import ps.mgtik.hotspot.domain.model.UserManConfig
import ps.mgtik.hotspot.domain.model.UserManMode
import ps.mgtik.hotspot.domain.usecase.UserManConfigValidator
import ps.mgtik.hotspot.domain.usecase.UserManField
import ps.mgtik.hotspot.domain.usecase.UserManFieldError

/** راوتر وهمي في الذاكرة: جداول بحسب المسار، مع أوامر محفوظة، ورفض اختياري لخاصية `customer` (لمحاكاة v5/v6 القديم). */
private class UmFakeApi : RouterApi {
    override val kind = RouterConnectionType.API
    override val port = 8728
    val tables = mutableMapOf<String, MutableList<MutableMap<String, String>>>()
    val commands = mutableListOf<Pair<String, Map<String, Any?>>>()
    var rejectCustomerOnRouterAdd = false
    private var seq = 0

    fun table(path: String) = tables.getOrPut(path) { mutableListOf() }
    fun row(path: String, vararg kv: Pair<String, String>) {
        table(path).add(mutableMapOf(*kv).also { it[".id"] = "*${++seq}" })
    }

    override suspend fun print(path: String, query: Map<String, String>, props: List<String>): List<JsonObject> =
        table(path).filter { r -> query.all { (k, v) -> r[k] == v } }.map { r -> JsonObject(r.mapValues { JsonPrimitive(it.value) }) }

    override suspend fun add(path: String, attrs: Map<String, Any?>) {
        if (rejectCustomerOnRouterAdd && path.endsWith("/router") && attrs.containsKey("customer")) {
            throw RouterCommandRejected("unknown parameter customer")
        }
        row(path, *attrs.map { it.key to it.value.toString() }.toTypedArray())
    }

    override suspend fun set(path: String, ref: String, attrs: Map<String, Any?>) {
        val r = table(path).firstOrNull { it[".id"] == ref || it["name"] == ref } ?: throw RouterCommandRejected("no such item")
        attrs.forEach { (k, v) -> r[k] = v.toString() }
    }

    override suspend fun setSingle(path: String, attrs: Map<String, Any?>) {
        val r = table(path).firstOrNull() ?: throw RouterCommandRejected("no such command")
        attrs.forEach { (k, v) -> r[k] = v.toString() }
    }

    override suspend fun remove(path: String, ref: String): Boolean = table(path).removeAll { it[".id"] == ref }
    override suspend fun action(path: String, action: String, ref: String?) = Unit
    override suspend fun command(path: String, attrs: Map<String, Any?>) { commands += path to attrs }
    override fun close() = Unit
}

class UserManSetupLogicTest {

    private val cfg = UserManConfig(radiusSecret = "s3cretKey")

    private fun v7Router() = UmFakeApi().apply {
        row("/user-manager", "enabled" to "false", "use-profiles" to "false")
        row("/ip/hotspot/profile", "name" to "default", "use-radius" to "false")
        row("/ip/hotspot/profile", "name" to "hsprof1", "use-radius" to "false")
    }

    private fun v6Router() = UmFakeApi().apply {
        row("/tool/user-manager/customer", "login" to "admin")
        row("/ip/hotspot/profile", "name" to "default", "use-radius" to "false")
    }

    // ───── التحقق ─────

    @Test fun validator_defaultsNeedOnlySecret() {
        val e = UserManConfigValidator.validate(UserManConfig())
        assertEquals(mapOf(UserManField.RADIUS_SECRET to UserManFieldError.REQUIRED), e)
        assertTrue(UserManConfigValidator.validate(UserManConfig(), requireSecret = false).isEmpty())
    }

    @Test fun validator_modeSkipsIrrelevantFields() {
        val badCustomer = cfg.copy(customer = "bad name!")
        assertTrue(UserManConfigValidator.validate(badCustomer.copy(mode = UserManMode.V7)).isEmpty())
        assertEquals(UserManFieldError.INVALID, UserManConfigValidator.validate(badCustomer.copy(mode = UserManMode.V6))[UserManField.CUSTOMER])
        val badNas = cfg.copy(nasName = "a b")
        assertTrue(UserManConfigValidator.validate(badNas.copy(mode = UserManMode.V6)).isEmpty())
        assertEquals(UserManFieldError.INVALID, UserManConfigValidator.validate(badNas.copy(mode = UserManMode.V7))[UserManField.NAS_NAME])
    }

    @Test fun validator_secretAndAddressRules() {
        fun f(c: UserManConfig) = UserManConfigValidator.validate(c)
        assertEquals(UserManFieldError.TOO_SHORT, f(cfg.copy(radiusSecret = "abc"))[UserManField.RADIUS_SECRET])
        assertEquals(UserManFieldError.INVALID, f(cfg.copy(radiusSecret = "ab cdefg"))[UserManField.RADIUS_SECRET])
        assertEquals(UserManFieldError.INVALID, f(cfg.copy(radiusSecret = "ab\"cdefg"))[UserManField.RADIUS_SECRET])
        assertEquals(UserManFieldError.TOO_LONG, f(cfg.copy(radiusSecret = "x".repeat(65)))[UserManField.RADIUS_SECRET])
        assertEquals(UserManFieldError.INVALID, f(cfg.copy(radiusAddress = "300.1.1.1"))[UserManField.RADIUS_ADDRESS])
        assertEquals(UserManFieldError.INVALID, f(cfg.copy(radiusAddress = "http://x"))[UserManField.RADIUS_ADDRESS])
        assertTrue(f(cfg.copy(radiusAddress = "radius.local")).isEmpty())
    }

    @Test fun generatedSecret_isValid() {
        repeat(20) {
            val s = UserManConfigValidator.generateSecret()
            assertEquals(16, s.length)
            assertTrue(UserManConfigValidator.validate(cfg.copy(radiusSecret = s)).isEmpty())
        }
    }

    // ───── المخطِّط ─────

    @Test fun planner_mapsFailedChecksToSteps() {
        val checks = listOf(
            UmCheck(UmCheckKeys.PACKAGE, true, ""), UmCheck(UmCheckKeys.ENABLED, false, ""),
            UmCheck(UmCheckKeys.SECRET_MATCH, false, ""), UmCheck(UmCheckKeys.USE_RADIUS, false, ""),
            UmCheck(UmCheckKeys.CUSTOMER, false, "")
        )
        assertEquals(setOf(UmStep.ENABLE, UmStep.NAS, UmStep.RADIUS, UmStep.HOTSPOT_PROFILES), UserManPlanner.stepsFor(checks))
        assertEquals(listOf(UmCheckKeys.CUSTOMER), UserManPlanner.manualChecks(checks).map { it.key })
        assertTrue(UserManPlanner.stepsFor(checks.map { it.copy(ok = true) }).isEmpty())
    }

    // ───── v7 ─────

    @Test fun v7_inspectThenProvisionThenReady_andIdempotent() = runBlocking {
        val api = v7Router(); val d = UserManV7Dialect()
        val before = d.inspect(api, cfg, "7.14.1")
        assertFalse(before.isReady)
        assertTrue(before.packageInstalled)
        assertEquals(setOf(UmStep.ENABLE, UmStep.NAS, UmStep.RADIUS, UmStep.HOTSPOT_PROFILES), UserManPlanner.stepsFor(before.checks))

        val steps = d.provision(api, cfg)
        assertEquals(listOf(UmAction.UPDATED, UmAction.CREATED, UmAction.CREATED, UmAction.UPDATED), steps.map { it.action })
        assertTrue(d.inspect(api, cfg, "7.14.1").isReady)

        // تشغيل ثانٍ: لا تغيير ولا تكرار سجلات
        assertTrue(d.provision(api, cfg).all { it.action == UmAction.UNCHANGED })
        assertEquals(1, api.table("/radius").size)
        assertEquals(1, api.table("/user-manager/router").size)
    }

    @Test fun v7_secretMismatchDetectedAndRepaired() = runBlocking {
        val api = v7Router(); val d = UserManV7Dialect()
        d.provision(api, cfg)
        api.table("/radius").first()["secret"] = "other-secret"
        val s = d.inspect(api, cfg, "7.14")
        assertFalse(s.checks.first { it.key == UmCheckKeys.SECRET_MATCH }.ok)
        d.provision(api, cfg)
        assertTrue(d.inspect(api, cfg, "7.14").isReady)
    }

    @Test fun v7_missingPackageReportsNotInstalled() = runBlocking {
        val s = UserManV7Dialect().inspect(UmFakeApi(), cfg, "7.14")
        assertFalse(s.packageInstalled); assertFalse(s.isReady)
        assertEquals(listOf(UmCheckKeys.PACKAGE), UserManPlanner.manualChecks(s.checks).map { it.key })
    }

    @Test fun v7_backupAndOptimize() = runBlocking {
        val api = v7Router(); val d = UserManV7Dialect()
        d.backupDatabase(api, "bk1")
        assertEquals("/user-manager/database/save" to mapOf<String, Any?>("name" to "bk1", "overwrite" to "yes"), api.commands.last())
        assertTrue(d.optimizeDatabase(api))
        assertEquals("/user-manager/database/optimize-db", api.commands.last().first)
    }

    // ───── v6 ─────

    @Test fun v6_provisionAndReady() = runBlocking {
        val api = v6Router(); val d = UserManV6Dialect("admin")
        val before = d.inspect(api, cfg, "6.49.10")
        assertTrue(before.packageInstalled); assertFalse(before.isReady)
        assertEquals(null, before.enabled)
        val steps = d.provision(api, cfg)
        // v6 بلا خطوة تفعيل
        assertEquals(listOf(UmStep.NAS, UmStep.RADIUS, UmStep.HOTSPOT_PROFILES), steps.map { it.step })
        assertTrue(d.inspect(api, cfg, "6.49.10").isReady)
        assertEquals("admin", api.table("/tool/user-manager/router").single()["customer"])
    }

    @Test fun v6_routerAddFallsBackToSubscriberOnUnknownParameter() = runBlocking {
        val api = v6Router().apply { rejectCustomerOnRouterAdd = true }
        val steps = UserManV6Dialect("admin").provision(api, cfg)
        assertEquals(UmAction.CREATED, steps.first { it.step == UmStep.NAS }.action)
        assertEquals("admin", api.table("/tool/user-manager/router").single()["subscriber"])
    }

    @Test fun v6_missingCustomerIsManualCheck() = runBlocking {
        val api = v6Router().apply { tables["/tool/user-manager/customer"]!!.clear() }
        val s = UserManV6Dialect("admin").inspect(api, cfg, "6.48")
        assertEquals(listOf(UmCheckKeys.CUSTOMER), UserManPlanner.manualChecks(s.checks).map { it.key })
    }

    @Test fun v6_optimizeUnsupported() = runBlocking {
        val api = v6Router()
        assertFalse(UserManV6Dialect("admin").optimizeDatabase(api))
        assertTrue(api.commands.isEmpty())
    }

    // ───── بقايا الحدود ─────

    @Test fun orphanLimitations_foundAndRemovedOnlyIfPrefixed() = runBlocking {
        val api = v7Router(); val d = UserManV7Dialect()
        api.row("/user-manager/profile", "name" to "gold")
        api.row("/user-manager/limitation", "name" to "mgtik-gold")
        api.row("/user-manager/limitation", "name" to "mgtik-deleted")
        api.row("/user-manager/limitation", "name" to "manual-limit")
        api.row("/user-manager/profile-limitation", "limitation" to "mgtik-deleted", "profile" to "deleted")

        assertEquals(listOf("mgtik-deleted"), d.orphanLimitations(api))
        // حماية: اسم بلا بادئة لا يُحذف حتى لو مرّر المستدعي ذلك
        assertEquals(1, d.removeLimitations(api, listOf("mgtik-deleted", "manual-limit")))
        assertEquals(listOf("mgtik-gold", "manual-limit"), api.table("/user-manager/limitation").map { it["name"] })
        assertTrue(api.table("/user-manager/profile-limitation").isEmpty())
        assertTrue(d.orphanLimitations(api).isEmpty())
    }

    @Test fun listPackages_readsLinkedLimitationAndExposesForeign() = kotlinx.coroutines.runBlocking {
        val api = v7Router(); val d = UserManV7Dialect()
        api.row("/user-manager/profile", "name" to "gold", "override-shared-users" to "2")
        api.row("/user-manager/profile", "name" to "manual", "override-shared-users" to "1")
        api.row("/user-manager/limitation", "name" to "mgtik-gold", "rate-limit-rx" to "1000k", "rate-limit-tx" to "5000k")
        api.row("/user-manager/limitation", "name" to "extra", "rate-limit-rx" to "64k")
        api.row("/user-manager/limitation", "name" to "hand", "rate-limit-rx" to "2000k", "rate-limit-tx" to "8000k")
        api.row("/user-manager/profile-limitation", "profile" to "gold", "limitation" to "mgtik-gold")
        api.row("/user-manager/profile-limitation", "profile" to "gold", "limitation" to "extra")
        api.row("/user-manager/profile-limitation", "profile" to "manual", "limitation" to "hand")

        val pk = d.listPackages(api).associateBy { it.name }
        assertEquals(5000, pk.getValue("gold").txKbps)
        assertEquals(listOf("extra"), pk.getValue("gold").foreignLimitations)
        assertEquals(8000, pk.getValue("manual").txKbps)
        assertTrue(pk.getValue("manual").foreignLimitations.isEmpty())
    }

    @Test fun usersOnPackage_countsOnlyAssignedUsers() = kotlinx.coroutines.runBlocking {
        val api = v7Router(); val d = UserManV7Dialect()
        api.row("/user-manager/user", "name" to "a"); api.row("/user-manager/user", "name" to "b")
        api.row("/user-manager/user-profile", "user" to "a", "profile" to "gold", "state" to "running-active")
        val on = d.usersOnPackage(api, "gold")
        assertEquals(listOf("a"), on)
        assertTrue(d.usersOnPackage(api, "silver").isEmpty())
    }

    @Test fun v7_startModeAndUsageLimitPolicy_andRenewalAddsAssignment() = runBlocking {
        val api = v7Router(); val d = UserManV7Dialect()
        d.upsertPackage(api, UmPackageSpec("sub", uptimeSeconds = 3600, transferBytes = 1_000_000, validitySeconds = 86400,
            startMode = UmStartMode.ON_ASSIGN, pushUsageLimits = false))
        assertEquals("assigned", api.table("/user-manager/profile").single()["starts-when"])
        val lim = api.table("/user-manager/limitation").single()
        assertNull(lim["uptime-limit"]); assertNull(lim["transfer-limit"]) // pushUsageLimits=false: لا يُرسل شيء

        d.upsertPackage(api, UmPackageSpec("sub", uptimeSeconds = 3600, transferBytes = 1_000_000, validitySeconds = 86400))
        assertEquals("first-auth", api.table("/user-manager/profile").single()["starts-when"])
        assertEquals("3600s", api.table("/user-manager/limitation").single()["uptime-limit"])

        d.activatePackage(api, "u1", "sub"); d.activatePackage(api, "u1", "sub", renew = true)
        assertEquals(2, api.table("/user-manager/user-profile").size) // التجديد = إسناد جديد لا تعديل القديم
    }

    @Test fun v6_startModeMapsToLogonOrNow() = runBlocking {
        val api = v6Router(); val d = UserManV6Dialect("admin")
        d.upsertPackage(api, UmPackageSpec("p", startMode = UmStartMode.ON_ASSIGN))
        assertEquals("now", api.table("/tool/user-manager/profile").single()["starts-at"])
        d.upsertPackage(api, UmPackageSpec("p"))
        assertEquals("logon", api.table("/tool/user-manager/profile").single()["starts-at"])
    }

    @Test fun v7_nullKeepsExistingAndZeroClearsAndValidityNeverSendsDefaultZero() = runBlocking {
        val api = v7Router(); val d = UserManV7Dialect()
        d.upsertPackage(api, UmPackageSpec("k", uptimeSeconds = 3600, transferBytes = 5_000, validitySeconds = 86400, sharedUsers = 3))
        d.upsertPackage(api, UmPackageSpec("k")) // كلها null: لا تُلمس
        val lim = api.table("/user-manager/limitation").single()
        assertEquals("3600s", lim["uptime-limit"]); assertEquals("5000", lim["transfer-limit"])
        assertEquals("86400s", api.table("/user-manager/profile").single()["validity"])
        d.upsertPackage(api, UmPackageSpec("k", uptimeSeconds = 0L, transferBytes = 0L, validityUnlimited = true, sharedUnlimited = true))
        assertEquals("0s", api.table("/user-manager/limitation").single()["uptime-limit"])
        val prof = api.table("/user-manager/profile").single()
        assertEquals("0s", prof["validity"]); assertEquals("unlimited", prof["override-shared-users"])
    }

    @Test fun v7_explicitLimitationNameIsUsedAndNotDerived() = runBlocking {
        val api = v7Router(); val d = UserManV7Dialect()
        d.upsertPackage(api, UmPackageSpec("p1", limitationName = "shared-lim"))
        assertEquals("shared-lim", api.table("/user-manager/limitation").single()["name"])
        assertEquals("shared-lim", api.table("/user-manager/profile-limitation").single()["limitation"])
    }
}

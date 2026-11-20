package ps.mgtik.hotspot.data.userman

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ps.mgtik.hotspot.core.network.RouterApi
import ps.mgtik.hotspot.core.network.RouterCommandRejected
import ps.mgtik.hotspot.core.network.RouterConnectionType

/** راوتر وهمي مع حقن أعطال: [failWhen] يُستدعى قبل كل كتابة (add/set/remove) ويرمي رفضاً إن أعاد true. */
internal class CompRouter : RouterApi {
    override val kind = RouterConnectionType.API
    override val port = 8728
    val tables = mutableMapOf<String, MutableList<MutableMap<String, String>>>()
    var failWhen: (op: String, path: String, attrs: Map<String, Any?>) -> Boolean = { _, _, _ -> false }
    /** RouterOS يحدّث مراجع الروابط عند إعادة التسمية (المراجع بالمعرّف داخلياً)؛ false يحاكي راوتراً لا يفعل ذلك. */
    var renameUpdatesRefs = true
    private var seq = 0
    val writes = mutableListOf<String>()

    fun table(path: String) = tables.getOrPut(path) { mutableListOf() }
    fun rows(path: String) = table(path).map { it.toMap() }
    fun seed(path: String, vararg kv: Pair<String, String>) { table(path).add((kv.toMap() + (".id" to "*${++seq}")).toMutableMap()) }

    private fun guard(op: String, path: String, attrs: Map<String, Any?>) {
        if (failWhen(op, path, attrs)) throw RouterCommandRejected("failure: injected $op on $path")
        writes += "$op $path"
    }

    override suspend fun print(path: String, query: Map<String, String>, props: List<String>): List<JsonObject> =
        table(path).filter { row -> query.all { (k, v) -> row[k] == v } }.map { row -> JsonObject(row.mapValues { JsonPrimitive(it.value) }) }

    override suspend fun add(path: String, attrs: Map<String, Any?>) {
        guard("add", path, attrs)
        val row = attrs.mapValues { it.value.toString() }.toMutableMap()
        row[".id"] = "*${++seq}"
        table(path).add(row)
    }

    override suspend fun set(path: String, ref: String, attrs: Map<String, Any?>) {
        guard("set", path, attrs)
        val row = table(path).first { it[".id"] == ref }
        val oldName = row["name"]
        attrs.forEach { (k, v) -> row[k] = v.toString() }
        val newName = row["name"]
        if (renameUpdatesRefs && oldName != null && newName != null && oldName != newName) {
            val field = if (path.endsWith("/limitation")) "limitation" else if (path.endsWith("/profile")) "profile" else null
            if (field != null) tables.filterKeys { it.endsWith("profile-limitation") }.values.flatten().forEach { if (it[field] == oldName) it[field] = newName }
        }
    }

    override suspend fun remove(path: String, ref: String): Boolean { guard("remove", path, emptyMap()); return table(path).removeAll { it[".id"] == ref } }
    override suspend fun setSingle(path: String, attrs: Map<String, Any?>) = Unit
    override suspend fun action(path: String, action: String, ref: String?) = Unit
    override suspend fun command(path: String, attrs: Map<String, Any?>) = Unit
    override fun close() = Unit
}

class UserManComponentsTest {
    private val v7 = UserManV7Dialect()
    private val v6 = UserManV6Dialect("admin")
    private val L7 = "/user-manager/limitation"
    private val P7 = "/user-manager/profile"
    private val PL7 = "/user-manager/profile-limitation"
    private val L6 = "/tool/user-manager/profile/limitation"
    private val P6 = "/tool/user-manager/profile"
    private val PL6 = "/tool/user-manager/profile/profile-limitation"

    private fun CompRouter.limOf(name: String, path: String = L7) = table(path).first { it["name"] == name }
    private fun CompRouter.links(path: String = PL7) = table(path).map { it["profile"] to it["limitation"] }

    // ───────────── مكونات مستقلة ─────────────

    @Test
    fun `limitation and profile are created independently with manual names and no derived names`() = runBlocking {
        val api = CompRouter()
        v7.createLimitation(api, UmLimitationSpec("fast-line", rxKbps = 2048, txKbps = 4096, uptimeSeconds = 3_600, transferBytes = 1_073_741_824L))
        v7.createProfile(api, UmProfileSpec("gold", nameForUsers = "Gold", validitySeconds = 86_400, sharedUsers = 2, startMode = UmStartMode.ON_ASSIGN))
        assertEquals(listOf("fast-line"), api.table(L7).map { it["name"] })
        assertEquals(listOf("gold"), api.table(P7).map { it["name"] })
        assertTrue("no link is created implicitly", api.table(PL7).isEmpty())
        assertEquals("assigned", api.table(P7).single()["starts-when"])
        assertEquals("86400s", api.table(P7).single()["validity"])
        assertEquals("2", api.table(P7).single()["override-shared-users"])
        assertEquals("2048k", api.limOf("fast-line")["rate-limit-rx"])
    }

    @Test
    fun `creating an existing component is rejected instead of silently replaced`() = runBlocking {
        val api = CompRouter(); v7.createLimitation(api, UmLimitationSpec("x", rxKbps = 1))
        try { v7.createLimitation(api, UmLimitationSpec("x", rxKbps = 999)); fail() } catch (e: RouterCommandRejected) { assertTrue(e.routerMessage.contains("already have")) }
        assertEquals("1k", api.limOf("x")["rate-limit-rx"])
        v7.createProfile(api, UmProfileSpec("p"))
        try { v7.createProfile(api, UmProfileSpec("p")); fail() } catch (e: RouterCommandRejected) { }
    }

    @Test
    fun `updating a limitation touches only the supplied fields`() = runBlocking {
        val api = CompRouter()
        v7.createLimitation(api, UmLimitationSpec("l", rxKbps = 512, txKbps = 1024, uptimeSeconds = 7_200, transferBytes = 5_000_000L))
        assertTrue(v7.updateLimitation(api, UmLimitationSpec("l", txKbps = 2048)))
        val row = api.limOf("l")
        assertEquals("512k", row["rate-limit-rx"]); assertEquals("2048k", row["rate-limit-tx"])
        assertEquals("7200s", row["uptime-limit"]); assertEquals("5000000", row["transfer-limit"])
        assertFalse(v7.updateLimitation(api, UmLimitationSpec("missing", rxKbps = 1)))
        // 0 = إزالة صريحة فقط عند طلبها
        v7.updateLimitation(api, UmLimitationSpec("l", uptimeSeconds = 0))
        assertEquals("0s", api.limOf("l")["uptime-limit"])
    }

    @Test
    fun `updating a profile with null fields keeps validity and shared users as they are`() = runBlocking {
        val api = CompRouter()
        v7.createProfile(api, UmProfileSpec("p", validitySeconds = 3_600, sharedUsers = 5, startMode = UmStartMode.FIRST_AUTH))
        v7.updateProfile(api, UmProfileSpec("p", nameForUsers = "Display"))
        val r = api.table(P7).single()
        assertEquals("3600s", r["validity"]); assertEquals("5", r["override-shared-users"]); assertEquals("first-auth", r["starts-when"]); assertEquals("Display", r["name-for-users"])
    }

    @Test
    fun `renaming a limitation keeps its links and refuses a taken name`() = runBlocking {
        val api = CompRouter()
        v7.createLimitation(api, UmLimitationSpec("a")); v7.createLimitation(api, UmLimitationSpec("b"))
        v7.createProfile(api, UmProfileSpec("p1")); v7.createProfile(api, UmProfileSpec("p2"))
        v7.linkLimitation(api, "p1", "a"); v7.linkLimitation(api, "p2", "a")
        assertTrue(v7.renameLimitation(api, "a", "a2"))
        assertEquals(setOf("p1" to "a2", "p2" to "a2"), api.links().toSet())
        try { v7.renameLimitation(api, "a2", "b"); fail() } catch (e: RouterCommandRejected) { }
        assertFalse(v7.renameLimitation(api, "ghost", "z"))
    }

    @Test
    fun `renaming a profile keeps limitation links and does not rename shared limitations`() = runBlocking {
        val api = CompRouter()
        v7.createLimitation(api, UmLimitationSpec("shared")); v7.createProfile(api, UmProfileSpec("old", nameForUsers = "old"))
        v7.linkLimitation(api, "old", "shared")
        assertTrue(v7.renameProfile(api, "old", "new", syncNameForUsers = true))
        assertEquals(listOf("new" to "shared"), api.links())
        assertEquals("new", api.table(P7).single()["name-for-users"])
    }

    @Test
    fun `setProfileLimitations reconciles exactly and never deletes a limitation`() = runBlocking {
        val api = CompRouter()
        listOf("a", "b", "c").forEach { v7.createLimitation(api, UmLimitationSpec(it)) }
        v7.createProfile(api, UmProfileSpec("p"))
        assertEquals(UmLinkChange(listOf("a", "b"), emptyList()), v7.setProfileLimitations(api, "p", listOf("a", "b")))
        val ch = v7.setProfileLimitations(api, "p", listOf("b", "c"))
        assertEquals(listOf("c"), ch.added); assertEquals(listOf("a"), ch.removed)
        assertEquals(3, api.table(L7).size)
        assertEquals(UmLinkChange(emptyList(), emptyList()), v7.setProfileLimitations(api, "p", listOf("b", "c")))
        try { v7.setProfileLimitations(api, "p", listOf("ghost")); fail() } catch (e: RouterCommandRejected) { }
    }

    @Test
    fun `removing a profile keeps its limitations and removing a limitation keeps its profiles`() = runBlocking {
        val api = CompRouter()
        v7.createLimitation(api, UmLimitationSpec("l")); v7.createProfile(api, UmProfileSpec("p1")); v7.createProfile(api, UmProfileSpec("p2"))
        v7.linkLimitation(api, "p1", "l"); v7.linkLimitation(api, "p2", "l")
        assertTrue(v7.removeProfile(api, "p1"))
        assertEquals(1, api.table(L7).size); assertEquals(listOf("p2" to "l"), api.links())
        assertTrue(v7.removeLimitation(api, "l"))
        assertEquals(listOf("p2"), api.table(P7).map { it["name"] }); assertTrue(api.table(PL7).isEmpty())
        assertFalse(v7.removeProfile(api, "ghost"))
    }

    @Test
    fun `graph reads profiles limitations and users`() = runBlocking {
        val api = CompRouter()
        v7.createLimitation(api, UmLimitationSpec("l", rxKbps = 100)); v7.createProfile(api, UmProfileSpec("p", validityUnlimited = true, sharedUnlimited = true))
        v7.linkLimitation(api, "p", "l")
        api.seed("/user-manager/user-profile", "user" to "u1", "profile" to "p")
        api.seed("/user-manager/user-profile", "user" to "u2", "profile" to "p")
        val g = v7.readGraph(api)
        assertEquals(listOf("l"), g.profile("p")!!.limitations)
        assertTrue(g.profile("p")!!.validityUnlimited); assertTrue(g.profile("p")!!.sharedUnlimited)
        assertEquals(100, g.limitation("l")!!.rxKbps)
        assertEquals(setOf("u1", "u2"), g.usersByProfile["p"]!!.toSet())
    }

    // ───────────── ربط الباقة بمكونات موجودة ─────────────

    @Test
    fun `package with referenced profile writes nothing and fails if the profile is missing`() = runBlocking {
        val api = CompRouter(); v7.createProfile(api, UmProfileSpec("shared-profile", validitySeconds = 600, sharedUsers = 3))
        api.writes.clear()
        val log = UmOpLog()
        v7.upsertPackage(api, UmPackageSpec(name = "shared-profile", rxKbps = 999, profileMode = UmComponentMode.REFERENCE), log)
        assertTrue("reference mode never writes", api.writes.isEmpty())
        assertEquals(UmStepAction.REUSED, log.entries.single().action)
        val log2 = UmOpLog()
        try { v7.upsertPackage(api, UmPackageSpec(name = "ghost", profileMode = UmComponentMode.REFERENCE), log2); fail() } catch (e: RouterCommandRejected) { }
        assertTrue(log2.failed)
    }

    @Test
    fun `package reuses an existing limitation without overwriting it and links it`() = runBlocking {
        val api = CompRouter(); v7.createLimitation(api, UmLimitationSpec("corp-line", rxKbps = 4096, txKbps = 8192, uptimeSeconds = 99))
        val spec = UmPackageSpec(name = "pkg-a", rxKbps = 1, txKbps = 1, sharedUsers = 2, validitySeconds = 86_400,
            limitationNames = listOf("corp-line"), limitationMode = UmComponentMode.REFERENCE)
        v7.upsertPackage(api, spec); v7.upsertPackage(api, spec) // إعادة المحاولة لا تكرر شيئاً
        assertEquals("4096k", api.limOf("corp-line")["rate-limit-rx"]); assertEquals("99s", api.limOf("corp-line")["uptime-limit"])
        assertEquals(1, api.table(L7).size); assertEquals(1, api.table(P7).size)
        assertEquals(listOf("pkg-a" to "corp-line"), api.links())
        assertNull("derived mgtik-* limitation must not be created", api.table(L7).firstOrNull { it["name"] == "mgtik-pkg-a" })
        assertTrue(v7.verifyPackage(api, spec).ok)
    }

    @Test
    fun `two packages can share one profile and one limitation without duplicates`() = runBlocking {
        val api = CompRouter()
        v7.createLimitation(api, UmLimitationSpec("l", rxKbps = 10)); v7.createProfile(api, UmProfileSpec("p", sharedUsers = 1))
        v7.linkLimitation(api, "p", "l")
        val ref = UmPackageSpec(name = "p", profileMode = UmComponentMode.REFERENCE)
        v7.upsertPackage(api, ref); v7.upsertPackage(api, ref.copy(rxKbps = 5))
        assertEquals(1, api.table(P7).size); assertEquals(1, api.table(L7).size); assertEquals(1, api.table(PL7).size)
    }

    @Test
    fun `explicit limitation list is exact and several limitations can be linked`() = runBlocking {
        val api = CompRouter()
        listOf("speed", "quota", "old").forEach { v7.createLimitation(api, UmLimitationSpec(it)) }
        v7.createProfile(api, UmProfileSpec("p")); v7.linkLimitation(api, "p", "old")
        val spec = UmPackageSpec(name = "p", limitationNames = listOf("speed", "quota"), limitationMode = UmComponentMode.REFERENCE)
        v7.upsertPackage(api, spec)
        assertEquals(setOf("p" to "speed", "p" to "quota"), api.links().toSet())
        assertEquals("the unlinked limitation itself is never deleted", 3, api.table(L7).size)
        assertTrue(v7.verifyPackage(api, spec).ok)
    }

    @Test
    fun `legacy spec still creates the derived limitation and keeps foreign links`() = runBlocking {
        val api = CompRouter(); v7.createLimitation(api, UmLimitationSpec("admin-made"))
        v7.createProfile(api, UmProfileSpec("legacy")); v7.linkLimitation(api, "legacy", "admin-made")
        v7.upsertPackage(api, UmPackageSpec(name = "legacy", rxKbps = 256, txKbps = 512))
        assertEquals(setOf("legacy" to "admin-made", "legacy" to "mgtik-legacy"), api.links().toSet())
    }

    // ───────────── الفشل الجزئي ─────────────

    @Test
    fun `limitation failure stops before the profile is created`() = runBlocking {
        val api = CompRouter(); api.failWhen = { op, path, _ -> op == "add" && path == L7 }
        val log = UmOpLog()
        try { v7.upsertPackage(api, UmPackageSpec(name = "n", rxKbps = 1), log); fail() } catch (e: RouterCommandRejected) { }
        assertTrue(api.table(P7).isEmpty()); assertTrue(api.table(L7).isEmpty()); assertTrue(log.failed)
    }

    @Test
    fun `profile failure rolls back the limitation this call created`() = runBlocking {
        val api = CompRouter(); api.failWhen = { op, path, _ -> op == "add" && path == P7 }
        val log = UmOpLog()
        try { v7.upsertPackage(api, UmPackageSpec(name = "n", rxKbps = 1), log); fail() } catch (e: RouterCommandRejected) { }
        assertTrue("no orphan limitation", api.table(L7).isEmpty()); assertTrue(api.table(PL7).isEmpty())
        assertTrue(log.failed && log.rolledBack && !log.rollbackIncomplete)
        // إعادة المحاولة بعد زوال العطل تنجح بلا تكرار
        api.failWhen = { _, _, _ -> false }
        v7.upsertPackage(api, UmPackageSpec(name = "n", rxKbps = 1))
        assertEquals(1, api.table(L7).size); assertEquals(1, api.table(P7).size); assertEquals(1, api.table(PL7).size)
    }

    @Test
    fun `profile failure never deletes a pre-existing shared limitation and restores its values`() = runBlocking {
        val api = CompRouter(); v7.createLimitation(api, UmLimitationSpec("shared", rxKbps = 100, txKbps = 200, uptimeSeconds = 60))
        v7.createProfile(api, UmProfileSpec("other")); v7.linkLimitation(api, "other", "shared")
        api.failWhen = { op, path, _ -> op == "add" && path == P7 }
        try { v7.upsertPackage(api, UmPackageSpec(name = "new-pkg", rxKbps = 999, txKbps = 999, uptimeSeconds = 5, limitationNames = listOf("shared")), UmOpLog()); fail() } catch (e: RouterCommandRejected) { }
        assertEquals(1, api.table(L7).size)
        val row = api.limOf("shared")
        assertEquals("100k", row["rate-limit-rx"]); assertEquals("200k", row["rate-limit-tx"]); assertEquals("60s", row["uptime-limit"])
        assertEquals(listOf("other" to "shared"), api.links())
    }

    @Test
    fun `link failure rolls back created profile and limitation`() = runBlocking {
        val api = CompRouter(); api.failWhen = { op, path, _ -> op == "add" && path == PL7 }
        try { v7.upsertPackage(api, UmPackageSpec(name = "n", rxKbps = 1), UmOpLog()); fail() } catch (e: RouterCommandRejected) { }
        assertTrue(api.table(L7).isEmpty() && api.table(P7).isEmpty() && api.table(PL7).isEmpty())
    }

    @Test
    fun `failed update restores the previous profile values`() = runBlocking {
        val api = CompRouter()
        v7.upsertPackage(api, UmPackageSpec(name = "p", rxKbps = 100, txKbps = 100, sharedUsers = 2, validitySeconds = 3_600))
        api.failWhen = { op, path, _ -> op == "set" && path == P7 }
        try { v7.upsertPackage(api, UmPackageSpec(name = "p", rxKbps = 777, txKbps = 777, sharedUsers = 9, validitySeconds = 7_200)); fail() } catch (e: RouterCommandRejected) { }
        api.failWhen = { _, _, _ -> false }
        assertEquals("100k", api.limOf("mgtik-p")["rate-limit-rx"])
        assertEquals("2", api.table(P7).single()["override-shared-users"]); assertEquals("3600s", api.table(P7).single()["validity"])
    }

    // ───────────── pushUsageLimits والحقول غير المكتوبة ─────────────

    private fun externalLimitedPackage(api: CompRouter) {
        api.seed(L7, "name" to "mgtik-p", "rate-limit-rx" to "100k", "rate-limit-tx" to "100k", "uptime-limit" to "3600s", "transfer-limit" to "5000000")
        api.seed(P7, "name" to "p", "name-for-users" to "p", "validity" to "86400s", "override-shared-users" to "4", "starts-when" to "assigned")
        api.seed(PL7, "profile" to "p", "limitation" to "mgtik-p")
    }

    @Test
    fun `pushUsageLimits=false keeps existing time and transfer limits untouched`() = runBlocking {
        val api = CompRouter(); externalLimitedPackage(api)
        v7.upsertPackage(api, UmPackageSpec(name = "p", rxKbps = 200, txKbps = 200, uptimeSeconds = 0, transferBytes = 0, pushUsageLimits = false))
        val r = api.limOf("mgtik-p")
        assertEquals("200k", r["rate-limit-rx"]); assertEquals("3600s", r["uptime-limit"]); assertEquals("5000000", r["transfer-limit"])
    }

    @Test
    fun `pushUsageLimits=true with null limit values also leaves the router limits alone`() = runBlocking {
        val api = CompRouter(); externalLimitedPackage(api)
        v7.upsertPackage(api, UmPackageSpec(name = "p", rxKbps = 100, txKbps = 100, uptimeSeconds = null, transferBytes = null, pushUsageLimits = true))
        assertEquals("3600s", api.limOf("mgtik-p")["uptime-limit"]); assertEquals("5000000", api.limOf("mgtik-p")["transfer-limit"])
    }

    @Test
    fun `explicit zero removes a limit only when pushUsageLimits is true`() = runBlocking {
        val api = CompRouter(); externalLimitedPackage(api)
        v7.upsertPackage(api, UmPackageSpec(name = "p", rxKbps = 100, txKbps = 100, uptimeSeconds = 0, transferBytes = null, pushUsageLimits = true))
        assertEquals("0s", api.limOf("mgtik-p")["uptime-limit"]); assertEquals("5000000", api.limOf("mgtik-p")["transfer-limit"])
    }

    @Test
    fun `writes subset leaves every other field as on the router`() = runBlocking {
        val api = CompRouter(); externalLimitedPackage(api)
        v7.upsertPackage(api, UmPackageSpec(name = "p", rxKbps = 300, txKbps = 300, sharedUsers = 1, validitySeconds = 60, uptimeSeconds = 0, transferBytes = 0,
            startMode = UmStartMode.FIRST_AUTH, writes = setOf(UmWrite.SPEED)))
        val lim = api.limOf("mgtik-p"); val prof = api.table(P7).single()
        assertEquals("300k", lim["rate-limit-rx"]); assertEquals("3600s", lim["uptime-limit"]); assertEquals("5000000", lim["transfer-limit"])
        assertEquals("4", prof["override-shared-users"]); assertEquals("86400s", prof["validity"]); assertEquals("assigned", prof["starts-when"])
    }

    @Test
    fun `validity is never written as 0s unless unlimited was chosen explicitly`() = runBlocking {
        val api = CompRouter()
        v7.upsertPackage(api, UmPackageSpec(name = "a", rxKbps = 1, validitySeconds = null))
        assertNull(api.table(P7).single()["validity"])
        v7.upsertPackage(api, UmPackageSpec(name = "b", rxKbps = 1, validitySeconds = 0L))
        assertNull(api.table(P7).first { it["name"] == "b" }["validity"])
        v7.upsertPackage(api, UmPackageSpec(name = "c", rxKbps = 1, validityUnlimited = true))
        assertEquals("0s", api.table(P7).first { it["name"] == "c" }["validity"])
    }

    // ───────────── الجيلان ─────────────

    @Test
    fun `v6 uses starts-at and owner while v7 uses starts-when`() = runBlocking {
        val a6 = CompRouter(); val a7 = CompRouter()
        v6.upsertPackage(a6, UmPackageSpec(name = "p", rxKbps = 1, startMode = UmStartMode.ON_ASSIGN))
        v7.upsertPackage(a7, UmPackageSpec(name = "p", rxKbps = 1, startMode = UmStartMode.ON_ASSIGN))
        assertEquals("now", a6.table(P6).single()["starts-at"]); assertEquals("admin", a6.table(P6).single()["owner"]); assertEquals("admin", a6.table(L6).single()["owner"])
        assertEquals("assigned", a7.table(P7).single()["starts-when"]); assertNull(a7.table(P7).single()["owner"])
        v6.upsertPackage(a6, UmPackageSpec(name = "p", rxKbps = 1, startMode = UmStartMode.FIRST_AUTH))
        assertEquals("logon", a6.table(P6).single()["starts-at"])
        assertEquals(UmStartMode.FIRST_AUTH, v6.listProfiles(a6).single().startMode)
        assertEquals(UmStartMode.ON_ASSIGN, v7.listProfiles(a7).single().startMode)
    }

    @Test
    fun `v6 independent components are linked through profile-limitation`() = runBlocking {
        val api = CompRouter()
        v6.createLimitation(api, UmLimitationSpec("l", rxKbps = 64)); v6.createProfile(api, UmProfileSpec("p"))
        v6.linkLimitation(api, "p", "l")
        assertEquals(listOf("p" to "l"), api.links(PL6))
        assertEquals(listOf("l"), v6.listProfiles(api).single().limitations)
    }

    // ───────────── التحقق بعد القراءة ─────────────

    @Test
    fun `verification passes after a write and flags an external change`() = runBlocking {
        val api = CompRouter()
        val spec = UmPackageSpec(name = "p", rxKbps = 512, txKbps = 1024, sharedUsers = 3, validitySeconds = 172_800, uptimeSeconds = 7_200, transferBytes = 1_073_741_824L, startMode = UmStartMode.ON_ASSIGN)
        v7.upsertPackage(api, spec)
        assertTrue(v7.verifyPackage(api, spec).describe(), v7.verifyPackage(api, spec).ok)
        api.table(P7).single()["override-shared-users"] = "9"
        api.limOf("mgtik-p")["rate-limit-rx"] = "1M"
        val v = v7.verifyPackage(api, spec)
        assertEquals(setOf("shared-users", "rate-limit-rx"), v.mismatches.map { it.field }.toSet())
    }

    @Test
    fun `verification understands router display formats`() = runBlocking {
        val api = CompRouter()
        api.seed(L7, "name" to "mgtik-p", "rate-limit-rx" to "1M", "rate-limit-tx" to "2M", "uptime-limit" to "2h", "transfer-limit" to "1GiB")
        api.seed(P7, "name" to "p", "validity" to "1d", "override-shared-users" to "2", "starts-when" to "first-auth")
        api.seed(PL7, "profile" to "p", "limitation" to "mgtik-p")
        val spec = UmPackageSpec(name = "p", rxKbps = 1000, txKbps = 2000, sharedUsers = 2, validitySeconds = 86_400, uptimeSeconds = 7_200, transferBytes = 1_073_741_824L)
        assertTrue(v7.verifyPackage(api, spec).describe(), v7.verifyPackage(api, spec).ok)
    }

    @Test
    fun `verification reports a missing limitation link`() = runBlocking {
        val api = CompRouter(); v7.upsertPackage(api, UmPackageSpec(name = "p", rxKbps = 1))
        api.table(PL7).clear()
        assertTrue(v7.verifyPackage(api, UmPackageSpec(name = "p", rxKbps = 1)).mismatches.any { it.field == "limitations" })
    }

    // ───────────── الحذف الآمن للباقة ─────────────

    @Test
    fun `deleting a package keeps a derived limitation that another profile still links`() = runBlocking {
        val api = CompRouter(); v7.upsertPackage(api, UmPackageSpec(name = "p", rxKbps = 1))
        v7.createProfile(api, UmProfileSpec("other")); v7.linkLimitation(api, "other", "mgtik-p")
        assertTrue(v7.deletePackage(api, "p"))
        assertNotNull(api.table(L7).firstOrNull { it["name"] == "mgtik-p" })
        assertEquals(listOf("other" to "mgtik-p"), api.links())
    }

    @Test
    fun `renaming a package leaves a shared derived limitation under its old name`() = runBlocking {
        val api = CompRouter(); v7.upsertPackage(api, UmPackageSpec(name = "old", rxKbps = 1))
        v7.createProfile(api, UmProfileSpec("other")); v7.linkLimitation(api, "other", "mgtik-old")
        assertTrue(v7.renamePackage(api, "old", "new"))
        assertEquals(setOf("new" to "mgtik-old", "other" to "mgtik-old"), api.links().toSet())
        assertEquals(1, api.table(L7).size)
    }

    @Test
    fun `renaming a package renames its private derived limitation and keeps the link`() = runBlocking {
        val api = CompRouter(); v7.upsertPackage(api, UmPackageSpec(name = "old", rxKbps = 1))
        assertTrue(v7.renamePackage(api, "old", "new"))
        assertEquals(listOf("new" to "mgtik-new"), api.links())
    }

    @Test
    fun `rename repairs links when the router does not update references`() = runBlocking {
        val api = CompRouter().also { it.renameUpdatesRefs = false }
        v7.createLimitation(api, UmLimitationSpec("a")); v7.createProfile(api, UmProfileSpec("p")); v7.linkLimitation(api, "p", "a")
        v7.renameLimitation(api, "a", "a2")
        assertTrue(("p" to "a2") in api.links())
        v7.renameProfile(api, "p", "p2")
        assertTrue(("p2" to "a2") in api.links())
    }

    // ───────────── لقطات الاستعادة ─────────────

    @Test
    fun `snapshot restores limitation values and profile links`() = runBlocking {
        val api = CompRouter()
        v7.createLimitation(api, UmLimitationSpec("l", rxKbps = 100, txKbps = 200, uptimeSeconds = 60, transferBytes = 1000))
        v7.createLimitation(api, UmLimitationSpec("l2"))
        v7.createProfile(api, UmProfileSpec("p", validitySeconds = 3_600, sharedUsers = 2, startMode = UmStartMode.FIRST_AUTH)); v7.linkLimitation(api, "p", "l")
        val snapL = UmSnapshot("s1", UmComponentType.LIMITATION, "l", 1L, "before edit", limitation = v7.listLimitations(api).first { it.name == "l" })
        val snapP = UmSnapshot("s2", UmComponentType.PROFILE, "p", 1L, "before edit", profile = v7.listProfiles(api).single())
        v7.updateLimitation(api, UmLimitationSpec("l", rxKbps = 1, uptimeSeconds = 0)); v7.updateProfile(api, UmProfileSpec("p", sharedUsers = 9, validityUnlimited = true))
        v7.setProfileLimitations(api, "p", listOf("l2"))
        assertTrue(v7.restoreSnapshot(api, snapL).isEmpty()); assertTrue(v7.restoreSnapshot(api, snapP).isEmpty())
        assertEquals("100k", api.limOf("l")["rate-limit-rx"]); assertEquals("60s", api.limOf("l")["uptime-limit"])
        assertEquals("2", api.table(P7).single()["override-shared-users"]); assertEquals("3600s", api.table(P7).single()["validity"])
        assertEquals(listOf("p" to "l"), api.links())
    }

    @Test
    fun `snapshot restore reports a limitation that no longer exists instead of failing`() = runBlocking {
        val api = CompRouter(); v7.createProfile(api, UmProfileSpec("p"))
        val snap = UmSnapshot("s", UmComponentType.PROFILE, "p", 1L, "r", profile = UmProfile("p", "p", null, false, 1, false, null, listOf("gone")))
        assertEquals(listOf("limitation missing: gone"), v7.restoreSnapshot(api, snap))
    }

    // ───────────── قواعد نقية ─────────────

    private val graph = UmGraph(
        profiles = listOf(
            UmProfile("p1", "p1", null, false, 1, false, null, listOf("l-shared", "l-solo")),
            UmProfile("p2", "p2", null, false, 1, false, null, listOf("l-shared")),
            UmProfile("p3", "p3", null, false, 1, false, null, emptyList())
        ),
        limitations = listOf(UmLimitation("l-shared", 0, 0, null, null), UmLimitation("l-solo", 0, 0, null, null), UmLimitation("l-free", 0, 0, null, null)),
        usersByProfile = mapOf("p1" to listOf("u1", "u2"), "p2" to listOf("u3"))
    )
    private val pkgs = mapOf("p1" to listOf("Gold"), "p2" to listOf("Silver", "Bronze"))

    @Test
    fun `relations count profiles packages and users without duplicates`() {
        val u = UmRelations.usageOfLimitation(graph, "l-shared", pkgs)
        assertEquals(listOf("p1", "p2"), u.profiles); assertEquals(listOf("Gold", "Silver", "Bronze"), u.packages); assertEquals(listOf("u1", "u2", "u3"), u.users)
        assertTrue(UmRelations.isSharedLimitation(u))
        assertFalse(UmRelations.isSharedLimitation(UmRelations.usageOfLimitation(graph, "l-solo", pkgs)))
        assertTrue(UmRelations.isSharedProfile(UmRelations.usageOfProfile(graph, "p2", pkgs)))
        assertFalse(UmRelations.isSharedProfile(UmRelations.usageOfProfile(graph, "p1", pkgs)))
    }

    @Test
    fun `profile delete is blocked by users or packages and allowed when free`() {
        assertEquals(listOf(DeleteBlocker.PROFILE_HAS_USERS, DeleteBlocker.PROFILE_USED_BY_PACKAGES), UmDeleteRules.profile(UmRelations.usageOfProfile(graph, "p1", pkgs)).blockers)
        assertTrue(UmDeleteRules.profile(UmRelations.usageOfProfile(graph, "p3", pkgs)).allowed)
        assertEquals(listOf(DeleteBlocker.NOT_FOUND), UmDeleteRules.profile(UmRelations.usageOfProfile(graph, "zz", pkgs), exists = false).blockers)
    }

    @Test
    fun `limitation delete needs detach then impact acknowledgement`() {
        val shared = UmRelations.usageOfLimitation(graph, "l-shared", pkgs)
        assertEquals(DeleteStatus.BLOCKED, UmDeleteRules.limitation(shared, detachFromProfiles = false, acknowledged = true).status)
        assertEquals(DeleteStatus.NEEDS_ACKNOWLEDGEMENT, UmDeleteRules.limitation(shared, detachFromProfiles = true, acknowledged = false).status)
        assertTrue(UmDeleteRules.limitation(shared, detachFromProfiles = true, acknowledged = true).allowed)
        assertTrue(UmDeleteRules.limitation(UmRelations.usageOfLimitation(graph, "l-free", pkgs), detachFromProfiles = false, acknowledged = false).allowed)
    }

    @Test
    fun `copy names avoid collisions case-insensitively`() {
        assertEquals("a-copy", UmSharedEdit.copyName("a", listOf("b")))
        assertEquals("a-copy-2", UmSharedEdit.copyName("a", listOf("A-Copy")))
        assertEquals("a-copy-3", UmSharedEdit.copyName("a", listOf("a-copy", "a-copy-2")))
    }

    @Test
    fun `independent copy is an exact clone with explicit values`() = runBlocking {
        val api = CompRouter()
        v7.createLimitation(api, UmLimitationSpec("l", rxKbps = 100, txKbps = 200, uptimeSeconds = 60))
        v7.createProfile(api, UmProfileSpec("p", validitySeconds = 600, sharedUnlimited = true, startMode = UmStartMode.ON_ASSIGN))
        v7.createLimitation(api, UmSharedEdit.cloneSpec(v7.listLimitations(api).single(), "l-copy"))
        v7.createProfile(api, UmSharedEdit.cloneSpec(v7.listProfiles(api).single(), "p-copy"))
        val c = api.limOf("l-copy"); assertEquals("100k", c["rate-limit-rx"]); assertEquals("60s", c["uptime-limit"]); assertEquals("0", c["transfer-limit"])
        val pc = api.table(P7).first { it["name"] == "p-copy" }
        assertEquals("600s", pc["validity"]); assertEquals("unlimited", pc["override-shared-users"]); assertEquals("assigned", pc["starts-when"])
        assertEquals("original untouched", "100k", api.limOf("l")["rate-limit-rx"])
    }

    @Test
    fun `origin never assumes ownership without a record`() {
        val reg = mapOf(UmOrigin.key(UmComponentType.PROFILE, "imp") to ComponentOrigin.IMPORTED, UmOrigin.key(UmComponentType.PROFILE, "mine") to ComponentOrigin.MGTIK)
        assertEquals(ComponentOrigin.IMPORTED, UmOrigin.classify(UmComponentType.PROFILE, "imp", reg))
        assertEquals(ComponentOrigin.MGTIK, UmOrigin.classify(UmComponentType.PROFILE, "mine", reg))
        assertEquals(ComponentOrigin.EXTERNAL, UmOrigin.classify(UmComponentType.PROFILE, "admin-made", reg))
        assertEquals(ComponentOrigin.MGTIK, UmOrigin.classify(UmComponentType.LIMITATION, "mgtik-old", reg))
        assertEquals(ComponentOrigin.EXTERNAL, UmOrigin.classify(UmComponentType.LIMITATION, "corp", reg))
    }

    @Test
    fun `fingerprint is stable and detects external changes`() {
        val l = UmLimitation("l", 1, 2, 3L, null)
        val f = UmFingerprint.of(l)
        assertEquals(f, UmFingerprint.of(l.copy()))
        assertTrue(f != UmFingerprint.of(l.copy(rxKbps = 2)))
        assertEquals(UmFingerprint.of(UmProfile("p", "n", 1, false, 1, false, null, listOf("b", "a"))), UmFingerprint.of(UmProfile("p", "n", 1, false, 1, false, null, listOf("a", "b"))))
        assertEquals(DriftState.IN_SYNC, UmDrift.detect(f, f))
        assertEquals(DriftState.EXTERNAL_CHANGE, UmDrift.detect(f, "zzz"))
        assertEquals(DriftState.NEVER_RECORDED, UmDrift.detect(null, f))
        assertEquals(DriftState.MISSING_ON_ROUTER, UmDrift.detect(f, null))
        assertTrue(UmDrift.writeNeedsWarning(DriftState.EXTERNAL_CHANGE)); assertFalse(UmDrift.writeNeedsWarning(DriftState.NEVER_RECORDED))
    }

    @Test
    fun `package sync diff ignores unspecified local values and reports real differences`() {
        val prof = UmProfile("p", "p", 86_400, false, 2, false, UmStartMode.FIRST_AUTH, listOf("l"))
        val lim = UmLimitation("l", 100, 200, 3_600, null)
        assertTrue(UmPackageSync.diff(100, 200, 2, false, 0, false, null, null, prof, lim).isEmpty())
        val d = UmPackageSync.diff(100, 300, 3, false, 48, false, 60L, null, prof, lim).map { it.field }.toSet()
        assertEquals(setOf("shared-users", "validity", "rate-limit-tx", "uptime-limit"), d)
        assertEquals(listOf("limitation"), UmPackageSync.diff(0, 0, 2, false, 0, false, null, null, prof, null).map { it.field })
    }

    @Test
    fun `snapshot map round trip`() {
        val l = UmSnapshot("a", UmComponentType.LIMITATION, "l", 5L, "r", limitation = UmLimitation("l", 1, 2, 3L, 4L))
        assertEquals(l, UmSnapshot.fromMap("a", l.toMap()))
        val p = UmSnapshot("b", UmComponentType.PROFILE, "p", 6L, "r", profile = UmProfile("p", "P", null, true, 3, false, UmStartMode.ON_ASSIGN, listOf("x", "y")))
        assertEquals(p, UmSnapshot.fromMap("b", p.toMap()))
        assertNull(UmSnapshot.fromMap("c", mapOf("type" to "weird")))
    }

    @Test
    fun `name rules`() {
        assertEquals(UmNameRules.Problem.BLANK, UmNameRules.validate("  ", emptyList()))
        assertEquals(UmNameRules.Problem.INVALID_CHARS, UmNameRules.validate("a b", emptyList()))
        assertEquals(UmNameRules.Problem.RESERVED, UmNameRules.validate("Default", emptyList()))
        assertEquals(UmNameRules.Problem.EXISTS, UmNameRules.validate("Gold", listOf("gold")))
        assertEquals(UmNameRules.Problem.TOO_LONG, UmNameRules.validate("x".repeat(65), emptyList()))
        assertNull(UmNameRules.validate("gold-1m_x.y", emptyList()))
        assertNull(UmNameRules.validate("gold", listOf("gold"), allowExisting = true))
    }

    @Test
    fun `op log summary and flags`() {
        val log = UmOpLog()
        log.add(UmStepAction.CREATED, UmComponentType.LIMITATION, "l"); log.add(UmStepAction.FAILED, null, "p", "boom"); log.add(UmStepAction.ROLLED_BACK, UmComponentType.LIMITATION, "l")
        assertTrue(log.failed && log.rolledBack && !log.rollbackIncomplete)
        assertEquals("created:limitation:l; failed:-:p(boom); rolled_back:limitation:l", log.summary())
    }
}

package ps.mgtik.hotspot.data.userman

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ps.mgtik.hotspot.core.network.RouterApiException

/**
 * الأولوية، التصفير الدوري، الجدولة الزمنية والسعر: التحقق قبل الكتابة، الكتابة بأسماء RouterOS،
 * بوابة الجيل (v6 بلا تصفير دوري)، القراءة والتحقق بعد الكتابة، والتراجع عند الفشل.
 */
class UserManAdvancedLimitsTest {
    private val v7 = UserManV7Dialect()
    private val v6 = UserManV6Dialect("admin")
    private val L7 = "/user-manager/limitation"
    private val P7 = "/user-manager/profile"
    private val PL7 = "/user-manager/profile-limitation"
    private val L6 = "/tool/user-manager/profile/limitation"
    private val P6 = "/tool/user-manager/profile"
    private val PL6 = "/tool/user-manager/profile/profile-limitation"

    private fun codes(existing: UmLimitation?, spec: UmLimitationSpec) = UmLimitRules.validate(existing, spec).map { it.code }
    private fun invalidCode(block: () -> Unit): String {
        try { block() } catch (e: RouterApiException) { return e.error.message }
        fail("expected ERR_UM_LIMIT_INVALID"); return ""
    }

    // ───────────── الأولوية ─────────────

    @Test
    fun `priority must be 1 to 8 and needs a speed limit to be effective`() {
        assertTrue(codes(null, UmLimitationSpec("l", rxKbps = 2_000, txKbps = 2_000, priority = 3)).isEmpty())
        assertEquals(listOf(UmLimitRules.PRIORITY_INVALID), codes(null, UmLimitationSpec("l", rxKbps = 2_000, priority = 9)))
        assertEquals(listOf(UmLimitRules.PRIORITY_INVALID), codes(null, UmLimitationSpec("l", rxKbps = 2_000, priority = 0)))
        // بلا سرعة قصوى لا أثر للأولوية فلا تُرسل
        assertEquals(listOf(UmLimitRules.PRIORITY_NEEDS_RATE), codes(null, UmLimitationSpec("l", priority = 2)))
        // الأولوية الافتراضية (8) لا تحتاج سرعة: هي «لا أولوية خاصة»
        assertTrue(codes(null, UmLimitationSpec("l", priority = UmLimitRules.PRIORITY_DEFAULT)).isEmpty())
    }

    @Test
    fun `removing the speed under an existing priority is refused, unrelated edits are not blocked`() {
        val existing = UmLimitation("l", 2_000, 2_000, null, null, priority = 2)
        assertEquals(listOf(UmLimitRules.PRIORITY_NEEDS_RATE), codes(existing, UmLimitationSpec("l", rxKbps = 0, txKbps = 0)))
        // تعديل حد النقل فقط على Limitation قائم بأولوية بلا سرعة (أنشأه مدير آخر): لا يُحجب
        val legacy = UmLimitation("l", 0, 0, null, null, priority = 2)
        assertTrue(codes(legacy, UmLimitationSpec("l", transferBytes = 1_000L)).isEmpty())
    }

    @Test
    fun `v7 writes rate-limit-priority and reads it back, v6 uses the same name`() = runBlocking {
        for ((d, path) in listOf(v7 to L7, v6 to L6)) {
            val api = CompRouter()
            d.createLimitation(api, UmLimitationSpec("l", rxKbps = 4_000, txKbps = 4_000, priority = 2))
            assertEquals("2", api.table(path).single()["rate-limit-priority"])
            assertEquals(2, d.listLimitations(api).single().priority)
            d.updateLimitation(api, UmLimitationSpec("l", priority = UmLimitRules.PRIORITY_DEFAULT))
            assertEquals("8", api.table(path).single()["rate-limit-priority"])
        }
    }

    @Test
    fun `priority not given is never sent`() = runBlocking {
        val api = CompRouter()
        v7.createLimitation(api, UmLimitationSpec("l", rxKbps = 4_000, txKbps = 4_000))
        assertFalse(api.table(L7).single().containsKey("rate-limit-priority"))
    }

    // ───────────── التصفير الدوري ─────────────

    @Test
    fun `reset interval needs a counter limit and is rejected without one`() {
        assertEquals(listOf(UmLimitRules.RESET_NEEDS_LIMIT), codes(null, UmLimitationSpec("l", resetInterval = UmResetInterval.DAILY)))
        assertTrue(codes(null, UmLimitationSpec("l", transferBytes = 5_000L, resetInterval = UmResetInterval.DAILY)).isEmpty())
        assertTrue(codes(null, UmLimitationSpec("l", downloadBytes = 5_000L, resetInterval = UmResetInterval.MONTHLY)).isEmpty())
        // التعطيل لا يحتاج حداً (هو الوضع الافتراضي)
        assertTrue(codes(null, UmLimitationSpec("l", resetInterval = UmResetInterval.DISABLED)).isEmpty())
        // الدمج مع الحالة الحالية: حد قائم على الراوتر يكفي
        assertTrue(codes(UmLimitation("l", 0, 0, null, 9_000L), UmLimitationSpec("l", resetInterval = UmResetInterval.WEEKLY)).isEmpty())
    }

    @Test
    fun `v7 writes reset-counters-interval and reads it back`() = runBlocking {
        val api = CompRouter()
        v7.createLimitation(api, UmLimitationSpec("l", transferBytes = 5_000L, resetInterval = UmResetInterval.DAILY))
        assertEquals("daily", api.table(L7).single()["reset-counters-interval"])
        assertEquals(UmResetInterval.DAILY, v7.listLimitations(api).single().resetInterval)
        v7.updateLimitation(api, UmLimitationSpec("l", resetInterval = UmResetInterval.DISABLED))
        assertEquals("disabled", api.table(L7).single()["reset-counters-interval"])
    }

    @Test
    fun `reset interval left alone is not sent and an unknown custom interval reads as null not disabled`() = runBlocking {
        val api = CompRouter()
        v7.createLimitation(api, UmLimitationSpec("l", transferBytes = 5_000L))
        assertFalse(api.table(L7).single().containsKey("reset-counters-interval"))
        api.table(L7).single()["reset-counters-interval"] = "2d"
        assertNull(v7.listLimitations(api).single().resetInterval)
    }

    @Test
    fun `v6 has no reset interval - enabling is refused before any write and disabled sends nothing`() = runBlocking {
        val api = CompRouter()
        val msg = invalidCode { runBlocking { v6.createLimitation(api, UmLimitationSpec("l", transferBytes = 5_000L, resetInterval = UmResetInterval.DAILY)) } }
        assertTrue(msg, msg.contains("${UmLimitRules.UNSUPPORTED}:reset-counters-interval"))
        assertTrue("nothing written", api.table(L6).isEmpty())
        v6.createLimitation(api, UmLimitationSpec("l", transferBytes = 5_000L, resetInterval = UmResetInterval.DISABLED))
        assertFalse(api.table(L6).single().containsKey("reset-counters-interval"))
    }

    @Test
    fun `invalid advanced spec writes nothing for the package and rolls back nothing`() = runBlocking {
        val api = CompRouter()
        val msg = invalidCode { runBlocking { v7.upsertPackage(api, UmPackageSpec("p", rxKbps = 0, txKbps = 0, priority = 2)) } }
        assertTrue(msg, msg.contains(UmLimitRules.PRIORITY_NEEDS_RATE))
        assertTrue(api.table(L7).isEmpty() && api.table(P7).isEmpty() && api.table(PL7).isEmpty())
    }

    // ───────────── الجدولة ─────────────

    private val WORK = UmSchedule(8 * 3600, 16 * 3600, setOf(UmWeekday.SUNDAY, UmWeekday.MONDAY, UmWeekday.TUESDAY, UmWeekday.WEDNESDAY, UmWeekday.THURSDAY))

    @Test
    fun `schedule rules reject reversed or overnight windows and empty day sets`() {
        assertTrue(UmScheduleRules.validate(WORK).isEmpty())
        assertTrue(UmScheduleRules.validate(UmSchedule.ALWAYS).isEmpty())
        assertEquals(listOf(UmLimitRules.SCHEDULE_TIME_INVALID), UmScheduleRules.validate(UmSchedule(20 * 3600, 6 * 3600)).map { it.code })
        assertEquals(listOf(UmLimitRules.SCHEDULE_TIME_INVALID), UmScheduleRules.validate(UmSchedule(3600, 3600)).map { it.code })
        assertEquals(listOf(UmLimitRules.SCHEDULE_NO_DAYS), UmScheduleRules.validate(UmSchedule(weekdays = emptySet())).map { it.code })
    }

    @Test
    fun `default schedule is always on and is not imposed`() {
        assertTrue(UmSchedule.ALWAYS.isAlwaysOn)
        assertEquals("00:00:00", UmSchedule.clock(UmSchedule.ALWAYS.fromSeconds))
        assertEquals("23:59:59", UmSchedule.clock(UmSchedule.ALWAYS.tillSeconds))
        assertFalse(WORK.isAlwaysOn)
    }

    @Test
    fun `schedule parses router values and falls back to always on when absent`() {
        assertEquals(WORK, UmSchedule.parse("08:00:00", "16:00:00", "sunday,monday,tuesday,wednesday,thursday"))
        assertEquals(UmSchedule.ALWAYS, UmSchedule.parse(null, null, null))
        assertEquals(UmSchedule.ALWAYS, UmSchedule.parse("0s", "23:59:59", ""))
    }

    @Test
    fun `new package with a schedule writes it on the profile-limitation link, both generations`() = runBlocking {
        for ((d, pl) in listOf(v7 to PL7, v6 to PL6)) {
            val api = CompRouter()
            d.upsertPackage(api, UmPackageSpec("gold", rxKbps = 1_000, txKbps = 1_000, schedule = WORK))
            val link = api.table(pl).single()
            assertEquals("08:00:00", link["from-time"]); assertEquals("16:00:00", link["till-time"])
            assertEquals("sunday,monday,tuesday,wednesday,thursday", link["weekdays"])
            assertTrue(d.verifyPackage(api, UmPackageSpec("gold", rxKbps = 1_000, txKbps = 1_000, schedule = WORK)).ok)
            assertEquals(WORK, d.listProfiles(api).single().schedules["mgtik-gold"])
        }
    }

    @Test
    fun `package without a schedule never gets schedule attributes`() = runBlocking {
        val api = CompRouter()
        v7.upsertPackage(api, UmPackageSpec("plain", rxKbps = 1_000, txKbps = 1_000))
        val link = api.table(PL7).single()
        assertFalse(link.containsKey("from-time")); assertFalse(link.containsKey("till-time")); assertFalse(link.containsKey("weekdays"))
        // وأيضاً الجدول الافتراضي صراحةً على رابط جديد لا يرسل شيئاً (القيم الافتراضية في RouterOS نفسها)
        val api2 = CompRouter()
        v7.upsertPackage(api2, UmPackageSpec("plain", rxKbps = 1_000, txKbps = 1_000, schedule = UmSchedule.ALWAYS))
        assertFalse(api2.table(PL7).single().containsKey("from-time"))
    }

    @Test
    fun `editing the schedule updates the existing link and removing it writes explicit defaults`() = runBlocking {
        val api = CompRouter()
        v7.upsertPackage(api, UmPackageSpec("gold", rxKbps = 1_000, txKbps = 1_000, schedule = WORK))
        val linkWritesBefore = api.writes.count { it.endsWith(PL7) }
        // إعادة المحاولة بالجدول نفسه: لا كتابة جديدة على الرابط
        v7.upsertPackage(api, UmPackageSpec("gold", rxKbps = 1_000, txKbps = 1_000, schedule = WORK, writes = setOf(UmWrite.SCHEDULE)))
        assertEquals(linkWritesBefore, api.writes.count { it.endsWith(PL7) })
        // رفع القيد صراحةً: قيم RouterOS الافتراضية (لا حذف الرابط ولا ترك الجدول القديم)
        v7.upsertPackage(api, UmPackageSpec("gold", rxKbps = 1_000, txKbps = 1_000, schedule = UmSchedule.ALWAYS, writes = setOf(UmWrite.SCHEDULE)))
        val link = api.table(PL7).single()
        assertEquals("00:00:00", link["from-time"]); assertEquals("23:59:59", link["till-time"])
        assertEquals(UmSchedule.ALWAYS, v7.listProfiles(api).single().schedules["mgtik-gold"])
    }

    @Test
    fun `schedule is not written when the schedule field is not in writes`() = runBlocking {
        val api = CompRouter()
        v7.upsertPackage(api, UmPackageSpec("gold", rxKbps = 1_000, txKbps = 1_000, schedule = WORK))
        // تعديل السرعة فقط: الجدول (حتى لو حمله spec) لا يُلمس لأن SCHEDULE ليس في writes
        v7.upsertPackage(api, UmPackageSpec("gold", rxKbps = 2_000, txKbps = 2_000, schedule = UmSchedule.ALWAYS, writes = setOf(UmWrite.SPEED)))
        assertEquals(WORK, v7.listProfiles(api).single().schedules["mgtik-gold"])
    }

    @Test
    fun `invalid schedule is refused before any router write`() = runBlocking {
        val api = CompRouter()
        val msg = invalidCode { runBlocking { v7.upsertPackage(api, UmPackageSpec("gold", rxKbps = 1_000, txKbps = 1_000, schedule = UmSchedule(20 * 3600, 6 * 3600))) } }
        assertTrue(msg, msg.contains(UmLimitRules.SCHEDULE_TIME_INVALID))
        assertTrue(api.writes.isEmpty())
    }

    @Test
    fun `failed schedule write rolls back the earlier limitation change and keeps the old window`() = runBlocking {
        val api = CompRouter()
        v7.upsertPackage(api, UmPackageSpec("gold", rxKbps = 1_000, txKbps = 1_000, schedule = WORK))
        val other = UmSchedule(9 * 3600, 12 * 3600, setOf(UmWeekday.FRIDAY))
        api.failWhen = { op, path, _ -> op == "set" && path == PL7 }
        val log = UmOpLog()
        try {
            v7.upsertPackage(api, UmPackageSpec("gold", rxKbps = 5_000, txKbps = 5_000, schedule = other, writes = setOf(UmWrite.SPEED, UmWrite.SCHEDULE)), log)
            fail("expected failure")
        } catch (e: ps.mgtik.hotspot.core.network.RouterCommandRejected) { /* المتوقع */ }
        api.failWhen = { _, _, _ -> false }
        assertEquals(WORK, v7.listProfiles(api).single().schedules["mgtik-gold"])
        assertEquals("speed restored", 1_000, v7.listLimitations(api).single().rxKbps)
        assertTrue(log.failed)
    }

    // ───────────── السعر ─────────────

    @Test
    fun `price is formatted with two decimals and parsed back to minor units`() {
        assertEquals("12.50", UmUnits.formatPrice(1250)); assertEquals("0.05", UmUnits.formatPrice(5)); assertEquals("100.00", UmUnits.formatPrice(10_000))
        assertEquals(1250L, UmUnits.parsePriceMinor("12.50")); assertEquals(1000L, UmUnits.parsePriceMinor("10"))
        assertEquals(0L, UmUnits.parsePriceMinor("0.00")); assertNull(UmUnits.parsePriceMinor("")); assertNull(UmUnits.parsePriceMinor("abc")); assertNull(UmUnits.parsePriceMinor("-1"))
    }

    @Test
    fun `retail price is written to the profile price field on v6 and v7 and verified`() = runBlocking {
        for ((d, pp) in listOf(v7 to P7, v6 to P6)) {
            val api = CompRouter()
            val spec = UmPackageSpec("gold", rxKbps = 1_000, txKbps = 1_000, priceMinor = 1250)
            d.upsertPackage(api, spec)
            assertEquals("12.50", api.table(pp).single()["price"])
            assertEquals(1250L, d.listProfiles(api).single().priceMinor)
            assertTrue(d.verifyPackage(api, spec).ok)
            // السعر على الراوتر عُدِّل خارجياً: التحقق يكتشف
            api.table(pp).single()["price"] = "9.00"
            assertEquals("price", d.verifyPackage(api, spec).mismatches.single().field)
        }
    }

    @Test
    fun `price not given is never written and price edit touches nothing else`() = runBlocking {
        val api = CompRouter()
        v7.upsertPackage(api, UmPackageSpec("gold", rxKbps = 1_000, txKbps = 1_000, sharedUsers = 3))
        assertFalse(api.table(P7).single().containsKey("price"))
        v7.upsertPackage(api, UmPackageSpec("gold", priceMinor = 500, writes = setOf(UmWrite.PRICE)))
        val row = api.table(P7).single()
        assertEquals("5.00", row["price"]); assertEquals("3", row["override-shared-users"])
        assertEquals(1_000, v7.listLimitations(api).single().rxKbps)
    }

    @Test
    fun `reference profile is never written including price and schedule`() = runBlocking {
        val api = CompRouter()
        api.seed(P7, "name" to "shared-profile", "validity" to "30d", "price" to "7.00")
        v7.upsertPackage(api, UmPackageSpec("shared-profile", profileMode = UmComponentMode.REFERENCE, priceMinor = 9_999, schedule = WORK))
        assertEquals("7.00", api.table(P7).single()["price"])
        assertTrue(api.writes.isEmpty())
    }

    // ───────────── التراجع والاستنساخ والبصمة ─────────────

    @Test
    fun `failed limitation write rolls priority and reset back to router defaults when they were unset`() = runBlocking {
        val api = CompRouter()
        v7.createLimitation(api, UmLimitationSpec("mgtik-gold", rxKbps = 1_000, txKbps = 1_000, transferBytes = 5_000L))
        // الخطوة التالية (إنشاء Profile) تفشل بعد تعديل الـ Limitation: يجب أن تُعاد الأولوية والتصفير للافتراضي
        api.failWhen = { op, path, _ -> op == "add" && path == P7 }
        try {
            v7.upsertPackage(api, UmPackageSpec("gold", rxKbps = 1_000, txKbps = 1_000, transferBytes = 5_000L, priority = 2, resetInterval = UmResetInterval.DAILY))
            fail("expected failure")
        } catch (e: Exception) { /* المتوقع */ }
        val row = api.table(L7).single()
        assertEquals("8", row["rate-limit-priority"]); assertEquals("disabled", row["reset-counters-interval"])
    }

    @Test
    fun `clone carries priority reset and profile price`() {
        val l = UmLimitation("a", 1_000, 1_000, null, 9_000L, priority = 3, resetInterval = UmResetInterval.WEEKLY)
        val spec = UmSharedEdit.cloneSpec(l, "b")
        assertEquals(3, spec.priority); assertEquals(UmResetInterval.WEEKLY, spec.resetInterval)
        val p = UmProfile("p", "p", null, true, 1, false, null, emptyList(), priceMinor = 1500)
        assertEquals(1500L, UmSharedEdit.cloneSpec(p, "q").priceMinor)
    }

    @Test
    fun `fingerprint is unchanged for defaults and changes for real priority reset price or schedule`() {
        val base = UmLimitation("l", 1_000, 1_000, null, 5_000L)
        assertEquals(UmFingerprint.of(base), UmFingerprint.of(base.copy(priority = 8, resetInterval = UmResetInterval.DISABLED)))
        assertTrue(UmFingerprint.of(base) != UmFingerprint.of(base.copy(priority = 2)))
        assertTrue(UmFingerprint.of(base) != UmFingerprint.of(base.copy(resetInterval = UmResetInterval.DAILY)))
        val p = UmProfile("p", "p", 3600, false, 1, false, UmStartMode.FIRST_AUTH, listOf("l"))
        assertEquals(UmFingerprint.of(p), UmFingerprint.of(p.copy(priceMinor = 0, schedules = mapOf("l" to UmSchedule.ALWAYS))))
        assertTrue(UmFingerprint.of(p) != UmFingerprint.of(p.copy(priceMinor = 500)))
        assertTrue(UmFingerprint.of(p) != UmFingerprint.of(p.copy(schedules = mapOf("l" to WORK))))
    }

    @Test
    fun `snapshot round-trips advanced fields`() {
        val l = UmLimitation("l", 1_000, 1_000, null, 5_000L, priority = 3, resetInterval = UmResetInterval.MONTHLY)
        val back = UmSnapshot.fromMap("s", UmSnapshot("s", UmComponentType.LIMITATION, "l", 1L, "r", limitation = l).toMap())!!.limitation!!
        assertEquals(3, back.priority); assertEquals(UmResetInterval.MONTHLY, back.resetInterval)
        val p = UmProfile("p", "p", 3600, false, 1, false, UmStartMode.FIRST_AUTH, listOf("l"), schedules = mapOf("l" to WORK), priceMinor = 700)
        val pb = UmSnapshot.fromMap("s", UmSnapshot("s", UmComponentType.PROFILE, "p", 1L, "r", profile = p).toMap())!!.profile!!
        assertEquals(WORK, pb.schedules["l"]); assertEquals(700L, pb.priceMinor)
    }

    @Test
    fun `restoring a profile snapshot restores link schedules and price`() = runBlocking {
        val api = CompRouter()
        v7.upsertPackage(api, UmPackageSpec("gold", rxKbps = 1_000, txKbps = 1_000, schedule = WORK, priceMinor = 1000))
        val snap = UmSnapshot("s", UmComponentType.PROFILE, "gold", 1L, "r", profile = v7.listProfiles(api).single())
        api.table(PL7).single().apply { put("from-time", "00:00:00"); put("till-time", "23:59:59"); put("weekdays", "sunday,monday,tuesday,wednesday,thursday,friday,saturday") }
        api.table(P7).single()["price"] = "1.00"
        v7.restoreSnapshot(api, snap)
        assertEquals(WORK, v7.listProfiles(api).single().schedules["mgtik-gold"])
        assertEquals("10.00", api.table(P7).single()["price"])
    }
}

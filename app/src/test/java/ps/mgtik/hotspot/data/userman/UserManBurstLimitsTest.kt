package ps.mgtik.hotspot.data.userman

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ps.mgtik.hotspot.core.network.RouterApiException

/** Burst وحدّا التنزيل/الرفع المنفصلان: التحقق قبل الكتابة، الكتابة بأسماء v6/v7، القراءة، التحقق بعد الكتابة، وعدم المساس بالحدود الأخرى. */
class UserManBurstLimitsTest {
    private val v7 = UserManV7Dialect()
    private val v6 = UserManV6Dialect("admin")
    private val L7 = "/user-manager/limitation"
    private val L6 = "/tool/user-manager/profile/limitation"
    private val GOOD = UmBurstDir(rateKbps = 8_000, thresholdKbps = 2_000, timeSeconds = 10)

    private fun codes(l: UmLimitation) = UmLimitRules.validate(l).map { it.code }

    // ───────────── القواعد ─────────────

    @Test
    fun `valid burst per direction passes and disabled burst needs nothing`() {
        assertTrue(codes(UmLimitation("l", 4_000, 4_000, null, null, burstRx = GOOD, burstTx = GOOD)).isEmpty())
        assertTrue(codes(UmLimitation("l", 0, 0, null, null)).isEmpty())
    }

    @Test
    fun `burst rules reject incomplete, unlimited-base, not-above-rate, bad threshold and bad time`() {
        assertEquals(listOf(UmLimitRules.BURST_INCOMPLETE), codes(UmLimitation("l", 4_000, 4_000, null, null, burstTx = UmBurstDir(rateKbps = 8_000))))
        assertEquals(listOf(UmLimitRules.BURST_NEEDS_RATE), codes(UmLimitation("l", 0, 4_000, null, null, burstRx = GOOD)))
        assertEquals(listOf(UmLimitRules.BURST_NOT_ABOVE_RATE), codes(UmLimitation("l", 8_000, 8_000, null, null, burstRx = GOOD)))
        assertEquals(listOf(UmLimitRules.BURST_THRESHOLD_INVALID), codes(UmLimitation("l", 1_000, 1_000, null, null, burstTx = GOOD.copy(thresholdKbps = 8_000))))
        assertEquals(listOf(UmLimitRules.BURST_TIME_INVALID), codes(UmLimitation("l", 1_000, 1_000, null, null, burstTx = GOOD.copy(timeSeconds = 7_200))))
    }

    @Test
    fun `directions are independent`() {
        // التنزيل (tx) بـ Burst صالح، الرفع (rx) بلا Burst ولا قيد عليه
        assertTrue(codes(UmLimitation("l", 0, 4_000, null, null, burstTx = GOOD)).isEmpty())
    }

    @Test
    fun `separate download or upload limit may not exceed the total transfer limit`() {
        val base = UmLimitation("l", 0, 0, null, 1_000L)
        assertEquals(listOf(UmLimitRules.DOWNLOAD_EXCEEDS_TRANSFER), codes(base.copy(downloadBytes = 2_000L)))
        assertEquals(listOf(UmLimitRules.UPLOAD_EXCEEDS_TRANSFER), codes(base.copy(uploadBytes = 2_000L)))
        assertTrue(codes(base.copy(downloadBytes = 600L, uploadBytes = 600L)).isEmpty())
        // بلا حد إجمالي: لا قيد
        assertTrue(codes(UmLimitation("l", 0, 0, null, null, downloadBytes = 9_000L)).isEmpty())
    }

    @Test
    fun `spec validation merges with the existing state so lowering speed under an existing burst is refused`() {
        val existing = UmLimitation("l", 4_000, 4_000, null, null, burstRx = GOOD, burstTx = GOOD)
        val issues = UmLimitRules.validate(existing, UmLimitationSpec("l", rxKbps = 9_000))
        assertEquals(listOf(UmLimitRules.BURST_NOT_ABOVE_RATE), issues.map { it.code })
        // تعطيل Burst صراحةً ثم رفع السرعة: صالح
        assertTrue(UmLimitRules.validate(existing, UmLimitationSpec("l", rxKbps = 9_000, burstRx = UmBurstDir())).isEmpty())
    }

    @Test
    fun `defaults give double rate, half threshold and ten seconds and nothing for unlimited`() {
        assertEquals(UmBurstDir(8_000, 2_000, 10), UmLimitRules.defaultBurst(4_000))
        assertFalse(UmLimitRules.defaultBurst(0).enabled)
        assertTrue(codes(UmLimitation("l", 4_000, 4_000, null, null, burstRx = UmLimitRules.defaultBurst(4_000))).isEmpty())
    }

    // ───────────── الكتابة على الراوتر (v6 و v7) ─────────────

    @Test
    fun `v7 writes burst and separate limits with RouterOS attribute names and reads them back`() = runBlocking {
        val api = CompRouter()
        v7.createLimitation(api, UmLimitationSpec("l", rxKbps = 4_000, txKbps = 4_000, transferBytes = 10_000_000L,
            downloadBytes = 6_000_000L, uploadBytes = 3_000_000L, burstRx = GOOD, burstTx = GOOD.copy(rateKbps = 12_000)))
        val row = api.table(L7).single()
        assertEquals("8000k", row["rate-limit-burst-rx"]); assertEquals("2000k", row["rate-limit-burst-threshold-rx"]); assertEquals("10s", row["rate-limit-burst-time-rx"])
        assertEquals("12000k", row["rate-limit-burst-tx"])
        assertEquals("6000000", row["download-limit"]); assertEquals("3000000", row["upload-limit"]); assertEquals("10000000", row["transfer-limit"])
        val read = v7.listLimitations(api).single()
        assertEquals(GOOD, read.burstRx); assertEquals(12_000, read.burstTx.rateKbps)
        assertEquals(6_000_000L, read.downloadBytes); assertEquals(3_000_000L, read.uploadBytes)
    }

    @Test
    fun `v6 uses the same limitation attribute names`() = runBlocking {
        val api = CompRouter()
        v6.createLimitation(api, UmLimitationSpec("l", rxKbps = 1_000, txKbps = 1_000, downloadBytes = 5L, burstTx = UmBurstDir(2_000, 500, 20)))
        val row = api.table(L6).single()
        assertEquals("2000k", row["rate-limit-burst-tx"]); assertEquals("500k", row["rate-limit-burst-threshold-tx"]); assertEquals("20s", row["rate-limit-burst-time-tx"])
        assertEquals("5", row["download-limit"])
    }

    @Test
    fun `invalid settings are rejected before anything is written`() = runBlocking {
        val api = CompRouter()
        try {
            v7.createLimitation(api, UmLimitationSpec("l", rxKbps = 8_000, txKbps = 8_000, burstRx = GOOD)); fail()
        } catch (e: RouterApiException) {
            assertEquals(BaseDialect.ERR_LIMIT_INVALID, e.error.code)
            assertTrue(e.error.message.contains(UmLimitRules.BURST_NOT_ABOVE_RATE))
        }
        assertTrue("nothing written", api.writes.isEmpty() && api.table(L7).isEmpty())
    }

    @Test
    fun `update changes only supplied fields and can disable burst explicitly`() = runBlocking {
        val api = CompRouter()
        v7.createLimitation(api, UmLimitationSpec("l", rxKbps = 4_000, txKbps = 4_000, uptimeSeconds = 3_600, burstRx = GOOD))
        v7.updateLimitation(api, UmLimitationSpec("l", downloadBytes = 777L))
        var row = api.table(L7).single()
        assertEquals("777", row["download-limit"]); assertEquals("8000k", row["rate-limit-burst-rx"]); assertEquals("3600s", row["uptime-limit"])
        v7.updateLimitation(api, UmLimitationSpec("l", burstRx = UmBurstDir()))
        row = api.table(L7).single()
        assertEquals("0", row["rate-limit-burst-rx"]); assertEquals("0s", row["rate-limit-burst-time-rx"])
        assertEquals("777", row["download-limit"])
    }

    @Test
    fun `verification detects burst and limit mismatches and passes when equal`() = runBlocking {
        val api = CompRouter()
        val spec = UmLimitationSpec("l", rxKbps = 4_000, txKbps = 4_000, downloadBytes = 100L, burstRx = GOOD)
        v7.createLimitation(api, spec)
        assertTrue(UmVerifier.verifyLimitation(spec, v7.listLimitations(api).single()).ok)
        api.table(L7).single()["rate-limit-burst-time-rx"] = "5s"
        api.table(L7).single()["download-limit"] = "50"
        val bad = UmVerifier.verifyLimitation(spec, v7.listLimitations(api).single())
        assertEquals(setOf("rate-limit-burst-time-rx", "download-limit"), bad.mismatches.map { it.field }.toSet())
    }

    // ───────────── مسار الباقة ─────────────

    @Test
    fun `package writes burst and separate limits only when allowed and pushUsageLimits false skips download and upload`() = runBlocking {
        val api = CompRouter()
        val spec = UmPackageSpec(name = "pk", rxKbps = 4_000, txKbps = 4_000, transferBytes = 9_000L, downloadBytes = 5_000L, uploadBytes = 4_000L, burstRx = GOOD, burstTx = GOOD)
        v7.upsertPackage(api, spec)
        val row = api.table(L7).single()
        assertEquals("5000", row["download-limit"]); assertEquals("4000", row["upload-limit"]); assertEquals("8000k", row["rate-limit-burst-tx"])
        assertTrue(v7.verifyPackage(api, spec).ok)

        // pushUsageLimits=false: لا يُكتب download/upload ولا يُمس الموجود، بينما Burst (سرعة) يُكتب
        val api2 = CompRouter()
        v7.upsertPackage(api2, spec.copy(pushUsageLimits = false))
        val r2 = api2.table(L7).single()
        assertFalse(r2.containsKey("download-limit")); assertFalse(r2.containsKey("upload-limit")); assertFalse(r2.containsKey("transfer-limit"))
        assertEquals("8000k", r2["rate-limit-burst-rx"])
    }

    @Test
    fun `speed-only edit keeps existing burst but is refused if the new speed would invalidate it`() = runBlocking {
        val api = CompRouter()
        v7.upsertPackage(api, UmPackageSpec(name = "pk", rxKbps = 4_000, txKbps = 4_000, burstRx = GOOD, burstTx = GOOD))
        v7.upsertPackage(api, UmPackageSpec(name = "pk", rxKbps = 6_000, txKbps = 6_000, writes = setOf(UmWrite.SPEED)))
        assertEquals("8000k", api.table(L7).single()["rate-limit-burst-rx"])
        try {
            v7.upsertPackage(api, UmPackageSpec(name = "pk", rxKbps = 9_000, txKbps = 9_000, writes = setOf(UmWrite.SPEED))); fail()
        } catch (e: RouterApiException) { assertEquals(BaseDialect.ERR_LIMIT_INVALID, e.error.code) }
        assertEquals("6000k", api.table(L7).single()["rate-limit-rx"])
    }

    // ───────────── اللقطة والبصمة ─────────────

    @Test
    fun `fingerprint of a legacy limitation is unchanged by the new fields and snapshot round-trips them`() {
        val legacy = UmLimitation("l", 1, 2, 3L, 4L)
        // البصمة المسجّلة قبل هذا التعديل (نفس الصيغة القديمة) يجب أن تبقى
        val old = java.security.MessageDigest.getInstance("SHA-256").digest("L|l|1|2|3|4".toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        assertEquals(old, UmFingerprint.of(legacy))
        assertTrue(UmFingerprint.of(legacy) != UmFingerprint.of(legacy.copy(burstRx = GOOD)))
        val l = legacy.copy(downloadBytes = 9L, uploadBytes = 8L, burstRx = GOOD, burstTx = GOOD.copy(timeSeconds = 30))
        val back = UmSnapshot.fromMap("s", UmSnapshot("s", UmComponentType.LIMITATION, "l", 1L, "r", limitation = l).toMap())
        assertEquals(l, back!!.limitation)
    }

    @Test
    fun `restore snapshot and clone carry burst and separate limits`() = runBlocking {
        val api = CompRouter()
        val l = UmLimitation("l", 4_000, 4_000, null, 10_000L, downloadBytes = 6_000L, uploadBytes = 2_000L, burstRx = GOOD, burstTx = GOOD)
        v7.restoreSnapshot(api, UmSnapshot("s", UmComponentType.LIMITATION, "l", 1L, "r", limitation = l))
        assertEquals(l, v7.listLimitations(api).single())
        val c = UmSharedEdit.cloneSpec(l, "l2")
        v7.createLimitation(api, c)
        assertEquals(l.copy(name = "l2"), v7.listLimitations(api).first { it.name == "l2" })
        assertNotNull(c.burstRx)
    }
}

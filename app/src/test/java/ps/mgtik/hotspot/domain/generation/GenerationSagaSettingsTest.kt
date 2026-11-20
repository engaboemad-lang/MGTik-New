package ps.mgtik.hotspot.domain.generation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.generation.GenerationSagaTest.Chaos
import ps.mgtik.hotspot.domain.generation.GenerationSagaTest.FakeCloud
import ps.mgtik.hotspot.domain.generation.GenerationSagaTest.FakeRouter
import ps.mgtik.hotspot.domain.generation.GenerationSagaTest.FakeStore
import ps.mgtik.hotspot.domain.usecase.OperationalSettingsResolver
import ps.mgtik.hotspot.domain.usecase.ResolvedUserSettings
import ps.mgtik.hotspot.domain.usecase.RouterProfileSettings

/** بروفايل الراوتر يجب أن يطابق الإعدادات المحسومة قبل دفع أي حساب (سرعة/جلسات متزامنة). */
class GenerationSagaSettingsTest {

    private val expected = ResolvedUserSettings(UserLimits(3_600L, null), downloadKbps = 8_000, uploadKbps = 2_000, sharedUsers = 2)

    private fun run(actual: RouterProfileSettings?): Triple<SagaOutcome, FakeRouter, FakeStore> {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        st.run = RunState(
            executionId = "EXEC-1", idempotencyKey = "k1", orderId = "ORD-1", batchId = "BATCH-1",
            spec = GenerationSpec("P1", "Gold", "O1", 5, "USER_PASS_SAME", "XN-", 6), status = RunStatus.QUEUED
        )
        val cloud = object : CloudPort by cl {
            override suspend fun prepare(state: RunState): SagaResult<PrepareResult> =
                when (val r = cl.prepare(state)) {
                    is SagaResult.Ok -> SagaResult.Ok(r.value.copy(limits = expected.limits, settings = expected))
                    is SagaResult.Err -> r
                }
        }
        val router = object : RouterPort by rt {
            override suspend fun profileSettings(profileName: String) = SagaResult.Ok(actual)
        }
        val out = runBlocking { GenerationSaga(st, router, cloud, java.util.Random(1)).run("EXEC-1") }
        return Triple(out, rt, st)
    }

    @Test
    fun matching_router_profile_proceeds() {
        val (out, rt, _) = run(RouterProfileSettings(8_000, 2_000, 2))
        assertTrue(out is SagaOutcome.Completed)
        assertEquals(5, rt.users.size)
    }

    @Test
    fun mismatching_router_profile_fails_before_any_user_is_pushed() {
        val (out, rt, st) = run(RouterProfileSettings(1_000, 2_000, 2))
        assertTrue(out is SagaOutcome.Failed)
        assertEquals(OperationalSettingsResolver.ERR_ROUTER_PROFILE_OUT_OF_SYNC, (out as SagaOutcome.Failed).error.code)
        assertEquals(0, rt.users.size)
        assertEquals(RunStatus.FAILED, st.run!!.status)
    }

    @Test
    fun missing_router_package_fails_before_any_user_is_pushed() {
        val (out, rt, st) = run(null)
        assertTrue(out is SagaOutcome.Failed)
        assertEquals(OperationalSettingsResolver.ERR_ROUTER_PROFILE_MISSING, (out as SagaOutcome.Failed).error.code)
        assertEquals(0, rt.users.size)
        assertEquals(RunStatus.FAILED, st.run!!.status)
    }

    @Test
    fun router_limits_differing_from_package_fail_before_push() {
        val (out, rt, _) = run(RouterProfileSettings(8_000, 2_000, 2, uptimeSeconds = 7_200L, transferBytes = null, limitsKnown = true))
        assertTrue(out is SagaOutcome.Failed)
        assertEquals(OperationalSettingsResolver.ERR_ROUTER_PROFILE_OUT_OF_SYNC, (out as SagaOutcome.Failed).error.code)
        assertEquals(0, rt.users.size)
    }

    @Test
    fun matching_router_limits_proceed() {
        val (out, rt, _) = run(RouterProfileSettings(8_000, 2_000, 2, uptimeSeconds = 3_600L, transferBytes = null, limitsKnown = true))
        assertTrue(out is SagaOutcome.Completed)
        assertEquals(5, rt.users.size)
    }

    /** مستخدم أُنشئ ثم تبيّن في User Manager أنه بلا الباقة: يُحذف ويُعدّ فاشلاً ولا يُفوتَر. */
    @Test
    fun user_failing_post_create_verification_is_deleted_and_not_billed() {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        st.run = RunState(
            executionId = "EXEC-1", idempotencyKey = "k1", orderId = "ORD-1", batchId = "BATCH-1",
            spec = GenerationSpec("P1", "Gold", "O1", 5, "USER_PASS_SAME", "XN-", 6), status = RunStatus.QUEUED
        )
        val router = object : RouterPort by rt {
            override suspend fun verifyUsers(usernames: Collection<String>, profileName: String): SagaResult<Map<String, String>> =
                SagaResult.Ok(mapOf(usernames.first() to "profile=none"))
        }
        val out = runBlocking { GenerationSaga(st, router, cl, java.util.Random(1)).run("EXEC-1") }
        assertTrue(out is SagaOutcome.Completed)
        assertTrue((out as SagaOutcome.Completed).partial)
        assertEquals(4, rt.users.size)
        assertEquals(1, st.run!!.failed)
        assertEquals(4, st.run!!.successful)
    }

    @Test
    fun unlimited_router_package_does_not_pass_as_a_numeric_one_before_push() {
        val (out, rt, _) = run(RouterProfileSettings(8_000, 2_000, 1, sharedUnlimited = true))
        assertTrue(out is SagaOutcome.Failed)
        assertEquals(OperationalSettingsResolver.ERR_ROUTER_PROFILE_OUT_OF_SYNC, (out as SagaOutcome.Failed).error.code)
        assertEquals(0, rt.users.size)
    }

    @Test
    fun orphaned_app_limitation_fails_before_push_even_when_values_match() {
        val (out, rt, _) = run(RouterProfileSettings(8_000, 2_000, 2, uptimeSeconds = 3_600L, transferBytes = null, limitsKnown = true, limitationLinked = false))
        assertTrue(out is SagaOutcome.Failed)
        assertEquals(OperationalSettingsResolver.ERR_ROUTER_PROFILE_OUT_OF_SYNC, (out as SagaOutcome.Failed).error.code)
        assertEquals(0, rt.users.size)
    }
}

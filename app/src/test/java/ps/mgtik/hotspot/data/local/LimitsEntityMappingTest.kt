package ps.mgtik.hotspot.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ps.mgtik.hotspot.data.local.entity.GenerationRunEntity
import ps.mgtik.hotspot.data.local.entity.LocalUserEntity
import ps.mgtik.hotspot.data.local.entity.ProfileEntity
import ps.mgtik.hotspot.domain.generation.GenerationSpec
import ps.mgtik.hotspot.domain.generation.RunState
import ps.mgtik.hotspot.domain.generation.UserLimits
import ps.mgtik.hotspot.domain.model.LocalUserRecord
import ps.mgtik.hotspot.domain.model.Profile

/** القيود تبقى كما هي عبر Room (وتبقى null عند عدم الفرض، لا صفراً). */
class LimitsEntityMappingTest {

    @Test
    fun profileKeepsLimitsAndNullStaysNull() {
        val limited = Profile(id = "P1", linkedProfileName = "gold", limitUptimeSeconds = 3_600L, limitTransferBytes = 1_048_576L)
        val back = ProfileEntity.fromDomain(limited).toDomainOrNull()!!
        assertEquals(3_600L, back.limitUptimeSeconds); assertEquals(1_048_576L, back.limitTransferBytes)

        val none = ProfileEntity.fromDomain(Profile(id = "P2", linkedProfileName = "free")).toDomainOrNull()!!
        assertNull(none.limitUptimeSeconds); assertNull(none.limitTransferBytes)
    }

    @Test
    fun localUserKeepsAppliedLimitsSnapshot() {
        val record = LocalUserRecord(
            recordUuid = "B1-000001", routerUsername = "u1", ownerId = "O1",
            limitUptimeSeconds = 86_400L, limitTransferBytes = 5_368_709_120L
        )
        val back = LocalUserEntity.fromDomain(record).toDomainOrNull()!!
        assertEquals(86_400L, back.limitUptimeSeconds); assertEquals(5_368_709_120L, back.limitTransferBytes)
        val unlimited = LocalUserEntity.fromDomain(record.copy(limitUptimeSeconds = null, limitTransferBytes = null)).toDomainOrNull()!!
        assertNull(unlimited.limitUptimeSeconds); assertNull(unlimited.limitTransferBytes)
    }

    @Test
    fun generationRunRoundTripsResolvedLimits() {
        val state = RunState(
            executionId = "E1", idempotencyKey = "k", orderId = "O", batchId = "B",
            spec = GenerationSpec("P1", "gold", "O1", 10, "numeric", "", 8),
            limits = UserLimits(uptimeSeconds = 7_200L, transferBytes = null)
        )
        val back = GenerationRunEntity.fromState(state).toState()
        assertEquals(UserLimits(7_200L, null), back.limits)
        assertEquals(UserLimits.NONE, GenerationRunEntity.fromState(state.copy(limits = UserLimits.NONE)).toState().limits)
    }

    /** خيارات التوليد (الوضع/كلمة المرور/التعليق/السيرفر) تنجو من Room فيُستأنف التنفيذ بها نفسها بعد إغلاق التطبيق. */
    @Test
    fun generationRunRoundTripsGenerationOptions() {
        val spec = GenerationSpec("P1", "gold", "O1", 10, "NUMERIC_ONLY", "GZ", 6, "SEQUENTIAL", "MIXED_CASE_ALNUM", 9, "حفلة", "hotspot2")
        val state = RunState(executionId = "E1", idempotencyKey = "k", orderId = "O", batchId = "B", spec = spec)
        assertEquals(spec, GenerationRunEntity.fromState(state).toState().spec)
    }
}

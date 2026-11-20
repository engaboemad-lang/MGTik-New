package ps.mgtik.hotspot.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import ps.mgtik.hotspot.data.generation.RunMapping
import ps.mgtik.hotspot.domain.generation.GenerationSpec
import ps.mgtik.hotspot.domain.generation.RunState
import ps.mgtik.hotspot.domain.generation.RunStatus
import ps.mgtik.hotspot.domain.generation.SagaStep
import ps.mgtik.hotspot.domain.generation.StepState

/** نسبة التقدم تُبنى من حالة التنفيذ الفعلية فقط. */
class ExecutionProgressMathTest {

    private fun state(status: RunStatus, steps: Map<SagaStep, StepState>, done: Int = 0, total: Int = 0) = RunState(
        executionId = "E", idempotencyKey = "k", orderId = "O", batchId = "B",
        spec = GenerationSpec("P", "Gold", "O1", 100, "USER_PASS_SAME", "X-", 6),
        status = status, steps = steps, progressDone = done, progressTotal = total
    )

    private fun steps(vararg s: Pair<SagaStep, StepState>) =
        SagaStep.values().associateWith { StepState.PENDING } + s.toMap()

    @Test
    fun not_started_is_zero() {
        val e = RunMapping.toExecution(state(RunStatus.QUEUED, steps()))
        assertEquals(0f, ExecutionProgressMath.fraction(e), 0.0001f)
    }

    @Test
    fun running_push_counts_real_voucher_progress() {
        val e = RunMapping.toExecution(
            state(RunStatus.RUNNING, steps(SagaStep.PREPARE to StepState.SUCCESS, SagaStep.GENERATE to StepState.SUCCESS, SagaStep.ROUTER_PUSH to StepState.RUNNING), done = 25, total = 100)
        )
        assertEquals((2 + 0.25f) / 5, ExecutionProgressMath.fraction(e), 0.0001f)
    }

    @Test
    fun completed_is_one() {
        val all = SagaStep.values().associateWith { StepState.SUCCESS }
        assertEquals(1f, ExecutionProgressMath.fraction(RunMapping.toExecution(state(RunStatus.COMPLETED, all))), 0.0001f)
    }
}

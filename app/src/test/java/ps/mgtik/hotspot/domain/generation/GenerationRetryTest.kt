package ps.mgtik.hotspot.domain.generation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.generation.GenerationSagaTest.Chaos
import ps.mgtik.hotspot.domain.generation.GenerationSagaTest.FakeCloud
import ps.mgtik.hotspot.domain.generation.GenerationSagaTest.FakeRouter
import ps.mgtik.hotspot.domain.generation.GenerationSagaTest.FakeStore

/**
 * إعادة المحاولة بعد فشل متأخر: لا تكتمل دفعة فيها كروت لم تُحسم على الراوتر،
 * ولا تُعاد الكروت المرفوضة إلى PENDING بعد أن حُسمت خطوة الدفع.
 */
class GenerationRetryTest {

    private fun runAfterPush(count: Int) = RunState(
        executionId = "EXEC-1", idempotencyKey = "k1", orderId = "ORD-1", batchId = "BATCH-1", batchNumber = "B-0001",
        spec = GenerationSpec("P1", "Gold", "O1", count, "USER_PASS_SAME", "XN-", 6),
        status = RunStatus.QUEUED, pricePerVoucherMinor = 500L,
        steps = SagaStep.values().associateWith {
            if (it == SagaStep.PREPARE || it == SagaStep.GENERATE || it == SagaStep.ROUTER_PUSH) StepState.SUCCESS else StepState.PENDING
        }
    )

    private fun staged(seq: Int, state: VoucherState) = StagedVoucher("EXEC-1", seq, "xn-$seq", "xn-$seq", state)

    @Test
    fun canRepushRejected_only_before_push_step_succeeds() {
        val base = runAfterPush(3)
        assertFalse(base.canRepushRejected)
        assertTrue(base.withStep(SagaStep.ROUTER_PUSH, StepState.FAILED).canRepushRejected)
        assertTrue(base.withStep(SagaStep.ROUTER_PUSH, StepState.PENDING).canRepushRejected)
    }

    @Test
    fun unresolved_vouchers_after_push_never_complete_or_bill() = runBlocking<Unit> {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        st.run = runAfterPush(3)
        listOf(staged(1, VoucherState.ROUTER_OK), staged(2, VoucherState.ROUTER_OK), staged(3, VoucherState.PENDING)).forEach { st.staged[it.seq] = it }

        val out = GenerationSaga(st, rt, cl, java.util.Random(1)).run("EXEC-1")

        assertTrue(out is SagaOutcome.Failed && (out as SagaOutcome.Failed).error.code == SagaError.PENDING_REMAINS)
        assertEquals(RunStatus.FAILED, st.run!!.status)
        assertTrue("no invoice for an incomplete batch", cl.invoices.isEmpty())
        assertTrue("nothing committed to cloud", cl.vouchers.isEmpty())
    }

    @Test
    fun rewinding_push_step_pushes_only_the_pending_voucher_then_completes() = runBlocking<Unit> {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        cl.batch = PrepareResult("ORD-1", "BATCH-1", "B-0001", 500L, "Owner")
        st.run = runAfterPush(3)
        listOf(staged(1, VoucherState.ROUTER_OK), staged(2, VoucherState.ROUTER_OK), staged(3, VoucherState.PENDING)).forEach { st.staged[it.seq] = it }
        val saga = GenerationSaga(st, rt, cl, java.util.Random(1))
        saga.run("EXEC-1") // يفشل بـ PENDING_REMAINS

        // ما تفعله المستودع عند إعادة المحاولة: تعود خطوة الدفع إلى PENDING لوجود كروت غير محسومة
        st.run = st.run!!.copy(
            status = RunStatus.QUEUED,
            steps = st.run!!.steps.mapValues { (step, s) -> if (s == StepState.FAILED || step == SagaStep.ROUTER_PUSH) StepState.PENDING else s }
        )
        val out = saga.run("EXEC-1")

        assertTrue(out is SagaOutcome.Completed && !(out as SagaOutcome.Completed).partial)
        assertEquals(1, rt.users.size)
        assertEquals(1, rt.createCalls["xn-3"])
        assertEquals(3, cl.vouchers.size)
        assertEquals(3L * 500, cl.invoices["INV-ORD-1"])
        assertEquals(3, st.run!!.successful)
        assertEquals(0, st.run!!.failed)
    }
}

package ps.mgtik.hotspot.data.offline

import org.junit.Assert.assertEquals
import org.junit.Test
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult

class OfflineCommandProcessorTest {

    @Test
    fun `success removes the command`() {
        assertEquals(CommandDecision.REMOVE, decide(AppResult.Success(Unit)))
    }

    @Test
    fun `no connectivity stops the whole cycle to keep FIFO order`() {
        assertEquals(CommandDecision.RETRY_AND_STOP, decide(AppResult.Error(ApiError.networkUnavailable())))
        assertEquals(CommandDecision.RETRY_AND_STOP, decide(AppResult.Error(ApiError.timeout())))
    }

    @Test
    fun `transient server errors are retried without blocking others`() {
        assertEquals(CommandDecision.RETRY, decide(AppResult.Error(ApiError.httpError(503, "unavailable"))))
    }

    @Test
    fun `business and validation failures are final`() {
        assertEquals(CommandDecision.FAIL, decide(AppResult.Error(ApiError.validationError("bad"))))
        assertEquals(CommandDecision.FAIL, decide(AppResult.Error(ApiError("ERR_INSUFFICIENT_CREDIT", "x"))))
        assertEquals(CommandDecision.FAIL, decide(AppResult.Error(ApiError.httpError(401, "unauthorized"))))
    }
}

package ps.mgtik.hotspot.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test
import ps.mgtik.hotspot.core.result.ApiError

/** المهمة 6 (S2): أي خطوة اختبار تفشل لكل سبب. */
class RouterTestFailureRulesTest {
    private val S = TestStepStatus.SUCCESS
    private val E = TestStepStatus.ERROR
    private val I = TestStepStatus.IDLE

    @Test fun hostUnreachableTimeoutAndConnectionFailedFailAtReach() {
        listOf(ApiError.CODE_ROUTER_HOST_UNREACHABLE, ApiError.CODE_TIMEOUT, ApiError.CODE_NETWORK_UNAVAILABLE, null)
            .forEach { assertEquals(it.toString(), listOf(E, I, I), RouterTestFailureRules.statuses(it)) }
    }

    @Test fun portClosedRestUnavailableAndAuthFailAtAuthentication() {
        listOf(ApiError.CODE_ROUTER_PORT_CLOSED, ApiError.CODE_ROUTER_REST_UNAVAILABLE, ApiError.CODE_ROUTER_AUTH_FAILED)
            .forEach { assertEquals(it, listOf(S, E, I), RouterTestFailureRules.statuses(it)) }
    }

    @Test fun insufficientPermissionsFailAtRead() =
        assertEquals(listOf(S, S, E), RouterTestFailureRules.statuses(ApiError.CODE_ROUTER_PERMISSION_DENIED))
}

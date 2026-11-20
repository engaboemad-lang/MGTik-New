package ps.mgtik.hotspot.data.userman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.core.network.RouterOpKind
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.data.router.lifecycle.RouterOperation
import ps.mgtik.hotspot.data.router.lifecycle.RouterOperationPolicy

/** المهمة 4.4 تصنيف retryable، والمهمة 5 مهلات الأنواع. */
class RouterOperationPolicyTest {

    private val transient = ApiError.timeout()

    @Test fun everyOperationIsClassified() {
        assertTrue(RouterOperation.values().isNotEmpty())
        assertEquals(setOf("READ", "CREATE_USER", "ACTIVATE_PACKAGE", "DELETE_USER", "DISABLE_USER", "REBOOT_ROUTER", "PROVISION", "MAINTENANCE"),
            RouterOperation.values().map { it.name }.toSet())
    }

    @Test fun readsAndVerifiedWritesAreRetryable() {
        listOf(RouterOperation.READ, RouterOperation.CREATE_USER, RouterOperation.ACTIVATE_PACKAGE,
            RouterOperation.DELETE_USER, RouterOperation.DISABLE_USER).forEach {
            assertTrue(it.name, RouterOperationPolicy.isRetryable(it, transient))
        }
    }

    @Test fun rebootProvisionMaintenanceAreNeverRetried() {
        listOf(RouterOperation.REBOOT_ROUTER, RouterOperation.PROVISION, RouterOperation.MAINTENANCE).forEach {
            assertFalse(it.name, RouterOperationPolicy.isRetryable(it, transient))
            assertFalse(RouterOperationPolicy.classify(it, transient).retryable)
        }
    }

    @Test fun policyNeverWidensRetryability() {
        val permanent = ApiError(code = "ERR_X", message = "m", retryable = false)
        assertFalse(RouterOperationPolicy.isRetryable(RouterOperation.READ, permanent))
        assertFalse(RouterOperationPolicy.classify(RouterOperation.READ, permanent).retryable)
    }

    @Test fun createAndActivateRequireVerificationBeforeRetry() {
        assertTrue(RouterOperation.CREATE_USER.verifyBeforeRetry)
        assertTrue(RouterOperation.ACTIVATE_PACKAGE.verifyBeforeRetry)
        assertFalse(RouterOperation.READ.verifyBeforeRetry)
    }

    // ── 5: مهلات حسب النوع ──
    @Test fun probeIsFiveSecondsAndBulkAtLeastThirty() {
        assertEquals(5_000L, RouterOpKind.PROBE.readTimeoutMs)
        assertTrue(RouterOpKind.BULK.readTimeoutMs >= 30_000L)
        assertEquals(8_000L, RouterOpKind.STANDARD.readTimeoutMs)
    }

    @Test fun everyKindHasATotalCapAboveItsReadTimeout() {
        RouterOpKind.values().forEach { assertTrue(it.name, it.totalTimeoutMs > it.readTimeoutMs) }
        assertTrue(RouterOpKind.BULK.totalTimeoutMs > RouterOpKind.STANDARD.totalTimeoutMs)
        assertTrue(RouterOpKind.STANDARD.totalTimeoutMs > RouterOpKind.PROBE.totalTimeoutMs)
    }
}

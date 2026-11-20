package ps.mgtik.hotspot.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.core.network.RouterConfig

/** المهمة 2: متى يظهر حوار «لم يتم اختبار هذه الإعدادات بعد». */
class RouterSaveGuardTest {
    private val tested = RouterConfig(host = "10.0.0.1", username = "admin", password = "p")

    @Test fun successfulTestThenSave_noDialog() =
        assertFalse(RouterSaveGuard.needsConfirmation(tested, tested.copy()))

    @Test fun neverTested_dialog() =
        assertTrue(RouterSaveGuard.needsConfirmation(null, tested))

    @Test fun connectionAffectingEditAfterTest_dialog() {
        assertTrue(RouterSaveGuard.needsConfirmation(tested, tested.copy(host = "10.0.0.2")))
        assertTrue(RouterSaveGuard.needsConfirmation(tested, tested.copy(password = "q")))
        assertTrue(RouterSaveGuard.needsConfirmation(tested, tested.copy(useSsl = true)))
        assertTrue(RouterSaveGuard.needsConfirmation(tested, tested.copy(port = 8080)))
        assertTrue(RouterSaveGuard.needsConfirmation(tested, tested.copy(username = "x")))
    }

    @Test fun nonConnectionFieldsDoNotTriggerDialog() {
        assertFalse(RouterSaveGuard.needsConfirmation(tested, tested.copy(autoSyncIntervalSeconds = 99, isConnected = false)))
    }

    @Test fun revertingEditsToTestedValuesNeedsNoDialog() {
        val edited = tested.copy(host = "10.0.0.9")
        assertTrue(RouterSaveGuard.needsConfirmation(tested, edited))
        assertFalse(RouterSaveGuard.needsConfirmation(tested, edited.copy(host = tested.host)))
    }
}

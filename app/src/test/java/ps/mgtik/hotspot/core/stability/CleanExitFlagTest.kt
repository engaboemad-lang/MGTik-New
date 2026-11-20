package ps.mgtik.hotspot.core.stability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** المهمة 5.7: علامة الإغلاق السليم/غير السليم. */
class CleanExitFlagTest {

    private class MemStore : FlagStore {
        val map = HashMap<String, Boolean>()
        override fun get(key: String) = map[key] ?: false
        override fun set(key: String, value: Boolean) { map[key] = value }
    }

    @Test fun firstEverLaunchIsNotUnclean() {
        val f = CleanExitFlag(MemStore())
        assertFalse(f.onLaunch())
        assertFalse(f.hasPendingReport())
    }

    @Test fun orderlyExitLeavesNextLaunchClean() {
        val store = MemStore()
        CleanExitFlag(store).apply { onLaunch(); onCleanExit() }
        val next = CleanExitFlag(store)
        assertFalse(next.onLaunch())
        assertFalse(next.hasPendingReport())
    }

    @Test fun missingOrderlyExitIsReportedAtNextLaunch() {
        val store = MemStore()
        CleanExitFlag(store).onLaunch() // قُتلت العملية بلا prepareExit
        val next = CleanExitFlag(store)
        assertTrue(next.onLaunch())
        assertTrue(next.hasPendingReport())
    }

    @Test fun pendingReportSurvivesUntilReportedSuccessfully() {
        val store = MemStore()
        CleanExitFlag(store).onLaunch()
        val second = CleanExitFlag(store); second.onLaunch()
        // إقلاع ثالث قبل نجاح التسجيل في التدقيق: الحدث ما زال معلّقاً
        second.onCleanExit()
        assertTrue(CleanExitFlag(store).hasPendingReport())
        CleanExitFlag(store).markReported()
        assertFalse(CleanExitFlag(store).hasPendingReport())
    }

    @Test fun resumeAfterExitReopensSession() {
        val store = MemStore()
        val f = CleanExitFlag(store)
        f.onLaunch(); f.onCleanExit(); f.onResumed()
        assertTrue(CleanExitFlag(store).onLaunch()) // قُتلت بعد الاستئناف: غير سليم
    }
}

package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.usecase.ReferenceGuard.Verdict

class ReferenceGuardTest {
    @Test fun `profile marker unknown means not ready - never silently deletable`() {
        assertEquals(Verdict.NOT_READY, ReferenceGuard.profileDeletable(null))
    }
    @Test fun `used profile is blocked and unused is ok`() {
        assertEquals(Verdict.BLOCKED, ReferenceGuard.profileDeletable(true))
        assertEquals(Verdict.OK, ReferenceGuard.profileDeletable(false))
    }
    @Test fun `category counter decides deletion`() {
        assertEquals(Verdict.NOT_READY, ReferenceGuard.categoryDeletable(null))
        assertEquals(Verdict.BLOCKED, ReferenceGuard.categoryDeletable(3))
        assertEquals(Verdict.OK, ReferenceGuard.categoryDeletable(0))
    }
    @Test fun `only existing active categories accept profiles`() {
        assertTrue(ReferenceGuard.categoryAssignable(true, null))
        assertTrue(ReferenceGuard.categoryAssignable(true, "active"))
        assertFalse(ReferenceGuard.categoryAssignable(true, "disabled"))
        assertFalse(ReferenceGuard.categoryAssignable(true, "archived"))
        assertFalse(ReferenceGuard.categoryAssignable(false, "active"))
    }
}

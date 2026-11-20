package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test
import ps.mgtik.hotspot.domain.model.ReferenceChangeAction.*
import ps.mgtik.hotspot.domain.model.ReferenceChangeBlock.*

class ReferenceChangePlannerTest {
    private fun plan(old: String = "g1", new: String = "g2", oldOn: Boolean = true, newOn: Boolean = false, used: Boolean = false) =
        ReferenceChangePlanner.plan(old, new, oldOn, newOn, used)

    @Test fun renameWhenNewIsAbsentAndOldExists() = assertEquals(RENAME_ROUTER_PROFILE, plan().action)
    @Test fun repointWhenNewAlreadyOnRouter() = assertEquals(REPOINT_TO_EXISTING, plan(newOn = true).action)
    @Test fun repointEvenIfOldIsMissingOnRouter() = assertEquals(REPOINT_TO_EXISTING, plan(oldOn = false, newOn = true).action)

    @Test fun blockedWhenNothingExistsOnRouter() {
        val p = plan(oldOn = false, newOn = false)
        assertEquals(BLOCKED, p.action); assertEquals(NEW_NOT_ON_ROUTER, p.block)
    }
    @Test fun blockedWhenAnotherProfileUsesTheName() = assertEquals(USED_BY_OTHER_PROFILE, plan(newOn = true, used = true).block)
    @Test fun blockedWhenSameName() = assertEquals(SAME_NAME, plan(new = "g1").block)
    @Test fun blockedOnInvalidOrDefaultName() {
        assertEquals(INVALID_NAME, plan(new = "my profile").block)
        assertEquals(INVALID_NAME, plan(new = "").block)
        assertEquals(INVALID_NAME, plan(new = "default").block)
    }
    @Test fun invalidNameWinsOverEverything() = assertEquals(INVALID_NAME, plan(new = "a/b", newOn = true, used = true).block)
}

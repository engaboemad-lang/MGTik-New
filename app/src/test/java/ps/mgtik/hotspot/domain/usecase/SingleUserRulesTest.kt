package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.model.NewNormalUser
import ps.mgtik.hotspot.domain.model.OwnerAccount
import ps.mgtik.hotspot.domain.model.OwnerStatus

class SingleUserRulesTest {

    private fun draft(
        username: String = "user_01", password: String = "abcd1234",
        profileId: String = "p1", routerProfile: String = "rp"
    ) = NewNormalUser(username, password, profileId, routerProfile, "")

    @Test fun `valid draft passes`() = assertNull(SingleUserRules.validateNew(draft()))

    @Test fun `username rules match the subscription username rule`() {
        assertEquals(SingleUserRules.ERR_USERNAME_INVALID, SingleUserRules.validateNew(draft(username = "ab")))
        assertEquals(SingleUserRules.ERR_USERNAME_INVALID, SingleUserRules.validateNew(draft(username = "has space")))
        assertEquals(SingleUserRules.ERR_USERNAME_INVALID, SingleUserRules.validateNew(draft(username = "a".repeat(33))))
        assertNull(SingleUserRules.validateNew(draft(username = "a.b_c@d-1")))
    }

    @Test fun `password and profile are required`() {
        assertEquals(SingleUserRules.ERR_PASSWORD_INVALID, SingleUserRules.validateNew(draft(password = "abc")))
        assertEquals(SingleUserRules.ERR_PASSWORD_INVALID, SingleUserRules.validateNew(draft(password = "ab cd")))
        assertEquals(SingleUserRules.ERR_PROFILE_REQUIRED, SingleUserRules.validateNew(draft(profileId = "")))
        assertEquals(SingleUserRules.ERR_PROFILE_REQUIRED, SingleUserRules.validateNew(draft(routerProfile = "")))
    }

    private fun owner(id: String, name: String, status: OwnerStatus = OwnerStatus.ACTIVE) =
        OwnerAccount(ownerId = id, name = name, status = status)

    @Test fun `admin resolves by document id first, returning the real owner id`() {
        val r = SingleUserRules.resolveAdminOwner(listOf(owner("o1", "Ali"), owner("admin", "المدير")))
        assertEquals("admin", (r as SingleUserRules.AdminOwner.Found).owner.ownerId)
    }

    @Test fun `admin resolves by name to the real document id, not the text`() {
        val r = SingleUserRules.resolveAdminOwner(listOf(owner("o1", "Ali"), owner("k9X", " Admin ")))
        assertEquals("k9X", (r as SingleUserRules.AdminOwner.Found).owner.ownerId)
    }

    @Test fun `missing admin is an explicit error, never created`() {
        val r = SingleUserRules.resolveAdminOwner(listOf(owner("o1", "Ali")))
        assertEquals(SingleUserRules.ERR_ADMIN_OWNER_MISSING, (r as SingleUserRules.AdminOwner.Error).code)
        assertEquals(SingleUserRules.ERR_ADMIN_OWNER_MISSING, (SingleUserRules.resolveAdminOwner(emptyList()) as SingleUserRules.AdminOwner.Error).code)
    }

    @Test fun `two name matches are ambiguous`() {
        val r = SingleUserRules.resolveAdminOwner(listOf(owner("a", "admin"), owner("b", "ADMIN")))
        assertEquals(SingleUserRules.ERR_ADMIN_OWNER_AMBIGUOUS, (r as SingleUserRules.AdminOwner.Error).code)
    }

    @Test fun `inactive admin is rejected`() {
        val r = SingleUserRules.resolveAdminOwner(listOf(owner("admin", "x", OwnerStatus.ARCHIVED)))
        assertEquals(SingleUserRules.ERR_ADMIN_OWNER_INACTIVE, (r as SingleUserRules.AdminOwner.Error).code)
        assertTrue(SingleUserRules.resolveAdminOwner(listOf(owner("admin", "x", OwnerStatus.SUSPENDED))) is SingleUserRules.AdminOwner.Error)
    }

    @Test fun `id match wins over a different owner that is only named admin`() {
        val r = SingleUserRules.resolveAdminOwner(listOf(owner("admin", "Boss"), owner("z", "admin")))
        assertEquals("admin", (r as SingleUserRules.AdminOwner.Found).owner.ownerId)
    }
}

package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.LocalUserRecord
import ps.mgtik.hotspot.domain.model.UserEditRequest
import ps.mgtik.hotspot.domain.model.UserOrigin

class UserEditRulesTest {

    private fun record(
        origin: UserOrigin = UserOrigin.ADOPTED,
        owner: String = "o1",
        uptime: Long? = 3_600L,
        bytes: Long? = null,
        updatedAt: String = "2026-11-01T10:00:00.000Z"
    ) = LocalUserRecord(
        recordUuid = "u1", routerUsername = "card001", ownerId = owner, origin = origin,
        limitUptimeSeconds = uptime, limitTransferBytes = bytes, updatedAt = updatedAt
    )

    private fun request(
        owner: String = "o1",
        expected: String = "2026-11-01T10:00:00.000Z"
    ) = UserEditRequest("u1", expected, owner)

    private fun errorCode(r: AppResult<*>) = (r as AppResult.Error).error.code

    @Test
    fun `no change is rejected`() {
        assertEquals(UserEditRules.ERR_NO_CHANGES, errorCode(UserEditRules.plan(record(), request())))
    }

    @Test
    fun `owner change is planned and never needs the router`() {
        val owner = UserEditRules.plan(record(), request(owner = "o2")) as AppResult.Success
        assertTrue(owner.data.ownerChanged)
    }

    @Test
    fun `owner of a generated user is locked`() {
        val gen = record(origin = UserOrigin.GENERATED)
        assertEquals(UserEditRules.ERR_OWNER_LOCKED, errorCode(UserEditRules.plan(gen, request(owner = "o2"))))
    }

    @Test
    fun `blank owner is invalid`() {
        assertEquals(UserEditRules.ERR_OWNER_INVALID, errorCode(UserEditRules.plan(record(), request(owner = " "))))
    }

    @Test
    fun `conflict only when the record changed and the edit is not already applied`() {
        val current = record(updatedAt = "2026-11-01T11:00:00.000Z")
        // المدير فتح الشاشة عند 10:00 والسجل تغيّر عند 11:00 بقيمة أخرى
        assertTrue(UserEditRules.isConflict(current, request(owner = "o2")))
        // إعادة تنفيذ بعد نجاح ضاع إقراره: القيم الحالية = المطلوبة ⇒ ليس تعارضاً
        assertFalse(UserEditRules.isConflict(current, request(owner = "o1")))
        // لا تغيّر ⇒ لا تعارض
        assertFalse(UserEditRules.isConflict(record(), request(owner = "o2")))
    }

    @Test
    fun `blank expected version disables the conflict check`() {
        assertFalse(UserEditRules.isConflict(record(updatedAt = "2026-11-01T11:00:00.000Z"), request(owner = "o2", expected = "")))
    }
}

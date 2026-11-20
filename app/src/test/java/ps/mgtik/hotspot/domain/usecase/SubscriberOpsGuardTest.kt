package ps.mgtik.hotspot.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.AuditLog
import ps.mgtik.hotspot.domain.model.DisconnectOutcome
import ps.mgtik.hotspot.domain.model.LocalUserRecord
import ps.mgtik.hotspot.domain.model.LogSeverity
import ps.mgtik.hotspot.domain.model.RouterDisableState
import ps.mgtik.hotspot.domain.model.SystemClass
import ps.mgtik.hotspot.domain.model.UserEditRequest
import ps.mgtik.hotspot.domain.model.UserRecordLookup
import ps.mgtik.hotspot.domain.repository.IAuditRepository

/** المشترك الشهري محمي في طبقة المعالجة (Flow) لا في الواجهة فقط: لا تعطيل راوتر ولا كتابة سحابية. */
class SubscriberOpsGuardTest {

    private val subscriber = LocalUserRecord(
        recordUuid = "s1", routerUsername = "ali", ownerId = "", systemClass = SystemClass.SUBSCRIPTION,
        updatedAt = "2026-11-01T10:00:00.000Z"
    )
    private val normal = subscriber.copy(recordUuid = "u1", routerUsername = "card001", systemClass = SystemClass.NORMAL, ownerId = "o1")

    private class FakeAudit : IAuditRepository {
        override fun getLogs(severity: LogSeverity?): Flow<List<AuditLog>> = flowOf(emptyList())
        override suspend fun logAction(action: String, detail: String, severity: LogSeverity): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun clearOldLogs(days: Int): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class Ports(val record: LocalUserRecord) : UserOperationPorts {
        val calls = mutableListOf<String>()
        override suspend fun lookup(recordUuid: String): AppResult<UserRecordLookup> = AppResult.Success(UserRecordLookup.Found(record))
        override suspend fun disableOnRouter(routerUsername: String): AppResult<RouterDisableState> {
            calls += "router.disable"; return AppResult.Success(RouterDisableState.DISABLED)
        }
        override suspend fun disconnectSessions(routerUsername: String): AppResult<DisconnectOutcome> {
            calls += "router.disconnect"; return AppResult.Success(DisconnectOutcome.ALREADY_GONE)
        }
        override suspend fun commitArchive(record: LocalUserRecord, reason: String, archivedBy: String?, routerState: RouterDisableState): AppResult<Unit> {
            calls += "cloud.archive"; return AppResult.Success(Unit)
        }
        override suspend fun commitEdit(request: UserEditRequest): AppResult<LocalUserRecord> {
            calls += "cloud.edit"; return AppResult.Success(record)
        }
        override suspend fun recordDisabledWithoutArchive(record: LocalUserRecord) { calls += "cloud.markDisabled" }
        override suspend fun onArchived(recordUuid: String) { calls += "local.removed" }
        override suspend fun onEdited(record: LocalUserRecord) { calls += "local.saved" }
        override suspend fun currentAdminId(): String? = "admin"
    }

    private fun code(r: AppResult<*>) = (r as AppResult.Error).error.code

    @Test fun `archive flow refuses a subscriber before touching router or cloud`() {
        val ports = Ports(subscriber)
        val r = runBlocking { UserArchiveFlow(ports, FakeAudit()).run("s1", "reason") }
        assertEquals(SystemClassRules.ERR_USER_SUBSCRIPTION_MANAGED, code(r))
        assertTrue("no side effects expected, got ${ports.calls}", ports.calls.none { it != "lookup" })
    }

    @Test fun `edit flow refuses a subscriber before touching router or cloud`() {
        val ports = Ports(subscriber)
        val r = runBlocking { UserEditFlow(ports, FakeAudit()).run(UserEditRequest("s1", subscriber.updatedAt, "o9")) }
        assertEquals(SystemClassRules.ERR_USER_SUBSCRIPTION_MANAGED, code(r))
        assertTrue("no side effects expected, got ${ports.calls}", ports.calls.isEmpty())
    }

    @Test fun `regular users still archive normally`() {
        val ports = Ports(normal)
        val r = runBlocking { UserArchiveFlow(ports, FakeAudit()).run("u1", "reason") }
        assertTrue(r is AppResult.Success)
        assertTrue(ports.calls.containsAll(listOf("router.disable", "cloud.archive", "local.removed")))
    }

    @Test fun `normal ops guard keys on the stored system class only`() {
        assertEquals(SystemClassRules.ERR_USER_SUBSCRIPTION_MANAGED, SystemClassRules.normalOpsError(subscriber))
        assertNull(SystemClassRules.normalOpsError(normal))
    }
}

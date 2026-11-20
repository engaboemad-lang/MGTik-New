package ps.mgtik.hotspot.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.AuditLog
import ps.mgtik.hotspot.domain.model.DisconnectOutcome
import ps.mgtik.hotspot.domain.model.LocalUserRecord
import ps.mgtik.hotspot.domain.model.LogSeverity
import ps.mgtik.hotspot.domain.model.RouterDisableState
import ps.mgtik.hotspot.domain.model.UserEditRequest
import ps.mgtik.hotspot.domain.model.UserOrigin
import ps.mgtik.hotspot.domain.model.UserRecordLookup
import ps.mgtik.hotspot.domain.repository.IAuditRepository

class UserOperationsFlowTest {

    private val base = LocalUserRecord(
        recordUuid = "u1", routerUsername = "card001", ownerId = "o1", origin = UserOrigin.ADOPTED,
        limitUptimeSeconds = 3_600L, updatedAt = "2026-11-01T10:00:00.000Z"
    )

    private class FakeAudit : IAuditRepository {
        val actions = mutableListOf<String>()
        override fun getLogs(severity: LogSeverity?): Flow<List<AuditLog>> = flowOf(emptyList())
        override suspend fun logAction(action: String, detail: String, severity: LogSeverity): AppResult<Unit> {
            actions += action
            return AppResult.Success(Unit)
        }
        override suspend fun clearOldLogs(days: Int): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class FakePorts(var record: LocalUserRecord) : UserOperationPorts {
        val calls = mutableListOf<String>()
        var lookupResult: AppResult<UserRecordLookup>? = null
        var disableResult: AppResult<RouterDisableState> = AppResult.Success(RouterDisableState.DISABLED)
        var archiveResult: AppResult<Unit> = AppResult.Success(Unit)
        var editResult: AppResult<LocalUserRecord>? = null
        var adminId: String? = "admin-1"
        var archivedBy: String? = "unset"
        var committedRouterState: RouterDisableState? = null
        var archiveCommits = 0
        var editedRecord: LocalUserRecord? = null

        override suspend fun lookup(recordUuid: String): AppResult<UserRecordLookup> {
            calls += "lookup"
            return lookupResult ?: AppResult.Success(UserRecordLookup.Found(record))
        }
        override suspend fun disableOnRouter(routerUsername: String): AppResult<RouterDisableState> {
            calls += "router.disable:$routerUsername"
            return disableResult
        }
        var disconnectResult: AppResult<DisconnectOutcome> = AppResult.Success(DisconnectOutcome.DISCONNECTED)
        override suspend fun disconnectSessions(routerUsername: String): AppResult<DisconnectOutcome> {
            calls += "router.disconnect:$routerUsername"
            return disconnectResult
        }
        override suspend fun commitArchive(record: LocalUserRecord, reason: String, archivedBy: String?, routerState: RouterDisableState): AppResult<Unit> {
            calls += "cloud.archive"
            this.archivedBy = archivedBy
            committedRouterState = routerState
            if (archiveResult is AppResult.Success) archiveCommits++
            return archiveResult
        }
        override suspend fun commitEdit(request: UserEditRequest): AppResult<LocalUserRecord> {
            calls += "cloud.edit"
            return editResult ?: AppResult.Success(
                record.copy(ownerId = request.ownerId)
            )
        }
        override suspend fun recordDisabledWithoutArchive(record: LocalUserRecord) { calls += "cloud.markDisabled" }
        override suspend fun onArchived(recordUuid: String) { calls += "local.removed" }
        override suspend fun onEdited(record: LocalUserRecord) { calls += "local.saved"; editedRecord = record }
        override suspend fun currentAdminId(): String? = adminId
    }

    private fun archive(ports: FakePorts, audit: FakeAudit = FakeAudit(), reason: String = "expired") =
        runBlocking { UserArchiveFlow(ports, audit).run("u1", reason) }

    private fun edit(ports: FakePorts, request: UserEditRequest, audit: FakeAudit = FakeAudit()) =
        runBlocking { UserEditFlow(ports, audit).run(request) }

    private fun code(r: AppResult<*>) = (r as AppResult.Error).error.code

    // ---------------------------------------------------------------- الأرشفة

    @Test
    fun `archive disables on the router first, then commits to the cloud, then clears the local cache`() {
        val ports = FakePorts(base)
        val audit = FakeAudit()
        val r = archive(ports, audit)

        assertTrue(r is AppResult.Success)
        assertEquals(listOf("lookup", "router.disable:card001", "router.disconnect:card001", "cloud.archive", "local.removed"), ports.calls)
        assertEquals("admin-1", ports.archivedBy)
        assertEquals(RouterDisableState.DISABLED, ports.committedRouterState)
        assertEquals(listOf("ARCHIVE_USER_DONE"), audit.actions)
    }

    @Test
    fun `router failure leaves the active record untouched and never archives`() {
        val ports = FakePorts(base).apply {
            disableResult = AppResult.Error(ApiError.networkUnavailable())
        }
        val audit = FakeAudit()
        val r = archive(ports, audit)

        assertEquals(ApiError.CODE_NETWORK_UNAVAILABLE, code(r))
        assertTrue((r as AppResult.Error).error.retryable)
        assertEquals(listOf("lookup", "router.disable:card001"), ports.calls)
        assertEquals(0, ports.archiveCommits)
        assertEquals(listOf("ARCHIVE_USER_FAILED"), audit.actions)
    }

    @Test
    fun `failed session disconnect is not a success - account stays disabled, nothing is archived, retry is possible`() {
        val ports = FakePorts(base).apply {
            disconnectResult = AppResult.Error(ApiError.networkUnavailable())
        }
        val audit = FakeAudit()
        val r = archive(ports, audit)

        assertEquals(UserEditRules.ERR_SESSION_DISCONNECT_FAILED, code(r))
        assertTrue((r as AppResult.Error).error.retryable)
        assertEquals(listOf("lookup", "router.disable:card001", "router.disconnect:card001", "cloud.markDisabled"), ports.calls)
        assertEquals(0, ports.archiveCommits)
        assertFalse(ports.calls.contains("local.removed"))
        assertEquals(listOf("ARCHIVE_USER_FAILED"), audit.actions)

        // إعادة المحاولة بعد زوال العطل تكمل الأرشفة مرة واحدة
        ports.disconnectResult = AppResult.Success(DisconnectOutcome.DISCONNECTED)
        assertTrue(archive(ports) is AppResult.Success)
        assertEquals(1, ports.archiveCommits)
    }

    @Test
    fun `unverified disable is not a success and nothing is archived`() {
        val ports = FakePorts(base).apply {
            disableResult = AppResult.Error(ApiError(UserEditRules.ERR_ARCHIVE_ROUTER_UNVERIFIED, "still enabled", retryable = true))
        }
        val r = archive(ports)
        assertEquals(UserEditRules.ERR_ARCHIVE_ROUTER_UNVERIFIED, code(r))
        assertFalse(ports.calls.contains("cloud.archive"))
        assertFalse(ports.calls.contains("local.removed"))
    }

    @Test
    fun `a user absent from the router is archived with that state recorded`() {
        val ports = FakePorts(base).apply { disableResult = AppResult.Success(RouterDisableState.ABSENT) }
        assertTrue(archive(ports) is AppResult.Success)
        assertEquals(RouterDisableState.ABSENT, ports.committedRouterState)
        assertEquals(ps.mgtik.hotspot.domain.model.ArchiveType.MISSING, ports.committedRouterState?.toArchiveType())
    }

    @Test
    fun `cloud failure after the router was disabled keeps the record, marks it disabled and reports the error`() {
        val ports = FakePorts(base).apply { archiveResult = AppResult.Error(ApiError.networkUnavailable()) }
        val r = archive(ports)

        assertEquals(ApiError.CODE_NETWORK_UNAVAILABLE, code(r))
        assertEquals(listOf("lookup", "router.disable:card001", "router.disconnect:card001", "cloud.archive", "cloud.markDisabled"), ports.calls)
        assertFalse(ports.calls.contains("local.removed"))
    }

    @Test
    fun `retry after a partial failure archives exactly once`() {
        val ports = FakePorts(base).apply { archiveResult = AppResult.Error(ApiError.networkUnavailable()) }
        archive(ports)
        assertEquals(0, ports.archiveCommits)

        ports.archiveResult = AppResult.Success(Unit)
        assertTrue(archive(ports) is AppResult.Success)
        assertEquals(1, ports.archiveCommits)
    }

    @Test
    fun `an already archived record is a success with no router or cloud calls`() {
        val ports = FakePorts(base).apply { lookupResult = AppResult.Success(UserRecordLookup.AlreadyArchived) }
        assertTrue(archive(ports) is AppResult.Success)
        assertEquals(listOf("lookup", "local.removed"), ports.calls)
        assertEquals(0, ports.archiveCommits)
    }

    @Test
    fun `a missing record fails without touching the router`() {
        val ports = FakePorts(base).apply { lookupResult = AppResult.Success(UserRecordLookup.NotFound) }
        assertEquals("USER_NOT_FOUND", code(archive(ports)))
        assertEquals(listOf("lookup"), ports.calls)
    }

    @Test
    fun `an unreadable record (offline, no cache) is a retryable error, not NotFound`() {
        val ports = FakePorts(base).apply { lookupResult = AppResult.Error(ApiError.networkUnavailable()) }
        val r = archive(ports)
        assertTrue((r as AppResult.Error).error.retryable)
        assertEquals(listOf("lookup"), ports.calls)
    }

    @Test
    fun `blank reason is rejected before anything runs`() {
        val ports = FakePorts(base)
        assertEquals(ApiError.CODE_VALIDATION_FAILED, code(archive(ports, reason = "  ")))
        assertTrue(ports.calls.isEmpty())
    }

    @Test
    fun `without a trusted admin identity archivedBy stays null`() {
        val ports = FakePorts(base).apply { adminId = null }
        archive(ports)
        assertNull(ports.archivedBy)
    }

    // ---------------------------------------------------------------- التعديل

    private fun req(owner: String = "o2", expected: String = base.updatedAt) = UserEditRequest("u1", expected, owner)

    @Test
    fun `owner only edit never touches the router`() {
        val ports = FakePorts(base)
        assertTrue(edit(ports, req(owner = "o2")) is AppResult.Success)
        assertEquals(listOf("lookup", "cloud.edit", "local.saved"), ports.calls)
    }

    @Test
    fun `a record changed since it was opened is a conflict and nothing is written`() {
        val ports = FakePorts(base.copy(updatedAt = "2026-11-01T11:00:00.000Z"))
        val r = edit(ports, req())
        assertEquals(UserEditRules.ERR_CONFLICT, code(r))
        assertEquals(listOf("lookup"), ports.calls)
    }

    @Test
    fun `an edit that is already applied succeeds without writes even if the version moved`() {
        val ports = FakePorts(base.copy(ownerId = "o2", updatedAt = "2026-11-01T11:00:00.000Z"))
        assertTrue(edit(ports, req()) is AppResult.Success)
        assertEquals(listOf("lookup", "local.saved"), ports.calls)
    }

    @Test
    fun `retry after a cloud failure never touches the router and commits once`() {
        val ports = FakePorts(base).apply { editResult = AppResult.Error(ApiError.networkUnavailable()) }
        assertTrue(edit(ports, req()) is AppResult.Error)

        ports.editResult = null
        assertTrue(edit(ports, req()) is AppResult.Success)
        assertEquals(0, ports.calls.count { it.startsWith("router.") })
        assertEquals(1, ports.calls.count { it == "local.saved" })
    }

    @Test
    fun `owner of a generated user cannot be changed even from the queue`() {
        val ports = FakePorts(base.copy(origin = UserOrigin.GENERATED))
        assertEquals(UserEditRules.ERR_OWNER_LOCKED, code(edit(ports, req(owner = "o2"))))
        assertEquals(listOf("lookup"), ports.calls)
    }

    @Test
    fun `editing a missing record fails`() {
        val ports = FakePorts(base).apply { lookupResult = AppResult.Success(UserRecordLookup.NotFound) }
        assertEquals("USER_NOT_FOUND", code(edit(ports, req())))
    }
}

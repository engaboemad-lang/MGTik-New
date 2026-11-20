package ps.mgtik.hotspot.domain.repository

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.ActiveSession

interface IActiveSessionRepository {
    fun getActiveSessions(): Flow<List<ActiveSession>>
    suspend fun disconnectActiveUser(sessionId: String, username: String, idempotencyKey: String): AppResult<Unit>
}

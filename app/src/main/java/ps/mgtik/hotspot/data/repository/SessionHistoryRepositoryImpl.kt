package ps.mgtik.hotspot.data.repository

import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.data.cloud.source.CloudSessionHistoryDataSource
import ps.mgtik.hotspot.domain.model.SessionChanges
import ps.mgtik.hotspot.domain.model.TrackedSession
import ps.mgtik.hotspot.domain.model.UserSessionStats
import ps.mgtik.hotspot.domain.repository.ISessionHistoryRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionHistoryRepositoryImpl @Inject constructor(
    private val cloud: CloudSessionHistoryDataSource
) : ISessionHistoryRepository {
    override suspend fun loadOpenSessions(): AppResult<List<TrackedSession>> = cloud.loadOpenSessions()
    override suspend fun applyChanges(changes: SessionChanges): AppResult<Unit> =
        if (changes.isEmpty) AppResult.Success(Unit) else cloud.applyChanges(changes)
    override suspend fun getUserStats(username: String): AppResult<UserSessionStats?> = cloud.getUserStats(username)
    override suspend fun getSessionHistory(username: String, limit: Int): AppResult<List<TrackedSession>> =
        cloud.getSessionHistory(username, limit)
}

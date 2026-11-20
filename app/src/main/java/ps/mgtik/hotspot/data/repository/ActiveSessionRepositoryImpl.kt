package ps.mgtik.hotspot.data.repository

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.data.cloud.source.CloudActiveSessionDataSource
import ps.mgtik.hotspot.domain.model.ActiveSession
import ps.mgtik.hotspot.domain.repository.IActiveSessionRepository
import ps.mgtik.hotspot.domain.repository.IRouterRepository
import ps.mgtik.hotspot.domain.repository.IRouterStateManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActiveSessionRepositoryImpl @Inject constructor(
    private val cloudDataSource: CloudActiveSessionDataSource,
    private val routerRepository: IRouterRepository,
    private val routerState: IRouterStateManager
) : IActiveSessionRepository {

    override fun getActiveSessions(): Flow<List<ActiveSession>> {
        return cloudDataSource.getActiveSessionsFlow()
    }

    /**
     * القطع الحقيقي يتم على NAS أولاً ويُتحقَّق منه؛ لا يُعلَن نجاح إلا بعد تأكيد الراوتر.
     * بعدها فقط تُحذف نسخة الجلسة من Firestore (مرآة للعرض): فشل حذفها لا يُفشل العملية لأن الجلسة قُطعت فعلاً.
     * كان يُحذف مستند Firestore وحده فيُبلَّغ نجاح والمستخدم ما زال متصلاً.
     */
    override suspend fun disconnectActiveUser(sessionId: String, username: String, idempotencyKey: String): AppResult<Unit> {
        val config = routerState.status.value.config.takeIf { it.host.isNotBlank() }
            ?: return AppResult.Error(ApiError.routerNotConfigured())
        return when (val cut = routerRepository.disconnectUserSessions(config, username)) {
            is AppResult.Error -> cut
            is AppResult.Loading -> AppResult.Error(ApiError.unknownError("Unexpected loading state"))
            is AppResult.Success -> {
                cloudDataSource.disconnectUser(username, idempotencyKey)
                AppResult.Success(Unit)
            }
        }
    }
}

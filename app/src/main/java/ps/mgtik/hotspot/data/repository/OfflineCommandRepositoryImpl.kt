package ps.mgtik.hotspot.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ps.mgtik.hotspot.data.local.dao.OfflineCommandDao
import ps.mgtik.hotspot.data.local.entity.OfflineCommandEntity
import ps.mgtik.hotspot.domain.model.OfflineCommand
import ps.mgtik.hotspot.domain.repository.IOfflineCommandRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OfflineCommandRepositoryImpl - تنفيذ إدارة طابور العمل دون اتصال (§5.3)
 */
@Singleton
class OfflineCommandRepositoryImpl @Inject constructor(
    private val offlineCommandDao: OfflineCommandDao
) : IOfflineCommandRepository {

    override fun getPendingCommands(): Flow<List<OfflineCommand>> {
        return offlineCommandDao.getAllCommandsFlow().map { entities ->
            entities.mapNotNull { it.toDomainOrNull() }
        }
    }

    override suspend fun addCommand(command: OfflineCommand) {
        offlineCommandDao.insertCommand(OfflineCommandEntity.fromDomain(command))
    }

    override suspend fun removeCommand(commandId: String) {
        offlineCommandDao.deleteCommandById(commandId)
    }

    override suspend fun getRunnableCommands(maxAttempts: Int): List<OfflineCommand> =
        offlineCommandDao.getRunnableCommands(maxAttempts).mapNotNull { it.toDomainOrNull() }

    override suspend fun markFailed(commandId: String, error: String?, maxAttempts: Int) {
        offlineCommandDao.markFailed(commandId, maxAttempts, error)
    }

    override suspend fun updateAttempt(commandId: String, error: String?) {
        offlineCommandDao.incrementAttemptCount(commandId, error)
    }

    override suspend fun clearAll() {
        offlineCommandDao.clearAll()
    }
}

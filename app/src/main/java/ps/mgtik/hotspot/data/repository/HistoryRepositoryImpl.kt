package ps.mgtik.hotspot.data.repository

import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.data.cloud.source.CloudHistoryDataSource
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.repository.IHistoryRepository
import ps.mgtik.hotspot.domain.repository.IOfflineCommandRepository
import ps.mgtik.hotspot.domain.repository.IOfflineScheduler
import ps.mgtik.hotspot.domain.repository.IAuditRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HistoryRepositoryImpl - تطبيق مستودع سجل ودفعات العمليات التاريخية.
 */
@Singleton
class HistoryRepositoryImpl @Inject constructor(
    private val cloudDataSource: CloudHistoryDataSource,
    private val offlineRepo: IOfflineCommandRepository,
    private val offlineScheduler: IOfflineScheduler,
    private val auditRepo: IAuditRepository
) : IHistoryRepository {

    private val gson = Gson()

    override fun getHistoricalBatches(filter: HistoryFilter): Flow<List<HistoricalBatch>> =
        cloudDataSource.getHistoricalBatchesFlow(filter)

    override fun getHistoricalBatchById(batchId: String): Flow<HistoricalBatch?> =
        cloudDataSource.getHistoricalBatchByIdFlow(batchId)

    override suspend fun archiveBatch(batchId: String, skipQueue: Boolean): AppResult<Unit> {
        if (skipQueue) {
            return cloudDataSource.archiveBatch(batchId).also {
                if (it is AppResult.Success) {
                    auditRepo.logAction("HISTORY_ARCHIVE", "Archived batch: $batchId", LogSeverity.INFO)
                }
            }
        }

        val command = OfflineCommand(
            type = OfflineCommandType.ARCHIVE_HISTORY_BATCH,
            payloadJson = batchId,
            idempotencyKey = batchId
        )
        offlineRepo.addCommand(command)
        offlineScheduler.scheduleSync()
        
        auditRepo.logAction("HISTORY_ARCHIVE_QUEUED", "Queued archiving command for batch: $batchId", LogSeverity.INFO)
        return AppResult.Success(Unit)
    }

    override suspend fun exportHistoryToCsv(filter: HistoryFilter): AppResult<String> =
        cloudDataSource.exportHistoryToCsv(filter).also {
            if (it is AppResult.Success) {
                auditRepo.logAction("HISTORY_EXPORT", "Exported history to CSV", LogSeverity.INFO)
            }
        }
}

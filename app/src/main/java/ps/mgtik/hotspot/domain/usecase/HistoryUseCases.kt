package ps.mgtik.hotspot.domain.usecase

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.HistoricalBatch
import ps.mgtik.hotspot.domain.model.HistoryFilter
import ps.mgtik.hotspot.domain.repository.IHistoryRepository

class GetHistoricalBatchesUseCase @Inject constructor(private val historyRepository: IHistoryRepository) {
    operator fun invoke(filter: HistoryFilter): Flow<List<HistoricalBatch>> =
        historyRepository.getHistoricalBatches(filter)
}

class GetHistoricalBatchDetailUseCase @Inject constructor(private val historyRepository: IHistoryRepository) {
    operator fun invoke(batchId: String): Flow<HistoricalBatch?> =
        historyRepository.getHistoricalBatchById(batchId)
}

class ArchiveBatchUseCase @Inject constructor(private val historyRepository: IHistoryRepository) {
    suspend operator fun invoke(batchId: String): AppResult<Unit> =
        historyRepository.archiveBatch(batchId)
}

class ExportHistoryToCsvUseCase @Inject constructor(private val historyRepository: IHistoryRepository) {
    suspend operator fun invoke(filter: HistoryFilter): AppResult<String> =
        historyRepository.exportHistoryToCsv(filter)
}

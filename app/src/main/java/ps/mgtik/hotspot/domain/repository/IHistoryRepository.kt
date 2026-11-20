package ps.mgtik.hotspot.domain.repository

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.HistoricalBatch
import ps.mgtik.hotspot.domain.model.HistoryFilter

/**
 * IHistoryRepository - عقد أرشيف وسجل الدفعات السابقة (S21)
 * تم التدقيق لدعم التنفيذ المباشر (skipQueue) لضمان اتساق العمليات مع نظام الـ Offline.
 * skipQueue (معنى ثابت في كل المستودعات): false → تمر العملية عبر الطابور/WorkManager، true → تنفيذ مباشر بلا طابور.
 */
interface IHistoryRepository {
    fun getHistoricalBatches(filter: HistoryFilter): Flow<List<HistoricalBatch>>
    fun getHistoricalBatchById(batchId: String): Flow<HistoricalBatch?>
    suspend fun archiveBatch(batchId: String, skipQueue: Boolean = false): AppResult<Unit>
    suspend fun exportHistoryToCsv(filter: HistoryFilter): AppResult<String>
}

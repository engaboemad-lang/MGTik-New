package ps.mgtik.hotspot.data.generation

import androidx.room.withTransaction
import ps.mgtik.hotspot.data.local.cache.LocalCacheManager
import ps.mgtik.hotspot.data.local.db.MgtikDatabase
import ps.mgtik.hotspot.data.local.dao.GenerationDao
import ps.mgtik.hotspot.data.local.entity.GenerationRunEntity
import ps.mgtik.hotspot.data.local.entity.StagedVoucherEntity
import ps.mgtik.hotspot.domain.generation.RunState
import ps.mgtik.hotspot.domain.generation.RunStore
import ps.mgtik.hotspot.domain.generation.StagedVoucher
import ps.mgtik.hotspot.domain.generation.VoucherState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RoomRunStore - مخزن الساغا المحلي (Room). كل save يعكس الحالة أيضاً إلى جدول executions
 * ليراقبها S5 عبر LocalCacheManager.observeExecution دون شبكة.
 */
@Singleton
class RoomRunStore @Inject constructor(
    private val db: MgtikDatabase,
    private val dao: GenerationDao,
    private val cache: LocalCacheManager
) : RunStore {

    override suspend fun load(executionId: String): RunState? = dao.getRun(executionId)?.toState()

    override suspend fun save(state: RunState) {
        // معاملة واحدة: انقطاع بين الكتابتين لا يترك generation_runs وexecutions غير متسقين
        db.withTransaction {
            dao.saveRunPreservingCancel(GenerationRunEntity.fromState(state))
            cache.saveExecution(RunMapping.toExecution(state))
        }
    }

    override suspend fun setCancelRequested(executionId: String, requested: Boolean) =
        dao.setCancelRequested(executionId, requested)

    override suspend fun isCancelRequested(executionId: String): Boolean = dao.getCancelRequested(executionId) ?: false

    override suspend fun staged(executionId: String, state: VoucherState?): List<StagedVoucher> =
        (if (state == null) dao.getStaged(executionId) else dao.getStagedByState(executionId, state.name)).map { it.toDomain() }

    override suspend fun insertStaged(vouchers: List<StagedVoucher>) {
        // عقد RunStore: الإدراج كله في معاملة واحدة (انقطاع بين الدفعات الصغيرة لا يترك مجموعة كروت ناقصة).
        // الدفعات الصغيرة لتبقى داخل حد متغيرات SQLite.
        db.withTransaction {
            vouchers.chunked(500).forEach { chunk -> dao.insertStaged(chunk.map(StagedVoucherEntity::fromDomain)) }
        }
    }

    override suspend fun setVoucherStates(executionId: String, seqs: List<Int>, state: VoucherState, error: String?) {
        // IN (...) محدود بـ 999 متغيراً في SQLite القديم؛ المعاملة الواحدة تمنع حالة نصف مطبَّقة عند الانقطاع
        db.withTransaction {
            seqs.chunked(500).forEach { dao.setStates(executionId, it, state.name, error) }
        }
    }

    override suspend fun counts(executionId: String): Map<VoucherState, Int> =
        dao.counts(executionId).mapNotNull { row ->
            runCatching { VoucherState.valueOf(row.state) }.getOrNull()?.let { it to row.n }
        }.toMap()
}

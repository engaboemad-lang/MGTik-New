package ps.mgtik.hotspot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.data.local.entity.GenerationRunEntity
import ps.mgtik.hotspot.data.local.entity.StagedVoucherEntity

data class VoucherStateCount(val state: String, val n: Int)

data class FailureReasonRow(val reason: String, val n: Int)

@Dao
interface GenerationDao {

    // ---- الساغا
    @Query("SELECT * FROM generation_runs WHERE executionId = :executionId")
    suspend fun getRun(executionId: String): GenerationRunEntity?

    @Query("SELECT * FROM generation_runs WHERE batch_id = :batchId LIMIT 1")
    fun observeRunByBatch(batchId: String): Flow<GenerationRunEntity?>

    @Query("SELECT * FROM generation_runs WHERE batch_id = :batchId LIMIT 1")
    suspend fun getRunByBatch(batchId: String): GenerationRunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(run: GenerationRunEntity)

    @Query("SELECT cancel_requested FROM generation_runs WHERE executionId = :executionId")
    suspend fun getCancelRequested(executionId: String): Boolean?

    @Query("UPDATE generation_runs SET cancel_requested = :requested WHERE executionId = :executionId")
    suspend fun setCancelRequested(executionId: String, requested: Boolean)

    /** يحفظ الحالة دون المساس بعلم الإلغاء (يملكه setCancelRequested وحده). */
    @Transaction
    suspend fun saveRunPreservingCancel(run: GenerationRunEntity) {
        val flag = getCancelRequested(run.executionId) ?: run.cancelRequested
        upsertRun(run.copy(cancelRequested = flag))
    }

    /** ساغات يجب استئنافها عند الإقلاع: غير النهائية وغير الفاشلة (الفاشلة تنتظر إعادة محاولة المستخدم). */
    @Query("SELECT executionId FROM generation_runs WHERE status IN ('QUEUED', 'RUNNING', 'RETRYING')")
    suspend fun getResumableRunIds(): List<String>

    // ---- الكروت المجهَّزة
    @Query("SELECT * FROM staged_vouchers WHERE execution_id = :executionId ORDER BY seq")
    suspend fun getStaged(executionId: String): List<StagedVoucherEntity>

    @Query("SELECT * FROM staged_vouchers WHERE execution_id = :executionId AND state = :state ORDER BY seq")
    suspend fun getStagedByState(executionId: String, state: String): List<StagedVoucherEntity>

    @Query("SELECT * FROM staged_vouchers WHERE execution_id = :executionId AND state IN ('ROUTER_OK', 'CLOUD_OK') ORDER BY seq")
    suspend fun getDeliverable(executionId: String): List<StagedVoucherEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStaged(vouchers: List<StagedVoucherEntity>)

    @Query("UPDATE staged_vouchers SET state = :state, error = :error WHERE execution_id = :executionId AND seq IN (:seqs)")
    suspend fun setStates(executionId: String, seqs: List<Int>, state: String, error: String?)

    @Query("SELECT state AS state, COUNT(*) AS n FROM staged_vouchers WHERE execution_id = :executionId GROUP BY state")
    suspend fun counts(executionId: String): List<VoucherStateCount>

    /** أسباب رفض الراوتر لكروت دفعة (مجمّعة بالسبب، الأكثر أولاً). */
    @Query("""SELECT COALESCE(error, '') AS reason, COUNT(*) AS n FROM staged_vouchers
              WHERE execution_id = (SELECT executionId FROM generation_runs WHERE batch_id = :batchId LIMIT 1)
              AND state = 'ROUTER_FAILED' GROUP BY error ORDER BY n DESC LIMIT 5""")
    fun observeFailureReasonsByBatch(batchId: String): Flow<List<FailureReasonRow>>

    /**
     * إعادة المرفوض للمحاولة. يُستثنى USERNAME_TAKEN: الاسم لحساب غريب على الراوتر فإعادة دفعه تفشل بالنتيجة نفسها دائماً
     * (حلقة فشل بلا فائدة)؛ يبقى مرفوضاً ظاهراً في النتيجة حتى يُقرَّر استبدال الاسم.
     */
    @Query("UPDATE staged_vouchers SET state = 'PENDING', error = NULL WHERE execution_id = :executionId AND state = 'ROUTER_FAILED' AND (error IS NULL OR error != 'ERR_GEN_USERNAME_TAKEN')")
    suspend fun resetFailedVouchers(executionId: String)
}

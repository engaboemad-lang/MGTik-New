package ps.mgtik.hotspot.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.data.local.entity.VoucherBatchEntity

@Dao
interface VoucherBatchDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(batch: VoucherBatchEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatches(batches: List<VoucherBatchEntity>)

    @Query("SELECT * FROM cached_voucher_batches WHERE id = :batchId")
    suspend fun getBatchById(batchId: String): VoucherBatchEntity?

    @Query("SELECT * FROM cached_voucher_batches ORDER BY cached_at DESC")
    fun getAllBatchesFlow(): Flow<List<VoucherBatchEntity>>

    @Query("SELECT * FROM cached_voucher_batches WHERE cloud_profile_id = :cloudProfileId ORDER BY cached_at DESC")
    fun getBatchesByProfileFlow(cloudProfileId: String): Flow<List<VoucherBatchEntity>>

    @Query("SELECT * FROM cached_voucher_batches WHERE owner_id = :ownerId ORDER BY cached_at DESC")
    fun getBatchesByOwnerFlow(ownerId: String): Flow<List<VoucherBatchEntity>>

    @Query("SELECT * FROM cached_voucher_batches WHERE order_id = :orderId ORDER BY cached_at DESC")
    fun getBatchesByOrderFlow(orderId: String): Flow<List<VoucherBatchEntity>>

    // إحصائيات البروفايل (§4.6)
    @Query("SELECT COUNT(*) FROM cached_voucher_batches WHERE cloud_profile_id = :cloudProfileId")
    suspend fun getBatchesCountForProfile(cloudProfileId: String): Int

    @Query("SELECT SUM(successful_quantity) FROM cached_voucher_batches WHERE cloud_profile_id = :cloudProfileId")
    suspend fun getTotalVouchersForProfile(cloudProfileId: String): Int?

    @Query("SELECT * FROM cached_voucher_batches WHERE cloud_profile_id = :cloudProfileId ORDER BY cached_at DESC LIMIT 1")
    suspend fun getLatestBatchForProfile(cloudProfileId: String): VoucherBatchEntity?

    @Query("SELECT owner_id FROM cached_voucher_batches WHERE cloud_profile_id = :cloudProfileId GROUP BY owner_id ORDER BY COUNT(*) DESC LIMIT 1")
    suspend fun getTopOwnerForProfile(cloudProfileId: String): String?

    @Query("DELETE FROM cached_voucher_batches")
    suspend fun clearAllBatches()
}

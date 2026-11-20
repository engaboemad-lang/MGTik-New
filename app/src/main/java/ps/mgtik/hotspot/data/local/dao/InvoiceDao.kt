package ps.mgtik.hotspot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.util.SORTABLE_ISO_GLOB
import ps.mgtik.hotspot.data.local.entity.InvoiceEntity

@Dao
interface InvoiceDao {
    @Query("SELECT * FROM cached_invoices WHERE (:ownerId IS NULL OR owner_id = :ownerId) AND (:status IS NULL OR status = :status) ORDER BY created_at DESC")
    fun getFilteredInvoicesFlow(ownerId: String?, status: String?): Flow<List<InvoiceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInvoices(invoices: List<InvoiceEntity>)

    /** صفوف كاش بتنسيق تاريخ قديم (Date.toString أو ISO متغير العرض): تُحذف وتعود نظيفة من السحابة. */
    @Query("DELETE FROM cached_invoices WHERE created_at != '' AND created_at NOT GLOB '$SORTABLE_ISO_GLOB'")
    suspend fun deleteLegacyDateRows(): Int

    @Query("DELETE FROM cached_invoices")
    suspend fun clearAll()
}

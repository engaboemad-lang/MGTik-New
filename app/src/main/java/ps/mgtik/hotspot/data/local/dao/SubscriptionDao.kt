package ps.mgtik.hotspot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.data.local.entity.SubscriptionEntity

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM cached_subscriptions ORDER BY cached_at DESC")
    fun getAllSubscriptionsFlow(): Flow<List<SubscriptionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscriptions(subs: List<SubscriptionEntity>)

    /**
     * صفوف بيانات تجريبية (SUB-01..03 بملاكها OWN-01..03) كانت تُحفَظ في الكاش عند خلو مجموعة subscriptions.
     * المعرّفات الحقيقية UUID، فلا تصادم. لا يمس أي صف آخر.
     */
    @Query("DELETE FROM cached_subscriptions WHERE id IN ('SUB-01', 'SUB-02', 'SUB-03') AND owner_id IN ('OWN-01', 'OWN-02', 'OWN-03')")
    suspend fun deleteDemoRows(): Int

    @Query("DELETE FROM cached_subscriptions")
    suspend fun clearAll()
}

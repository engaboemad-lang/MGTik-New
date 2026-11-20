package ps.mgtik.hotspot.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.data.local.entity.DiscoveryEntity

@Dao
interface DiscoveryDao {
    @Query("SELECT * FROM discovery_records WHERE status = 'open' ORDER BY last_detected_at DESC")
    fun getOpenDiscoveriesFlow(): Flow<List<DiscoveryEntity>>

    @Query("SELECT * FROM discovery_records WHERE type = :type AND status = 'open'")
    fun getOpenDiscoveriesByTypeFlow(type: String): Flow<List<DiscoveryEntity>>

    @Query("SELECT * FROM discovery_records WHERE discoveryId = :id")
    suspend fun getDiscoveryById(id: String): DiscoveryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDiscoveries(records: List<DiscoveryEntity>)

    @Update
    suspend fun updateDiscovery(record: DiscoveryEntity)

    @Query("SELECT discoveryId FROM discovery_records")
    suspend fun getAllIds(): List<String>

    @Query("DELETE FROM discovery_records WHERE discoveryId IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM discovery_records WHERE discoveryId = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM discovery_records")
    suspend fun clearAll()
}

package ps.mgtik.hotspot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.data.local.entity.ProfileEntity

@Dao
interface ProfileDao {
    @Query("SELECT * FROM cached_profiles")
    fun getAllProfilesFlow(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM cached_profiles")
    suspend fun getAllProfiles(): List<ProfileEntity>

    @Query("SELECT * FROM cached_profiles WHERE id = :id")
    suspend fun getProfileById(id: String): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfiles(profiles: List<ProfileEntity>)

    @Query("DELETE FROM cached_profiles WHERE id = :id")
    suspend fun deleteById(id: String)

    /** حذف بمعرّفات محددة (يُستدعى على دفعات صغيرة داخل حد متغيرات SQLite). */
    @Query("DELETE FROM cached_profiles WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM cached_profiles")
    suspend fun clearAll()
}

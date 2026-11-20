package ps.mgtik.hotspot.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.util.SORTABLE_ISO_GLOB
import ps.mgtik.hotspot.data.local.entity.LocalUserEntity

@Dao
interface LocalUserDao {
    @Query("SELECT * FROM local_users WHERE recordUuid = :uuid")
    suspend fun getUserByUuid(uuid: String): LocalUserEntity?

    @Query("SELECT * FROM local_users WHERE (:ownerId IS NULL OR owner_id = :ownerId) AND (:status IS NULL OR status = :status) ORDER BY created_at DESC")
    fun getUsersFilteredFlow(ownerId: String?, status: String?): Flow<List<LocalUserEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsers(users: List<LocalUserEntity>)

    @Update
    suspend fun updateUser(user: LocalUserEntity)

    @Query("DELETE FROM local_users WHERE recordUuid = :uuid")
    suspend fun deleteByUuid(uuid: String)

    @Query("SELECT recordUuid FROM local_users")
    suspend fun getAllUuids(): List<String>

    @Query("DELETE FROM local_users WHERE recordUuid IN (:uuids)")
    suspend fun deleteByUuids(uuids: List<String>)

    /** صفوف كاش بتنسيق تاريخ قديم: تُحذف وتعود نظيفة من السحابة. */
    @Query("DELETE FROM local_users WHERE created_at != '' AND created_at NOT GLOB '$SORTABLE_ISO_GLOB'")
    suspend fun deleteLegacyDateRows(): Int

    @Query("DELETE FROM local_users")
    suspend fun clearAll()
}

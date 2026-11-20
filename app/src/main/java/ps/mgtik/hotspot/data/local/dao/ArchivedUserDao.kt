package ps.mgtik.hotspot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ps.mgtik.hotspot.data.local.entity.ArchivedUserEntity

/** نسخة محلية من الأرشيف السحابي: تُكتب عند قراءة صفحة أو عند إتمام أرشفة، وتُقرأ فقط حين تتعذر قراءة السحابة. */
@Dao
interface ArchivedUserDao {
    @Query("SELECT * FROM archived_users ORDER BY archived_at DESC, archiveId DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<ArchivedUserEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ArchivedUserEntity>)

    @Query("DELETE FROM archived_users")
    suspend fun clearAll()
}

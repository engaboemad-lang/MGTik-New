package ps.mgtik.hotspot.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.data.local.entity.OfflineCommandEntity

@Dao
interface OfflineCommandDao {

    @Query("SELECT * FROM offline_commands ORDER BY created_at ASC")
    fun getAllCommandsFlow(): Flow<List<OfflineCommandEntity>>

    /** الأوامر القابلة للتنفيذ (لم تستنفد محاولاتها) بترتيب الإنشاء (FIFO). */
    @Query("SELECT * FROM offline_commands WHERE attempt_count < :maxAttempts ORDER BY created_at ASC")
    suspend fun getRunnableCommands(maxAttempts: Int): List<OfflineCommandEntity>

    /** فشل نهائي: يرفع العداد للحد الأقصى فلا يُنفَّذ مجدداً، ويحفظ سبب الفشل. */
    @Query("UPDATE offline_commands SET attempt_count = :maxAttempts, last_error = :error WHERE commandId = :commandId")
    suspend fun markFailed(commandId: String, maxAttempts: Int, error: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommand(command: OfflineCommandEntity)

    @Query("DELETE FROM offline_commands WHERE commandId = :commandId")
    suspend fun deleteCommandById(commandId: String)

    @Query("UPDATE offline_commands SET attempt_count = attempt_count + 1, last_error = :error WHERE commandId = :commandId")
    suspend fun incrementAttemptCount(commandId: String, error: String?)

    @Query("DELETE FROM offline_commands")
    suspend fun clearAll()
}

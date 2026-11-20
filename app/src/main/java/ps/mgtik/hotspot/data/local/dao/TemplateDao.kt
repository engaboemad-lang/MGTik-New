package ps.mgtik.hotspot.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.data.local.entity.TemplateEntity

@Dao
interface TemplateDao {
    @Query("SELECT * FROM templates ORDER BY updatedAt DESC")
    fun getAllTemplates(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM templates WHERE templateId = :id")
    fun getTemplateById(id: String): Flow<TemplateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: TemplateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplates(templates: List<TemplateEntity>)

    @Query("DELETE FROM templates WHERE templateId = :id")
    suspend fun deleteTemplate(id: String)

    @Query("DELETE FROM templates")
    suspend fun clearAll()

    @Query("SELECT templateId FROM templates")
    suspend fun getAllIds(): List<String>
}

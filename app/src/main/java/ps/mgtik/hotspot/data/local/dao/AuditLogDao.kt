package ps.mgtik.hotspot.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.data.local.entity.AuditLogEntity

@Dao
interface AuditLogDao {
    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_logs WHERE severity = :severity ORDER BY timestamp DESC")
    fun getLogsBySeverity(severity: String): Flow<List<AuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: AuditLogEntity)

    @Query("DELETE FROM audit_logs WHERE timestamp < :timestamp")
    suspend fun deleteOldLogs(timestamp: Long)

    @Query("DELETE FROM audit_logs")
    suspend fun clearAll()
}

package ps.mgtik.hotspot.domain.repository

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.domain.model.OfflineCommand

/**
 * IOfflineCommandRepository - عقد إدارة طابور الأوامر دون اتصال (§5.3)
 */
interface IOfflineCommandRepository {
    fun getPendingCommands(): Flow<List<OfflineCommand>>
    suspend fun addCommand(command: OfflineCommand)
    suspend fun removeCommand(commandId: String)
    /** أوامر لم تستنفد محاولاتها، بترتيب الإنشاء. */
    suspend fun getRunnableCommands(maxAttempts: Int): List<OfflineCommand>
    suspend fun updateAttempt(commandId: String, error: String?)
    /** فشل نهائي لا تُجدي معه الإعادة (خطأ تحقق، صلاحيات، حمولة تالفة...). */
    suspend fun markFailed(commandId: String, error: String?, maxAttempts: Int)
    suspend fun clearAll()
}

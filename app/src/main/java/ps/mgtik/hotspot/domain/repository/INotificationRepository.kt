package ps.mgtik.hotspot.domain.repository

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.MgtikNotification

/**
 * INotificationRepository - عقد مركز الإشعارات والتنبيهات الحية (S20)
 * تم توحيده لاستخدام MgtikNotification حسب DomainEntities.kt (§6)
 */
interface INotificationRepository {
    fun getNotifications(): Flow<List<MgtikNotification>>
    fun getUnreadCount(): Flow<Int>
    suspend fun markAsRead(notificationId: String): AppResult<Unit>
    suspend fun markAllAsRead(): AppResult<Unit>
    suspend fun deleteNotification(notificationId: String): AppResult<Unit>
    suspend fun clearAllNotifications(): AppResult<Unit>
    suspend fun addNotification(notification: MgtikNotification): AppResult<Unit>
}

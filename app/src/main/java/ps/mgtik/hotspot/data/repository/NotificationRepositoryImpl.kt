package ps.mgtik.hotspot.data.repository

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.data.cloud.source.CloudNotificationDataSource
import ps.mgtik.hotspot.domain.model.MgtikNotification
import ps.mgtik.hotspot.domain.repository.INotificationRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NotificationRepositoryImpl - تطبيق مستودع مركز التنبيهات الفورية
 */
@Singleton
class NotificationRepositoryImpl @Inject constructor(
    private val cloudDataSource: CloudNotificationDataSource
) : INotificationRepository {

    override fun getNotifications(): Flow<List<MgtikNotification>> = 
        cloudDataSource.getNotificationsFlow()

    override fun getUnreadCount(): Flow<Int> = 
        cloudDataSource.getUnreadCountFlow()

    override suspend fun markAsRead(notificationId: String): AppResult<Unit> =
        cloudDataSource.markAsRead(notificationId)

    override suspend fun markAllAsRead(): AppResult<Unit> =
        cloudDataSource.markAllAsRead()

    override suspend fun deleteNotification(notificationId: String): AppResult<Unit> =
        cloudDataSource.deleteNotification(notificationId)

    override suspend fun clearAllNotifications(): AppResult<Unit> =
        cloudDataSource.clearAll()

    override suspend fun addNotification(notification: MgtikNotification): AppResult<Unit> =
        cloudDataSource.addNotification(notification)
}

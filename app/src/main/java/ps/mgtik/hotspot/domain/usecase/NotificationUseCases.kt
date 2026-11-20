package ps.mgtik.hotspot.domain.usecase

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.MgtikNotification
import ps.mgtik.hotspot.domain.repository.INotificationRepository

class GetNotificationsUseCase @Inject constructor(private val notificationRepository: INotificationRepository) {
    operator fun invoke(): Flow<List<MgtikNotification>> = notificationRepository.getNotifications()
}

class GetUnreadNotificationsCountUseCase @Inject constructor(private val notificationRepository: INotificationRepository) {
    operator fun invoke(): Flow<Int> = notificationRepository.getUnreadCount()
}

class MarkNotificationReadUseCase @Inject constructor(private val notificationRepository: INotificationRepository) {
    suspend operator fun invoke(notificationId: String): AppResult<Unit> =
        notificationRepository.markAsRead(notificationId)
}

class MarkAllNotificationsReadUseCase @Inject constructor(private val notificationRepository: INotificationRepository) {
    suspend operator fun invoke(): AppResult<Unit> = notificationRepository.markAllAsRead()
}

class ClearAllNotificationsUseCase @Inject constructor(private val notificationRepository: INotificationRepository) {
    suspend operator fun invoke(): AppResult<Unit> = notificationRepository.clearAllNotifications()
}

class AddNotificationUseCase @Inject constructor(private val notificationRepository: INotificationRepository) {
    suspend operator fun invoke(notification: MgtikNotification): AppResult<Unit> =
        notificationRepository.addNotification(notification)
}

class DeleteNotificationUseCase @Inject constructor(private val notificationRepository: INotificationRepository) {
    suspend operator fun invoke(notificationId: String): AppResult<Unit> =
        notificationRepository.deleteNotification(notificationId)
}

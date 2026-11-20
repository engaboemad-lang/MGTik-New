package ps.mgtik.hotspot.ui.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import javax.inject.Inject
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.ui.util.ErrorTranslator
import ps.mgtik.hotspot.domain.model.MgtikNotification
import ps.mgtik.hotspot.domain.usecase.*

data class NotificationUiState(
    val notifications: List<MgtikNotification> = emptyList(),
    val unreadCount: Int = 0,
    val isLoading: Boolean = false,
    val hasLoadError: Boolean = false,
    /** فشل آخر عملية (تعليم مقروء/تعليم الكل/مسح الكل). منفصل عن [error] حتى لا تُستبدل القائمة بشاشة خطأ التحميل. */
    @StringRes val actionErrorResId: Int? = null
)

@HiltViewModel
class NotificationViewModel @Inject constructor(
    private val getSessionStateUseCase: GetSessionStateUseCase,
    private val getNotificationsUseCase: GetNotificationsUseCase,
    private val getUnreadCountUseCase: GetUnreadNotificationsCountUseCase,
    private val markReadUseCase: MarkNotificationReadUseCase,
    private val markAllReadUseCase: MarkAllNotificationsReadUseCase,
    private val clearAllUseCase: ClearAllNotificationsUseCase,
    private val deleteUseCase: DeleteNotificationUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationUiState())
    val uiState: StateFlow<NotificationUiState> = _uiState.asStateFlow()

    private var notificationsJob: Job? = null

    init {
        observeNotifications()
        observeUnreadCount()
    }

    private fun observeNotifications() {
        notificationsJob?.cancel()
        notificationsJob = getSessionStateUseCase.gate(onSignedOut = { _uiState.value = NotificationUiState() }) { getNotificationsUseCase() }
            .onStart { _uiState.update { it.copy(isLoading = true, hasLoadError = false) } }
            .onEach { list ->
                _uiState.update { it.copy(notifications = list, isLoading = false) }
            }
            .catch { _uiState.update { it.copy(hasLoadError = true, isLoading = false) } }
            .launchIn(viewModelScope)
    }

    private fun observeUnreadCount() {
        getSessionStateUseCase.gate { getUnreadCountUseCase() }
            .onEach { count -> _uiState.update { it.copy(unreadCount = count) } }
            .catch { }
            .launchIn(viewModelScope)
    }

    /** إعادة محاولة تحميل الإشعارات بعد فشل (نفس مسار التحميل الأصلي). */
    fun retry() = observeNotifications()

    /** يُستدعى بعد عرض رسالة فشل العملية للمستخدم حتى لا تتكرر. */
    fun consumeActionError() = _uiState.update { it.copy(actionErrorResId = null) }

    fun markAsRead(id: String) = runAction { markReadUseCase(id) }

    fun markAllAsRead() = runAction { markAllReadUseCase() }

    fun clearAll() = runAction { clearAllUseCase() }

    fun deleteNotification(id: String) = runAction { deleteUseCase(id) }

    /**
     * ينفّذ عملية على الإشعارات ويتعامل مع نتيجتها.
     * عند النجاح: لا تعديل محلي، فمستمعو Firestore (القائمة والعدّاد) يعكسون التغيير تلقائياً.
     * عند الفشل: يُحفظ سبب الفشل في [NotificationUiState.actionErrorResId].
     */
    private fun runAction(operation: suspend () -> AppResult<Unit>) {
        viewModelScope.launch {
            _uiState.update { it.copy(actionErrorResId = null) }
            val res = try {
                operation()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.Error(ApiError.mapThrowable(e))
            }
            when (res) {
                is AppResult.Success -> Unit
                is AppResult.Error -> _uiState.update { it.copy(actionErrorResId = ErrorTranslator.resIdOf(res.error)) }
                is AppResult.Loading -> Unit
            }
        }
    }
}

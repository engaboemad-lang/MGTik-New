package ps.mgtik.hotspot.ui.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.IdempotencyKeyProvider
import ps.mgtik.hotspot.domain.model.AuditLog
import ps.mgtik.hotspot.domain.model.LocalUserRecord
import ps.mgtik.hotspot.domain.model.LogSeverity
import ps.mgtik.hotspot.domain.model.SessionEndReason
import ps.mgtik.hotspot.domain.model.Subscription
import ps.mgtik.hotspot.domain.model.SystemClass
import ps.mgtik.hotspot.domain.model.TrackedSession
import ps.mgtik.hotspot.domain.model.UserRecordStatus
import ps.mgtik.hotspot.domain.model.UserSessionStats
import ps.mgtik.hotspot.domain.repository.IAuditRepository
import ps.mgtik.hotspot.domain.repository.IOwnerRepository
import ps.mgtik.hotspot.domain.repository.ISessionHistoryRepository
import ps.mgtik.hotspot.domain.repository.ISubscriptionRepository
import ps.mgtik.hotspot.domain.repository.IUserRepository
import ps.mgtik.hotspot.domain.usecase.DisconnectSessionUseCase
import ps.mgtik.hotspot.domain.usecase.GetLiveSessionsUseCase
import ps.mgtik.hotspot.domain.usecase.GetRouterConfigUseCase
import ps.mgtik.hotspot.domain.usecase.SessionTracker
import ps.mgtik.hotspot.ui.util.ErrorTranslator
import javax.inject.Inject

/** حالة قسم تحميل منفصل: لا نخلط «لم يُحمَّل» و«فشل» و«لا بيانات» (الأخيرة ليست صفراً). */
sealed interface SectionState<out T> {
    data object NotLoaded : SectionState<Nothing>
    data object Loading : SectionState<Nothing>
    data class Loaded<T>(val value: T) : SectionState<T>
    data class Failed(@StringRes val errorResId: Int) : SectionState<Nothing>
}

enum class WorkspaceAction { DISABLE, ENABLE }

data class UserWorkspaceUiState(
    val username: String = "",
    val accountLoading: Boolean = true,
    /** null بعد انتهاء التحميل = الحساب غير مسجَّل في سجل المستخدمين السحابي (مستخدم راوتر فقط). */
    val record: LocalUserRecord? = null,
    val subscription: Subscription? = null,
    val ownerName: String? = null,
    val accountErrorResId: Int? = null,
    /** إحصائيات الحساب المُغلقة؛ Loaded(null) = لا سجل بعد (غير متاحة وليست صفراً). */
    val stats: SectionState<UserSessionStats?> = SectionState.NotLoaded,
    val history: SectionState<List<TrackedSession>> = SectionState.NotLoaded,
    val activity: SectionState<List<AuditLog>> = SectionState.NotLoaded,
    val busy: WorkspaceAction? = null,
    @StringRes val messageResId: Int? = null,
    val messageArg: String? = null,
    val messageIsError: Boolean = false
) {
    /** المشترك الشهري: يُدار بدورة الاشتراك لا بإجراءات المستخدم العادي. */
    val isSubscriber: Boolean get() = subscription != null || record?.systemClass == SystemClass.SUBSCRIPTION
}

/**
 * UserWorkspaceViewModel - مساحة عمل مستخدم واحد (المستوى الثالث).
 *
 * الجلسة الحالية لا تُجلب هنا: تأتي من LiveSessionsViewModel المشترك (مصدر واحد للجلسات الحية، لا طلبات مكررة).
 * هنا: بيانات الحساب (سجل المستخدمين/الاشتراك/المالك)، الإحصائيات التراكمية، سجل الجلسات عند الطلب، النشاطات، والإجراءات.
 * الفصل (Disconnect) ينفَّذ عبر LiveSessionsViewModel.disconnect نفسه؛ التعطيل/التفعيل هنا عبر IUserRepository الحالي (مباشر، متحقَّق).
 */
@HiltViewModel
class UserWorkspaceViewModel @Inject constructor(
    private val userRepository: IUserRepository,
    private val subscriptionRepository: ISubscriptionRepository,
    private val ownerRepository: IOwnerRepository,
    private val sessionHistory: ISessionHistoryRepository,
    private val auditRepository: IAuditRepository,
    private val getRouterConfig: GetRouterConfigUseCase,
    private val getLiveSessions: GetLiveSessionsUseCase,
    private val disconnectSession: DisconnectSessionUseCase,
    private val sessionTracker: SessionTracker
) : ViewModel() {

    private val _uiState = MutableStateFlow(UserWorkspaceUiState())
    val uiState: StateFlow<UserWorkspaceUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var historyJob: Job? = null

    /** فتح مساحة عمل مستخدم (أو إعادة فتحها بآخر)؛ يُلغي أي تحميل قديم ويصفّر الحالة. */
    fun open(username: String) {
        loadJob?.cancel(); historyJob?.cancel()
        _uiState.value = UserWorkspaceUiState(username = username)
        refresh()
    }

    /** إعادة تحديث بيانات الحساب والإحصائيات والنشاطات (الجلسة الحية تُحدَّث دورياً من المصدر المشترك). */
    fun refresh() {
        val username = _uiState.value.username
        if (username.isBlank()) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                accountLoading = true, accountErrorResId = null,
                stats = SectionState.Loading, activity = SectionState.Loading
            )
            loadAccount(username)
            launch { loadStats(username) }
            loadActivity(username)
            // إن كان سجل الجلسات مفتوحاً سابقاً نحدّثه أيضاً
            if (_uiState.value.history !is SectionState.NotLoaded) loadHistory()
        }
    }

    private suspend fun loadAccount(username: String) {
        try {
            val record = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                userRepository.listManagedUsers().first().firstOrNull { it.routerUsername == username }
            }
            val sub = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                subscriptionRepository.getSubscriptions().first()
                    .filter { it.customerUsername == username }
                    .maxByOrNull { it.createdAt }
            }
            val ownerId = record?.ownerId?.takeIf { it.isNotBlank() } ?: sub?.ownerId?.takeIf { it.isNotBlank() }
            val ownerName = ownerId?.let { id ->
                withTimeoutOrNull(LOAD_TIMEOUT_MS) { ownerRepository.getOwnerAccount(id).first()?.name }
                    ?: sub?.ownerName?.takeIf { it.isNotBlank() }
            } ?: sub?.ownerName?.takeIf { it.isNotBlank() }
            _uiState.value = _uiState.value.copy(
                accountLoading = false, record = record, subscription = sub, ownerName = ownerName
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                accountLoading = false, accountErrorResId = ErrorTranslator.resIdOf(ApiError.mapThrowable(e))
            )
        }
    }

    private suspend fun loadStats(username: String) {
        val res = sessionHistory.getUserStats(username)
        _uiState.value = _uiState.value.copy(
            stats = when (res) {
                is AppResult.Success -> SectionState.Loaded(res.data)
                is AppResult.Error -> SectionState.Failed(ErrorTranslator.resIdOf(res.error))
                is AppResult.Loading -> SectionState.Loading
            }
        )
    }

    private suspend fun loadActivity(username: String) {
        try {
            val uuid = _uiState.value.record?.recordUuid
            val logs = withTimeoutOrNull(LOAD_TIMEOUT_MS) { auditRepository.getLogs().first() }.orEmpty()
                .filter { it.detail.contains(username, ignoreCase = true) || (uuid != null && it.detail.contains(uuid)) }
                .take(ACTIVITY_LIMIT)
            _uiState.value = _uiState.value.copy(activity = SectionState.Loaded(logs))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(activity = SectionState.Failed(ErrorTranslator.resIdOf(ApiError.mapThrowable(e))))
        }
    }

    /** سجل الجلسات السابقة: عند الطلب فقط (لا يُحمَّل عند فتح الشاشة). */
    fun loadHistory() {
        val username = _uiState.value.username
        if (username.isBlank()) return
        historyJob?.cancel()
        _uiState.value = _uiState.value.copy(history = SectionState.Loading)
        historyJob = viewModelScope.launch {
            val res = sessionHistory.getSessionHistory(username, HISTORY_LIMIT)
            _uiState.value = _uiState.value.copy(
                history = when (res) {
                    is AppResult.Success -> SectionState.Loaded(res.data)
                    is AppResult.Error -> SectionState.Failed(ErrorTranslator.resIdOf(res.error))
                    is AppResult.Loading -> SectionState.Loading
                }
            )
        }
    }

    /**
     * تعطيل حساب مستخدم عادي: (1) تعطيل على الراوتر مع تحقق + تحديث Firestore + سجل نشاط (داخل IUserRepository)،
     * ثم (2) إنهاء أي جلسة نشطة له (RouterOS لا يفصل الجلسة الجارية عند تعطيل الحساب).
     * المشترك الشهري مرفوض هنا: يُدار من دورة الاشتراك.
     */
    fun disable() = runAction(WorkspaceAction.DISABLE)

    /** إعادة تفعيل مستخدم عادي معطَّل فقط (المنتهي EXPIRED والمشترك الشهري لهما مسارهما). */
    fun enable() = runAction(WorkspaceAction.ENABLE)

    private fun runAction(action: WorkspaceAction) {
        val s = _uiState.value
        if (s.busy != null) return
        val record = s.record ?: return postError(R.string.ws_err_not_managed)
        when {
            s.isSubscriber -> return postError(R.string.ws_err_subscription_managed)
            action == WorkspaceAction.DISABLE && record.status != UserRecordStatus.ACTIVE -> return postError(R.string.ws_err_not_active)
            action == WorkspaceAction.ENABLE && record.status != UserRecordStatus.DISABLED -> return postError(R.string.ws_err_not_disabled)
        }
        _uiState.value = s.copy(busy = action, messageResId = null)
        viewModelScope.launch {
            val key = IdempotencyKeyProvider.generateKey()
            val result = if (action == WorkspaceAction.DISABLE) {
                userRepository.disableUser(record.recordUuid, key, skipQueue = true)
            } else {
                userRepository.enableUser(record.recordUuid, key, skipQueue = true)
            }
            if (result is AppResult.Error) {
                _uiState.value = _uiState.value.copy(busy = null, messageResId = ErrorTranslator.resIdOf(result.error), messageArg = null, messageIsError = true)
                return@launch
            }
            if (action == WorkspaceAction.ENABLE) {
                reloadRecordAfterAction(record.routerUsername, R.string.ws_msg_enabled)
                return@launch
            }
            // تعطيل ناجح ← إنهاء الجلسة الحالية (إن وُجدت) ثم تقرير النتيجة بصدق
            val kicked = endActiveSessions(record.routerUsername)
            reloadRecordAfterAction(
                record.routerUsername,
                if (kicked) R.string.ws_msg_disabled_session_ended else R.string.ws_msg_disabled_session_failed,
                isError = !kicked
            )
        }
    }

    /** true = لا جلسة باقية (لم توجد أو فُصلت وتحققنا)، false = تعذر الفصل/التحقق. */
    private suspend fun endActiveSessions(username: String): Boolean {
        return try {
            val config = getRouterConfig().first().takeIf { it.host.isNotBlank() } ?: return false
            val live = (getLiveSessions(config) as? AppResult.Success)?.data ?: return false
            val mine = live.filter { it.username == username }
            sessionTracker.hintEndReason(username, SessionEndReason.ACCOUNT_DISABLED)
            mine.all { disconnectSession(config, it.sessionId) is AppResult.Success }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun reloadRecordAfterAction(username: String, @StringRes msg: Int, isError: Boolean = false) {
        val updated = try {
            userRepository.listManagedUsers().first().firstOrNull { it.routerUsername == username }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        _uiState.value = _uiState.value.copy(
            busy = null, record = updated ?: _uiState.value.record,
            messageResId = msg, messageArg = username, messageIsError = isError
        )
        loadActivity(username)
    }

    private fun postError(@StringRes res: Int) {
        _uiState.value = _uiState.value.copy(messageResId = res, messageArg = null, messageIsError = true)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(messageResId = null, messageArg = null)
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 8_000L
        const val HISTORY_LIMIT = 30
        const val ACTIVITY_LIMIT = 50
    }
}

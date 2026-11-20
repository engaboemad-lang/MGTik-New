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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.RouterStatus
import ps.mgtik.hotspot.domain.model.SessionImpact
import ps.mgtik.hotspot.domain.repository.ISessionManager
import ps.mgtik.hotspot.domain.usecase.ObserveRouterStatusUseCase
import ps.mgtik.hotspot.ui.util.ErrorTranslator
import javax.inject.Inject

/** الإجراءات الثلاثة المستقلة؛ تُستدعى من الإعدادات والقائمة الجانبية بالمنطق نفسه. */
enum class SessionAction { CLOUD_LOGOUT, ROUTER_LOGOUT, EXIT }

data class SessionMessage(@StringRes val resId: Int, val args: List<Any> = emptyList(), val isError: Boolean = false)

data class SessionActionsUiState(
    /** الإجراء المعروضة نافذة تأكيده. */
    val pending: SessionAction? = null,
    val impact: SessionImpact? = null,
    val impactLoading: Boolean = false,
    val impactFailed: Boolean = false,
    /** إجراء قيد التنفيذ (نافذة تقدّم؛ لا تُكرَّر الأوامر). */
    val running: SessionAction? = null,
    val message: SessionMessage? = null,
    /** تمّ الإنهاء المنظَّم: على الواجهة أن تُغلق النشاط بآلية Android الرسمية (finishAndRemoveTask). */
    val exitReady: Boolean = false,
    val exitNote: SessionMessage? = null
)

@HiltViewModel
class SessionActionsViewModel @Inject constructor(
    private val sessions: ISessionManager,
    observeRouterStatus: ObserveRouterStatusUseCase
) : ViewModel() {

    val routerStatus: StateFlow<RouterStatus> = observeRouterStatus()

    private val _state = MutableStateFlow(SessionActionsUiState())
    val state: StateFlow<SessionActionsUiState> = _state.asStateFlow()

    private var impactJob: Job? = null

    /** فتح نافذة التأكيد وفحص ما سيتأثر (طابور/توليد جارٍ) كي يُعرض بصدق قبل القرار. */
    fun request(action: SessionAction) {
        if (_state.value.running != null) return
        _state.update { it.copy(pending = action, impact = null, impactLoading = true, impactFailed = false) }
        impactJob?.cancel()
        impactJob = viewModelScope.launch {
            val r = sessions.inspect()
            _state.update {
                when (r) {
                    is AppResult.Success -> it.copy(impact = r.data, impactLoading = false, impactFailed = false)
                    else -> it.copy(impact = null, impactLoading = false, impactFailed = true)
                }
            }
        }
    }

    fun dismiss() {
        if (_state.value.running != null) return
        impactJob?.cancel()
        _state.update { it.copy(pending = null, impact = null, impactLoading = false, impactFailed = false) }
    }

    fun confirm() {
        val s = _state.value
        val action = s.pending ?: return
        if (s.running != null) return
        impactJob?.cancel()
        _state.update { it.copy(pending = null, running = action, message = null) }
        viewModelScope.launch {
            try {
                when (action) {
                    SessionAction.CLOUD_LOGOUT -> finishLogout(sessions.cloudLogout().let { r ->
                        when (r) {
                            is AppResult.Success -> {
                                val kept = r.data.pendingCommandsKept
                                if (kept > 0) SessionMessage(R.string.session_cloud_done_pending_fmt, listOf(kept))
                                else SessionMessage(R.string.session_cloud_done)
                            }
                            is AppResult.Error -> errorMessage(r.error)
                            is AppResult.Loading -> errorMessage(ApiError.unknownError("Unexpected loading state"))
                        }
                    })
                    SessionAction.ROUTER_LOGOUT -> finishLogout(sessions.routerLogout().let { r ->
                        when (r) {
                            is AppResult.Success -> SessionMessage(R.string.session_router_done)
                            is AppResult.Error -> errorMessage(r.error)
                            is AppResult.Loading -> errorMessage(ApiError.unknownError("Unexpected loading state"))
                        }
                    })
                    SessionAction.EXIT -> {
                        val report = sessions.prepareExit()
                        val left = report.stillRunning
                        val note = when {
                            report.failures.isNotEmpty() -> SessionMessage(R.string.session_exit_failures_note, isError = true)
                            left != null && (left.activeGenerationRuns > 0 || left.pendingCommands > 0) ->
                                SessionMessage(R.string.session_exit_note_fmt, listOf(left.activeGenerationRuns, left.pendingCommands))
                            else -> null
                        }
                        _state.update { it.copy(running = null, exitReady = true, exitNote = note) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(running = null, message = errorMessage(ApiError.mapThrowable(e))) }
            }
        }
    }

    private fun finishLogout(message: SessionMessage) {
        _state.update { it.copy(running = null, message = message) }
    }

    private fun errorMessage(error: ApiError) = SessionMessage(ErrorTranslator.resIdOf(error), isError = true)

    fun messageShown() = _state.update { it.copy(message = null) }

    fun exitHandled() = _state.update { it.copy(exitReady = false, exitNote = null) }
}

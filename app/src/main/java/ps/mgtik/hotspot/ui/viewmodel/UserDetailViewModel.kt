package ps.mgtik.hotspot.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.RouterUserSnapshot
import ps.mgtik.hotspot.domain.repository.IUserRepository
import ps.mgtik.hotspot.ui.util.ErrorTranslator
import javax.inject.Inject

data class UserDetailUiState(
    val recordUuid: String = "",
    val username: String = "",
    /** حساب الراوتر: Loaded(null) = غير موجود على الراوتر (حالة متحققة، ليست خطأ). */
    val router: SectionState<RouterUserSnapshot?> = SectionState.NotLoaded,
    /** كلمة المرور المحفوظة سحابياً؛ Loaded(null) = لا مصدر محفوظ. */
    val storedPassword: SectionState<String?> = SectionState.NotLoaded
)

/**
 * UserDetailViewModel - الجزء التشغيلي لصفحة تفاصيل المستخدم (S16D): قراءة حساب الراوتر وكلمة المرور المحفوظة فقط.
 * السجل الإداري والإجراءات تأتي من UsersViewModel نفسه (مصدر واحد، بلا منطق مكرر)، والجلسة الحية من LiveSessionsViewModel المشترك.
 * كل القراءات للعرض فقط: لا كتابة على الراوتر من هنا.
 */
@HiltViewModel
class UserDetailViewModel @Inject constructor(
    private val userRepository: IUserRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UserDetailUiState())
    val uiState: StateFlow<UserDetailUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    /** فتح/إعادة تحميل مستخدم؛ يلغي أي تحميل سابق ويظهر التحميل فوراً. */
    fun open(recordUuid: String, username: String) {
        _uiState.value = UserDetailUiState(recordUuid = recordUuid, username = username)
        load()
    }

    fun refresh() = load()

    private fun load() {
        val s = _uiState.value
        if (s.recordUuid.isBlank() || s.username.isBlank()) return
        loadJob?.cancel()
        _uiState.value = s.copy(router = SectionState.Loading, storedPassword = SectionState.Loading)
        loadJob = viewModelScope.launch {
            try {
                val router = userRepository.getRouterUserSnapshot(s.username)
                _uiState.value = _uiState.value.copy(
                    router = when (router) {
                        is AppResult.Success -> SectionState.Loaded(router.data)
                        is AppResult.Error -> SectionState.Failed(ErrorTranslator.resIdOf(router.error))
                        is AppResult.Loading -> SectionState.Loading
                    }
                )
                val pwd = userRepository.getStoredUserPassword(s.recordUuid)
                _uiState.value = _uiState.value.copy(
                    storedPassword = when (pwd) {
                        is AppResult.Success -> SectionState.Loaded(pwd.data)
                        is AppResult.Error -> SectionState.Failed(ErrorTranslator.resIdOf(pwd.error))
                        is AppResult.Loading -> SectionState.Loading
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val res = ErrorTranslator.resIdOf(ApiError.mapThrowable(e))
                _uiState.value = _uiState.value.copy(router = SectionState.Failed(res), storedPassword = SectionState.Failed(res))
            }
        }
    }
}

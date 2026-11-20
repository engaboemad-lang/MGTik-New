package ps.mgtik.hotspot.ui.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.AuthCredentials
import ps.mgtik.hotspot.domain.model.UserSession
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.ui.util.ErrorTranslator
import ps.mgtik.hotspot.domain.usecase.ChangePasswordUseCase
import ps.mgtik.hotspot.domain.usecase.GetSessionStateUseCase
import ps.mgtik.hotspot.domain.usecase.ReAuthenticateUseCase
import ps.mgtik.hotspot.domain.usecase.ResetPasswordUseCase
import ps.mgtik.hotspot.domain.usecase.SignInUseCase
import ps.mgtik.hotspot.domain.usecase.SignOutUseCase
import javax.inject.Inject

data class AuthUiState(
    val session: UserSession = UserSession(user = null, isAuthenticated = false),
    val isInitialAuthChecked: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /** عمليات كلمة المرور (استعادة/تغيير): تحميل ورسالة نجاح/خطأ كمعرّفات موارد. */
    val isPasswordActionLoading: Boolean = false,
    @StringRes val passwordNoticeResId: Int? = null,
    @StringRes val passwordErrorResId: Int? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val signInUseCase: SignInUseCase,
    private val signOutUseCase: SignOutUseCase,
    private val getSessionStateUseCase: GetSessionStateUseCase,
    private val resetPasswordUseCase: ResetPasswordUseCase,
    private val changePasswordUseCase: ChangePasswordUseCase,
    private val reAuthenticateUseCase: ReAuthenticateUseCase,
    private val internetChecker: ps.mgtik.hotspot.core.network.AndroidInternetChecker
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        observeAuthSession()
    }

    private fun observeAuthSession() {
        getSessionStateUseCase()
            .onEach { session ->
                _uiState.update { it.copy(session = session, isInitialAuthChecked = true) }
            }
            // فشل مراقبة الجلسة لا يترك الإقلاع معلّقاً على Splash للأبد: نعتبر الفحص منتهياً (دون جلسة)
            .catch { _uiState.update { it.copy(isInitialAuthChecked = true) } }
            .launchIn(viewModelScope)
    }

    fun signIn(credentials: AuthCredentials) {
        if (_uiState.value.isLoading) return
        // لا نُرسل طلب الدخول دون إنترنت: رسالة «لا إنترنت» أوضح من فشل غامض بعد مهلة
        if (!internetChecker.hasInternet()) {
            _uiState.update { it.copy(errorMessage = ps.mgtik.hotspot.core.result.ApiError.CODE_NETWORK_UNAVAILABLE) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            handleAuthResult(guard { signInUseCase(credentials) })
        }
    }

    private suspend fun guard(block: suspend () -> AppResult<*>): AppResult<*> = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        AppResult.Error(ps.mgtik.hotspot.core.result.ApiError.mapThrowable(e))
    }

    private fun handleAuthResult(result: AppResult<*>) {
        when (result) {
            is AppResult.Success -> {
                _uiState.update { it.copy(isLoading = false) }
            }
            is AppResult.Error -> {
                _uiState.update { it.copy(isLoading = false, errorMessage = result.error.code) }
            }
            else -> {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                signOutUseCase()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // فشل الخروج لا يُسقط التطبيق؛ الجلسة تبقى كما هي وتُعاد المحاولة يدوياً
            }
        }
    }

    /** إرسال رسالة استعادة كلمة المرور إلى البريد (من شاشة الدخول). */
    fun resetPassword(email: String) {
        if (_uiState.value.isPasswordActionLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPasswordActionLoading = true, passwordNoticeResId = null, passwordErrorResId = null) }
            val res = guard { resetPasswordUseCase(email.trim()) }
            _uiState.update {
                if (res is AppResult.Error) it.copy(isPasswordActionLoading = false, passwordErrorResId = ErrorTranslator.resIdOf(res.error))
                else it.copy(isPasswordActionLoading = false, passwordNoticeResId = R.string.password_reset_sent)
            }
        }
    }

    /** تغيير كلمة المرور من الإعدادات: إعادة مصادقة بالحالية أولاً (يشترطها Firebase) ثم التغيير. */
    fun changePassword(currentPassword: String, newPassword: String) {
        if (_uiState.value.isPasswordActionLoading) return
        val email = _uiState.value.session.user?.email.orEmpty()
        viewModelScope.launch {
            _uiState.update { it.copy(isPasswordActionLoading = true, passwordNoticeResId = null, passwordErrorResId = null) }
            val reauth = guard { reAuthenticateUseCase(AuthCredentials(email, currentPassword)) }
            val res = if (reauth is AppResult.Error) reauth else guard { changePasswordUseCase(newPassword) }
            _uiState.update {
                if (res is AppResult.Error) it.copy(isPasswordActionLoading = false, passwordErrorResId = ErrorTranslator.resIdOf(res.error))
                else it.copy(isPasswordActionLoading = false, passwordNoticeResId = R.string.password_changed)
            }
        }
    }

    fun clearPasswordMessages() {
        _uiState.update { it.copy(passwordNoticeResId = null, passwordErrorResId = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}

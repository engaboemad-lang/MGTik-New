package ps.mgtik.hotspot.domain.usecase

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.AuthCredentials
import ps.mgtik.hotspot.domain.model.AuthUser
import ps.mgtik.hotspot.domain.model.UserSession
import ps.mgtik.hotspot.domain.repository.IAuthRepository
import javax.inject.Inject

/**
 * SignInUseCase - تنفيذ عملية تسجيل الدخول الرسمي.
 */
class SignInUseCase @Inject constructor(private val authRepository: IAuthRepository) {
    suspend operator fun invoke(credentials: AuthCredentials): AppResult<AuthUser> {
        return when {
            !AuthValidator.isEmailValid(credentials.email) -> {
                AppResult.Error(ApiError.validationError(message = "Invalid email format"))
            }
            !AuthValidator.isPasswordValid(credentials.password) -> {
                AppResult.Error(ApiError.validationError(message = "Password too short"))
            }
            else -> authRepository.signInWithEmailAndPassword(credentials)
        }
    }
}

class SignOutUseCase @Inject constructor(private val authRepository: IAuthRepository) {
    suspend operator fun invoke(): AppResult<Unit> = authRepository.signOut()
}

class GetSessionStateUseCase @Inject constructor(private val authRepository: IAuthRepository) {
    operator fun invoke(): Flow<UserSession> = authRepository.sessionState
}

class ResetPasswordUseCase @Inject constructor(private val authRepository: IAuthRepository) {
    suspend operator fun invoke(email: String): AppResult<Unit> {
        if (!AuthValidator.isEmailValid(email)) {
            return AppResult.Error(ApiError.validationError("Invalid email"))
        }
        return authRepository.sendPasswordResetEmail(email)
    }
}

class ChangePasswordUseCase @Inject constructor(private val authRepository: IAuthRepository) {
    suspend operator fun invoke(newPassword: String): AppResult<Unit> {
        if (!AuthValidator.isPasswordValid(newPassword)) {
            return AppResult.Error(ApiError.validationError("Password too weak"))
        }
        return authRepository.changePassword(newPassword)
    }
}

class ReAuthenticateUseCase @Inject constructor(private val authRepository: IAuthRepository) {
    suspend operator fun invoke(credentials: AuthCredentials): AppResult<Unit> =
        authRepository.reAuthenticate(credentials)
}

class GetCurrentUserUseCase @Inject constructor(private val authRepository: IAuthRepository) {
    suspend operator fun invoke(): AuthUser? = authRepository.getCurrentUser()
}

package ps.mgtik.hotspot.data.cloud.util

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.*
import ps.mgtik.hotspot.core.result.ApiError

/**
 * FirebaseAuthErrorMapper - المسؤول عن تحويل أخطاء Firebase التقنية إلى رموز نظام MGTik (§3.7).
 * يضمن هذا المكون عدم تسرب تفاصيل أمنية حساسة وفي نفس الوقت تقديم رسائل دقيقة للمستخدم.
 */
object FirebaseAuthErrorMapper {
    private val OPERATION_NOT_ALLOWED_CODES = setOf("ERROR_OPERATION_NOT_ALLOWED", "ADMIN_RESTRICTED_OPERATION")
    /** أعطال في خدمة Firebase نفسها: لا يُطلب من المستخدم «تصحيح البيانات»، بل إعادة المحاولة لاحقاً. */
    private val SERVICE_UNAVAILABLE_CODES = setOf("ERROR_INTERNAL_ERROR", "ERROR_API_NOT_AVAILABLE", "ERROR_APP_NOT_AUTHORIZED")

    fun map(e: Exception): ApiError {
        return when (e) {
            is FirebaseAuthInvalidCredentialsException -> ApiError(
                code = ApiError.CODE_AUTH_INVALID_CREDENTIALS,
                message = "Invalid email or password"
            )
            is FirebaseAuthInvalidUserException -> ApiError(
                code = ApiError.CODE_AUTH_USER_DISABLED,
                message = "User account not found or disabled"
            )
            is FirebaseAuthRecentLoginRequiredException -> ApiError(
                code = "ERR_AUTH_REQUIRES_RECENT_LOGIN",
                message = "Please login again to perform this sensitive operation"
            )
            is FirebaseAuthUserCollisionException -> ApiError(
                code = "ERR_AUTH_USER_COLLISION",
                message = "Account already exists"
            )
            is FirebaseNetworkException -> ApiError.networkUnavailable()
            is FirebaseTooManyRequestsException -> ApiError(
                code = ApiError.CODE_AUTH_TOO_MANY_REQUESTS,
                message = "Too many attempts, try again later",
                retryable = true
            )
            // يشمل تعطيل تسجيل الدخول كضيف/المزوّد من لوحة Firebase
            is FirebaseAuthException -> if (e.errorCode in SERVICE_UNAVAILABLE_CODES) {
                ApiError(
                    code = ApiError.CODE_AUTH_SERVICE_UNAVAILABLE,
                    message = "Sign-in service is unavailable",
                    retryable = true
                )
            } else if (e.errorCode in OPERATION_NOT_ALLOWED_CODES) {
                ApiError(
                    code = ApiError.CODE_AUTH_OPERATION_NOT_ALLOWED,
                    message = "Sign-in method is not enabled"
                )
            } else {
                ApiError.unexpectedClientError(e)
            }
            else -> ApiError.unexpectedClientError(e)
        }
    }
}

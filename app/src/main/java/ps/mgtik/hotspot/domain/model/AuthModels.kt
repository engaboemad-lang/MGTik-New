package ps.mgtik.hotspot.domain.model

import kotlinx.serialization.Serializable

/**
 * AuthCredentials - بيانات الاعتماد للمصادقة السحابية (§1).
 */
@Serializable
data class AuthCredentials(
    val email: String = "",
    val password: String = ""
)

/**
 * AuthUser - بيانات المستخدم بعد تسجيل الدخول الناجح.
 */
@Serializable
data class AuthUser(
    val uid: String = "",
    val email: String = "",
    val displayName: String? = null,
    val photoUrl: String? = null
)

/**
 * UserSession - حالة جلسة المصادقة الحالية (يبنيها CloudAuthDataSource.sessionFlow).
 * lastLoginAt بصيغة yyyy-MM-dd HH:mm، وفارغ عند عدم وجود جلسة.
 */
@Serializable
data class UserSession(
    val user: AuthUser? = null,
    val isAuthenticated: Boolean = false,
    val lastLoginAt: String = ""
)

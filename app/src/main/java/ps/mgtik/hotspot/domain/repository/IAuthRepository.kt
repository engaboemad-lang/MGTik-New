package ps.mgtik.hotspot.domain.repository

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.AuthCredentials
import ps.mgtik.hotspot.domain.model.AuthUser
import ps.mgtik.hotspot.domain.model.UserSession

/**
 * IAuthRepository - عقد إدارة التوثيق وتسجيل الدخول السحابي (S1)
 * المبدأ: Firebase Auth هو مصدر الهوية الوحيد للأدمن.
 * يغطي متطلبات مراجعة تنفيذ Admin Authentication (§4 - §11)
 */
interface IAuthRepository {
    /**
     * مراقبة حالة تسجيل الدخول بشكل مستمر (§7)
     */
    val sessionState: Flow<UserSession>
    
    /**
     * تسجيل الدخول باستخدام البريد وكلمة المرور (§5)
     */
    suspend fun signInWithEmailAndPassword(credentials: AuthCredentials): AppResult<AuthUser>
    
    /**
     * تسجيل الخروج (§11)
     */
    suspend fun signOut(): AppResult<Unit>
    
    /**
     * جلب بيانات الأدمن الحالي إن وجد (§4)
     */
    suspend fun getCurrentUser(): AuthUser?
    
    /**
     * إرسال بريد إعادة تعيين كلمة المرور عبر Firebase (§10)
     */
    suspend fun sendPasswordResetEmail(email: String): AppResult<Unit>
    
    /**
     * تغيير كلمة المرور عبر Firebase (§9)
     */
    suspend fun changePassword(newPassword: String): AppResult<Unit>
    
    /**
     * إعادة المصادقة (مطلوب قبل تغيير كلمة المرور في Firebase) (§9)
     */
    suspend fun reAuthenticate(credentials: AuthCredentials): AppResult<Unit>

    /**
     * تسجيل الدخول التجريبي (إن وجد في التنفيذ الحالي)
     */
}

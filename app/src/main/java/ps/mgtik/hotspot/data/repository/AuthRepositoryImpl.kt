package ps.mgtik.hotspot.data.repository

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.data.cloud.source.CloudAuthDataSource
import ps.mgtik.hotspot.data.local.cache.LocalCacheManager
import ps.mgtik.hotspot.domain.model.AuthCredentials
import ps.mgtik.hotspot.domain.model.AuthUser
import ps.mgtik.hotspot.domain.model.UserSession
import ps.mgtik.hotspot.domain.repository.IAuthRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AuthRepositoryImpl - تطبيق مستودع التوثيق عبر Firebase Authentication
 * يدعم دورة حياة الأدمن وإدارة الحساب (§4 - §11 من المعمارية)
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val cloudDataSource: CloudAuthDataSource,
    private val localCache: LocalCacheManager,
    private val prefs: ps.mgtik.hotspot.data.local.prefs.AppPreferences,
    private val offlineScheduler: ps.mgtik.hotspot.domain.repository.IOfflineScheduler
) : IAuthRepository {

    override val sessionState: Flow<UserSession> = cloudDataSource.sessionFlow

    /**
     * دخول ناجح: إن كان الحساب مختلفاً عن آخر حساب دخل الجهاز تُمسح كاشات بيانات الحساب السابق (قابلة لإعادة البناء من السحابة)
     * كي لا يراها الحساب الجديد؛ وإن كان الحساب نفسه فتبقى كما هي. بعدها تُجدوَل مزامنة الطابور المتراكم أثناء غياب الجلسة.
     * فشل مسح الكاش لا يُخفى: الدخول يُرفض (signOut) ويُرجَع الخطأ، فلا تُكشف بيانات حساب آخر.
     */
    override suspend fun signInWithEmailAndPassword(credentials: AuthCredentials): AppResult<AuthUser> {
        val result = cloudDataSource.signInWithEmailAndPassword(credentials)
        if (result !is AppResult.Success) return result
        val uid = result.data.uid
        val previous = prefs.lastSignedInUid()
        if (previous != null && previous != uid) {
            try {
                localCache.clearUserCaches()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                cloudDataSource.signOut()
                return AppResult.Error(ApiError.unknownError("Could not clear the previous account's cached data: ${e.message}"))
            }
        }
        prefs.setLastSignedInUid(uid)
        offlineScheduler.scheduleSync()
        return result
    }

    /**
     * الخروج ينهي جلسة Firebase فقط. لا تُمسح بيانات Room (الكاش والطابور والساغات والتدقيق) ولا إعدادات الراوتر؛
     * مسح الكاش يحدث فقط عند دخول حساب مختلف (انظر [signInWithEmailAndPassword]).
     */
    override suspend fun signOut(): AppResult<Unit> = cloudDataSource.signOut()

    override suspend fun getCurrentUser(): AuthUser? =
        cloudDataSource.getCurrentUser()

    override suspend fun sendPasswordResetEmail(email: String): AppResult<Unit> =
        cloudDataSource.sendPasswordResetEmail(email)

    override suspend fun changePassword(newPassword: String): AppResult<Unit> =
        cloudDataSource.changePassword(newPassword)

    override suspend fun reAuthenticate(credentials: AuthCredentials): AppResult<Unit> =
        cloudDataSource.reAuthenticate(credentials)
}

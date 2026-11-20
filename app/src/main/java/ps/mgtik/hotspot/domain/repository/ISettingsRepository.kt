package ps.mgtik.hotspot.domain.repository

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.network.RouterConfig
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.NetworkCompanyInfo
import ps.mgtik.hotspot.domain.model.UserManConfig

/**
 * ISettingsRepository - عقد إدارة إعدادات النظام والهوية والاتصال (§6.15)
 * تم توحيد العقد لضمان فصل منطق التهيئة عن منطق القياسات الحيوية.
 */
interface ISettingsRepository {
    // --- معلومات الشركة والشبكة ---
    fun getCompanyInfo(): Flow<NetworkCompanyInfo>
    suspend fun updateCompanyInfo(info: NetworkCompanyInfo, idempotencyKey: String): AppResult<Unit>

    // --- إعدادات اتصال المايكروتك ---
    fun getRouterConfig(): Flow<RouterConfig>
    suspend fun updateRouterConfig(config: RouterConfig, idempotencyKey: String): AppResult<Unit>

    // --- إعداد User Manager (جيل/عميل v6/سجل RADIUS): وثيقة مستقلة عن اتصال الراوتر ---
    /** الإعداد المحفوظ؛ وثيقة غير موجودة = الافتراضي ([UserManConfig]). */
    fun getUserManConfig(): Flow<UserManConfig>
    /** يحفظ الإعداد ويتحقق من استرجاعه من الخادم (نجاح الحفظ = قابلية الاسترجاع بعد إعادة التشغيل). */
    suspend fun updateUserManConfig(config: UserManConfig): AppResult<Unit>

    // --- تشخيص ---
    /** زمن استجابة الخادم (ms) من قراءة حقيقية، أو الخطأ. */
    suspend fun pingCloud(): AppResult<Long>
}

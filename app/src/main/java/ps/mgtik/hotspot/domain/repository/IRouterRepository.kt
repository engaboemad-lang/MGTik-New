package ps.mgtik.hotspot.domain.repository

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.DisconnectOutcome
import ps.mgtik.hotspot.domain.model.LiveSession
import ps.mgtik.hotspot.domain.model.RouterConfig
import ps.mgtik.hotspot.domain.model.RouterHealth
import ps.mgtik.hotspot.domain.model.RouterInfo
import ps.mgtik.hotspot.domain.model.RouterInterface

/**
 * IRouterRepository - عقد مراقبة وعمليات راوتر MikroTik (Operational Contract)
 * تم فصله عن إدارة الإعدادات (ISettingsRepository) لضمان مصدر واحد للحقيقة.
 */
interface IRouterRepository {
    /**
     * تدفق حالة الراوتر اللحظية (CPU, RAM, Uptime)
     */
    fun getRouterHealth(): Flow<RouterHealth>
    
    /**
     * تدفق أحداث الاتصال (مثل إعادة الاتصال التلقائي)
     */
    fun getConnectionEvents(): Flow<RouterConnectionEvent>
    
    /**
     * اختبار الاتصال بإعدادات معينة (تستخدم عند الإعداد الأولي)
     */
    suspend fun testConnection(config: RouterConfig): AppResult<RouterInfo>

    /**
     * جلب قائمة الواجهات. يتطلب تمرير الإعدادات الحالية.
     */
    suspend fun getInterfaces(config: RouterConfig): AppResult<List<RouterInterface>>

    /**
     * إعادة تشغيل الراوتر. يتطلب تمرير الإعدادات الحالية.
     */
    suspend fun rebootRouter(config: RouterConfig): AppResult<Unit>

    /** الجلسات الحيّة الآن من User Manager (مصدر الجلسات والاستهلاك) مع باقة كل مستخدم. */
    suspend fun getLiveSessions(config: RouterConfig): AppResult<List<LiveSession>>

    /**
     * يفصل جلسة واحدة فقط على NAS (الهوتسبوت) دون المساس بحساب المستخدم، ثم يتحقق بقراءة ثانية.
     * [sessionId] معرّف جلسة User Manager؛ اختفاؤها قبل الأمر أو غياب مطابقتها على NAS = [DisconnectOutcome.ALREADY_GONE].
     */
    suspend fun disconnectSession(config: RouterConfig, sessionId: String): AppResult<DisconnectOutcome>

    /**
     * يقطع كل جلسات [username] على NAS ويتحقق من ذلك (بعد تعطيل الحساب أو أرشفته). خطأ قابل لإعادة المحاولة إن بقيت جلسة.
     * DISCONNECTED = قُطعت جلسة أو أكثر، ALREADY_GONE = لا جلسات له على NAS.
     */
    suspend fun disconnectUserSessions(config: RouterConfig, username: String): AppResult<DisconnectOutcome>
}

sealed class RouterConnectionEvent {
    object AutoReconnected : RouterConnectionEvent()
}

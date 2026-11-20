package ps.mgtik.hotspot.domain.repository

import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.SessionChanges
import ps.mgtik.hotspot.domain.model.TrackedSession
import ps.mgtik.hotspot.domain.model.UserSessionStats

/**
 * سجل الجلسات والإحصائيات التراكمية (Firestore: hotspot_sessions / hotspot_user_stats).
 * المصدر الوحيد للتاريخ؛ RouterOS لا يحفظ إلا الجلسات الجارية.
 */
interface ISessionHistoryRepository {
    /** الجلسات المفتوحة المسجَّلة (لاستئناف التتبع بعد إعادة تشغيل التطبيق دون تكرار). */
    suspend fun loadOpenSessions(): AppResult<List<TrackedSession>>

    /** تثبيت نتيجة جولة رصد. الإغلاق idempotent: إغلاق جلسة مُغلقة أصلاً لا يضيف شيئاً للتراكم. */
    suspend fun applyChanges(changes: SessionChanges): AppResult<Unit>

    /** null = لا إحصائيات مسجَّلة لهذا الحساب بعد (غير متاحة، وليست صفراً). */
    suspend fun getUserStats(username: String): AppResult<UserSessionStats?>

    /** الجلسات المُغلقة للحساب، الأحدث أولاً. تُحمَّل عند الطلب فقط. */
    suspend fun getSessionHistory(username: String, limit: Int = 30): AppResult<List<TrackedSession>>
}

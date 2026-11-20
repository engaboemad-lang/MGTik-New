package ps.mgtik.hotspot.domain.repository

import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.CloudLogoutReport
import ps.mgtik.hotspot.domain.model.ExitReport
import ps.mgtik.hotspot.domain.model.SessionImpact

/**
 * ISessionManager - إدارة جلسات التطبيق (خروج سحابي، خروج من الراوتر، إغلاق منظَّم) بمنطق واحد تشترك فيه الإعدادات والقائمة الجانبية.
 * لا عملية هنا تحذف بيانات Room أو إعدادات الراوتر المحفوظة.
 */
interface ISessionManager {
    companion object {
        /** ساغا توليد جارية: إنهاء الجلسة الآن سيُفشل عملها (تحتاج السحابة والراوتر معاً). */
        const val ERR_GENERATION_ACTIVE = "ERR_SESSION_GENERATION_ACTIVE"
        const val ERR_ROUTER_STILL_CONNECTED = "ERR_SESSION_ROUTER_STILL_CONNECTED"
    }

    /** ما سيتأثر؛ خطأ = تعذّر القراءة (لا يُعرض صفراً مضلِّلاً). */
    suspend fun inspect(): AppResult<SessionImpact>

    /** يفرّغ ما أمكن من الطابور ثم ينهي جلسة Firebase. لا يمس الراوتر ولا البيانات المحلية. يُرفض أثناء توليد جارٍ. */
    suspend fun cloudLogout(): AppResult<CloudLogoutReport>

    /** يفصل الراوتر ويُبقي إعداداته. لا يمس جلسة Firebase. يُرفض أثناء توليد جارٍ. */
    suspend fun routerLogout(): AppResult<Unit>

    /** إنهاء ما يملكه التطبيق من جلسات وموارد قبل أن تُغلق الواجهة؛ لا يحذف بيانات ولا يخرج من Firebase. */
    suspend fun prepareExit(): ExitReport

    /** استئناف خدمات التطبيق بعد إغلاق منظَّم (أو عند فتح الواجهة)؛ آمن للتكرار. */
    suspend fun resumeServices()
}

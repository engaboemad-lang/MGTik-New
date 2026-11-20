package ps.mgtik.hotspot.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.ConnectionLogEntry
import ps.mgtik.hotspot.domain.model.RouterConfig
import ps.mgtik.hotspot.domain.model.RouterInfo
import ps.mgtik.hotspot.domain.model.RouterSaveOutcome
import ps.mgtik.hotspot.domain.model.RouterStateEvent
import ps.mgtik.hotspot.domain.model.RouterStatus

/**
 * IRouterStateManager - العقد الوحيد لإدارة دورة حياة اتصال الراوتر ومراقبته (Single Source of Truth).
 *
 * يجمع المسؤوليات التنظيمية: قراءة الإعدادات وحفظها، تنفيذ الاتصال وإعادة المحاولة، مراقبة الانقطاع والاستعادة،
 * بثّ الحالة لكل الشاشات، وتنظيم العمليات التي تتطلب الاتصال (رفضها أثناء الانقطاع). الشاشات لا تنفّذ منطق اتصال خاصاً.
 */
interface IRouterStateManager {
    val status: StateFlow<RouterStatus>

    /** أحداث لحظية (مثل: عاد الاتصال). */
    val events: Flow<RouterStateEvent>

    /** يبدأ مراقبة الجلسة والإعدادات والاتصال؛ آمن للاستدعاء المتكرر. */
    fun start()

    /** إعادة محاولة الاتصال بالإعدادات الحالية (يدوية). إن تعذّرت قراءة الإعدادات من السحابة تُعاد قراءتها. */
    fun retry()

    /** تخطي الاتصال: وضع غير متصل بلا محاولات تلقائية متكررة حتى يطلب المستخدم إعادة المحاولة. */
    fun skipConnection()

    /**
     * تسجيل الخروج من الراوتر: يلغي محاولات الاتصال، يوقف المراقبة، ويغلق الاتصال وجلسته المفتوحة، فتصبح الحالة «غير متصل» مع
     * [RouterStatus.loggedOut]. لا يحذف الإعدادات المحفوظة ولا يمسّ جلسة Firebase. لا إعادة اتصال تلقائية بعده؛ [retry] أو
     * [saveAndConnect] فقط يعيدانه. العمليات التي تحتاج الراوتر تُرفض (راوتر غير متصل) فتبقى أوامرها في الطابور.
     */
    suspend fun logout()

    /**
     * إنهاء منظَّم عند إغلاق التطبيق: يوقف المراقبة ومحاولات الاتصال ويغلق الاتصال ويصفّر الحالة المعروضة، دون حذف أي إعداد.
     * يمكن استئنافه بـ [start] (يعيد قراءة الإعدادات والاتصال كما عند الإقلاع).
     */
    suspend fun shutdown()

    /** فحص إعداد دون حفظه ودون المساس بالإعداد المحفوظ. */
    suspend fun testConfig(config: RouterConfig): AppResult<RouterInfo>

    /** يحفظ الإعداد (ويتحقق من استرجاعه) ثم يختبر الاتصال به ويحدّث الحالة المركزية. */
    suspend fun saveAndConnect(config: RouterConfig): RouterSaveOutcome

    /** سجل الاتصال التشخيصي (آخر 50 حدثاً)، الأقدم أولاً. */
    val connectionLog: StateFlow<List<ConnectionLogEntry>>

    /** نص تصدير السجل (وقت | حالة | رمز السبب | مدة | نتيجة إعادة الاتصال) دون أي بيانات حساسة. */
    fun exportConnectionLog(): String

    /**
     * التطبيق في المقدمة/الخلفية (ProcessLifecycleOwner). المقدمة: نبض كل 10 ثوانٍ ونبضة فورية عند العودة.
     * الخلفية: تتوقف النبضات ومحاولات إعادة الاتصال (لا عمليات غير ضرورية في الخلفية/Doze) حتى العودة.
     */
    fun setAppForeground(foreground: Boolean)
}

package ps.mgtik.hotspot.core.network

/**
 * NetworkConfig - الثوابت المركزية لإعدادات الشبكة والاتصال (§14)
 * تضمن توحيد السلوك عبر كافة مديولات النظام.
 */
object NetworkConfig {
    // مدة صلاحية اتصال الراوتر (TTL) - 60 ثانية (§14)
    const val ROUTER_CONNECTION_TTL_MS = 60_000L
    
    // مهلة الاستجابة الموحدة - 8 ثوانٍ (§3)
    const val NETWORK_TIMEOUT_MS = 8_000L
    
    // الحد الأقصى لمحاولات إعادة المزامنة التلقائية
    const val MAX_SYNC_RETRIES = 3
}

/**
 * نوع عملية الراوتر ← مهلتها. المهلة الموحدة (8ث) لا تناسب الأنواع كلها:
 *  - PROBE: النبض والفحص، يجب أن يفشل بسرعة (5ث قراءة).
 *  - STANDARD: العمليات العادية (8ث قراءة كما كان).
 *  - BULK: قراءات ضخمة (قوائم المستخدمين/الجلسات/الباقات): 30ث قراءة.
 * [totalTimeoutMs] سقف كلي للعملية كلها (اتصال + عدة طلبات) كي لا تستمر بلا حدود.
 */
enum class RouterOpKind(val readTimeoutMs: Long, val totalTimeoutMs: Long) {
    PROBE(5_000L, 20_000L),
    STANDARD(NetworkConfig.NETWORK_TIMEOUT_MS, 30_000L),
    BULK(30_000L, 90_000L)
}

/** ينقل نوع العملية إلى طبقة النقل (REST/API الثنائي) عبر سياق الكوروتين فتطبّق مهلة القراءة المناسبة. */
internal class RouterOpTimeout(val kind: RouterOpKind) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<RouterOpTimeout>
}

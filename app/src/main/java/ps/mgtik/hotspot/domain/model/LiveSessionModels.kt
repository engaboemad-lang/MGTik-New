package ps.mgtik.hotspot.domain.model

/**
 * LiveSession - جلسة نشطة الآن كما يقرؤها التطبيق من User Manager على الراوتر (جلسات المحاسبة بحالة حيّة) عبر RouterConnectionManager.
 * مصدرها الراوتر وحده؛ لا تُستعمل للبيانات المالية أو دورة الاشتراك.
 *
 * اتجاه العدّادات من منظور المستخدم: upload = ما أرسله العميل، download = ما استقبله.
 * [profile] هو الباقة المُسندة للمستخدم في User Manager ويبقى null إن تعذّرت قراءتها (لا تخمين).
 * [uptimeSeconds] = ساعة الراوتر − وقت بداية الجلسة إن أمكن، وإلا uptime المبلَّغ؛ وعدّادات User Manager تتحدّث مع حزم المحاسبة المرحلية فقط.
 */
data class LiveSession(
    /** معرّف جلسة User Manager (.id)؛ يُحوَّل عند الفصل إلى مطابقة (مستخدم + MAC/عنوان) على NAS ولا يُستعمل مباشرة معه. */
    val sessionId: String,
    val username: String,
    val address: String,
    val macAddress: String,
    val profile: String?,
    val uptimeSeconds: Long?,
    val uploadBytes: Long,
    val downloadBytes: Long,
    /** نتيجة التحقق من وجود الجلسة على NAS (قراءة فقط): CONFIRMED مؤكدة، UNVERIFIED تعذّرت قراءة NAS (غير مؤكدة لا مرفوضة). */
    val confirmation: LiveConfirmation = LiveConfirmation.UNVERIFIED,
    /** NAS/الراوتر الذي سجّل الجلسة كما أبلغ User Manager؛ فارغ = غير متوفر. */
    val nasAddress: String = "",
    /** وقت بدء الجلسة كما أعاده الراوتر (بتوقيته)؛ null = غير متوفر. */
    val startedRaw: String? = null
) {
    val totalBytes: Long get() = uploadBytes + downloadBytes
}

/** نتيجة فصل جلسة: [AlreadyGone] = لم تعد موجودة على الراوتر (انتهت قبل الأمر) وهي حالة متحققة لا خطأ. */
enum class DisconnectOutcome { DISCONNECTED, ALREADY_GONE }

enum class LiveConfirmation { CONFIRMED, UNVERIFIED }

/**
 * لقطة الجلسات الحية: [sessions] متصلون فعلاً (مؤكدون على NAS أو غير مُتحقَّق منهم لتعذّر قراءته)،
 * و[stale] جلسات يراها User Manager نشطة بينما NAS (قُرئ بنجاح) لا يعرفها: عالقة لا تُعرض كمتصلة ولا تُحذف سجلاتها.
 */
data class LiveSnapshot(val sessions: List<LiveSession>, val stale: List<LiveSession> = emptyList())

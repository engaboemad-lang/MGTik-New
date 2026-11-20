package ps.mgtik.hotspot.domain.model

/**
 * جلسة مُتتبَّعة (تُحفظ في Firestore: hotspot_sessions). مصدر القيم الأولي RouterOS؛ التاريخ والتراكم مصدرهما هذا السجل.
 *
 * - [startedAtMs] تقدير = وقت أول رصد − uptime (RouterOS لا يعطي وقت الدخول). [startEstimated] = true حين لم تتوفر uptime.
 * - [uploadBytes]/[downloadBytes] قيم مُراكَمة تتحمّل تصفير عدّادات RouterOS داخل الجلسة (لا احتساب مزدوج).
 * - [lastRawUpload]/[lastRawDownload]/[lastUptimeSeconds] آخر قراءة خام لحساب الفرق وكشف الجلسة الجديدة.
 * - [endedAtMs] null = الجلسة مفتوحة. [endEstimated] = true حين غاب الرصد عن لحظة الانقطاع فاعتُمد آخر وقت رُصدت فيه.
 * - [lastPersistedMs] محلي فقط (لتخفيف الكتابة)؛ لا يُحفظ.
 */
data class TrackedSession(
    val sessionKey: String,
    val username: String,
    val macAddress: String,
    val address: String,
    /** حقل تاريخي: يُقرأ من وثائق قديمة ويُكتب فارغاً للجلسات الجديدة (User Manager لا يحمل سيرفراً). */
    val server: String,
    val profile: String?,
    val startedAtMs: Long,
    val startEstimated: Boolean,
    val lastSeenAtMs: Long,
    val lastUptimeSeconds: Long?,
    val lastRawUpload: Long,
    val lastRawDownload: Long,
    val uploadBytes: Long,
    val downloadBytes: Long,
    val endedAtMs: Long? = null,
    val endReason: SessionEndReason? = null,
    val endEstimated: Boolean = false,
    val lastPersistedMs: Long = 0L
) {
    val totalBytes: Long get() = uploadBytes + downloadBytes
    val isOpen: Boolean get() = endedAtMs == null

    /** مدة الجلسة بالثواني: uptime الراوتر إن عُرفت (أدق)، وإلا الفرق بين البداية والنهاية/آخر رصد. */
    val durationSeconds: Long
        get() {
            val end = endedAtMs ?: lastSeenAtMs
            val byClock = ((end - startedAtMs) / 1000).coerceAtLeast(0)
            return lastUptimeSeconds?.coerceAtLeast(0) ?: byClock
        }
}

/** سبب انتهاء الجلسة كما نستطيع استنتاجه؛ RouterOS لا يعطي سبباً صريحاً لجلسة انتهت أثناء غياب التطبيق. */
enum class SessionEndReason {
    /** اختفت من active بينما الراوتر يردّ (فصل/خروج/انتهاء مهلة/تعطيل). السبب الدقيق غير معروف. */
    DISAPPEARED,
    /** ظهرت جلسة جديدة بالمفتاح نفسه بـ uptime أقل أو بداية مختلفة (إعادة اتصال أو إعادة تشغيل الراوتر). */
    RECONNECTED,
    /** فُصلت من التطبيق (إجراء Disconnect). */
    DISCONNECTED_BY_ADMIN,
    /** فُصلت بسبب تعطيل الحساب من التطبيق. */
    ACCOUNT_DISABLED
}

/**
 * إحصائيات تراكمية للحساب = مجموع الجلسات المُغلقة المسجَّلة فقط (لا تشمل الجلسة الجارية: تُضاف للعرض عند الحاجة).
 * [trackingSinceMs] = أول وقت بدأ فيه التتبع؛ ما قبله غير معروف ولا يُعرض صفراً.
 */
data class UserSessionStats(
    val username: String,
    val totalUptimeSeconds: Long,
    val totalUploadBytes: Long,
    val totalDownloadBytes: Long,
    val closedSessionCount: Long,
    val lastSeenAtMs: Long?,
    val lastSessionDurationSeconds: Long?,
    val trackingSinceMs: Long?
) {
    val totalBytes: Long get() = totalUploadBytes + totalDownloadBytes
}

/** ما يجب تثبيته في السحابة بعد جولة رصد واحدة. */
data class SessionChanges(
    val upserts: List<TrackedSession> = emptyList(),
    val closes: List<TrackedSession> = emptyList()
) {
    val isEmpty: Boolean get() = upserts.isEmpty() && closes.isEmpty()
}

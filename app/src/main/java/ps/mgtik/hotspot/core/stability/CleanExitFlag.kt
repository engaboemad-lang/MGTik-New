package ps.mgtik.hotspot.core.stability

/** تخزين أعلام منطقية بسيط (قابل للاستبدال في الاختبارات). */
interface FlagStore {
    fun get(key: String): Boolean
    /** كتابة متزامنة: العلم يجب أن يصل للقرص قبل أن تُقتل العملية. */
    fun set(key: String, value: Boolean)
}

/**
 * علامة «إغلاق غير سليم» (المهمة 5.7):
 *  - تُكتب عند الإقلاع ([onLaunch])،
 *  - تُمسح عند الإغلاق المنظم عبر prepareExit ([onCleanExit])،
 *  - إن وُجدت عند الإقلاع التالي ⇒ الجلسة السابقة انتهت بلا إغلاق منظم، فيُسجَّل حدث (يُرفع إلى AuditRepository
 *    حين تتوفر جلسة مصادَق عليها، ويبقى معلّقاً حتى يُسجَّل فعلاً).
 */
class CleanExitFlag(private val store: FlagStore) {

    /** يعيد true إن كان الإقلاع السابق غير سليم. يكتب علامة الجلسة المفتوحة دائماً. */
    fun onLaunch(): Boolean {
        val wasUnclean = store.get(KEY_SESSION_OPEN)
        if (wasUnclean) store.set(KEY_UNCLEAN_PENDING, true)
        store.set(KEY_SESSION_OPEN, true)
        return wasUnclean
    }

    /** إغلاق منظم (prepareExit). */
    fun onCleanExit() = store.set(KEY_SESSION_OPEN, false)

    /** استُؤنفت الخدمات بعد prepareExit (resumeServices): الجلسة مفتوحة من جديد. */
    fun onResumed() = store.set(KEY_SESSION_OPEN, true)

    /** هل ينتظر حدث «إغلاق غير سليم» التسجيل في التدقيق؟ */
    fun hasPendingReport(): Boolean = store.get(KEY_UNCLEAN_PENDING)

    /** يُستدعى بعد نجاح التسجيل فقط (فشله يُبقي الحدث معلّقاً لمحاولة لاحقة). */
    fun markReported() = store.set(KEY_UNCLEAN_PENDING, false)

    private companion object {
        const val KEY_SESSION_OPEN = "session_open"
        const val KEY_UNCLEAN_PENDING = "unclean_pending"
    }
}

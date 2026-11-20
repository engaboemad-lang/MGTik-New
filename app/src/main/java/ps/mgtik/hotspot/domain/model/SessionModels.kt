package ps.mgtik.hotspot.domain.model

/** ما يعمل أو ينتظر الآن ويتأثر بإنهاء جلسة أو إغلاق التطبيق (يُعرض في نافذة التأكيد). */
data class SessionImpact(
    /** أوامر في طابور العمليات لم تُنفَّذ بعد؛ محفوظة في Room وتُستأنف بعد الدخول/إعادة الاتصال. */
    val pendingCommands: Int,
    /** ساغات توليد جارية أو معلّقة (QUEUED/RUNNING/RETRYING). */
    val activeGenerationRuns: Int
)

/** نتيجة تسجيل الخروج السحابي: ما بقي محفوظاً في الطابور بعد محاولة تفريغه. */
data class CloudLogoutReport(val pendingCommandsKept: Int)

/**
 * نتيجة الإغلاق المنظَّم. [stillRunning] ما يبقى بإدارة Android/السحابة ولا يملك التطبيق إيقافه بأمان
 * (توليد جارٍ، أوامر طابور مجدولة في WorkManager)؛ [failures] خطوات تعذّر إنهاؤها وسببها.
 */
data class ExitReport(
    val stillRunning: SessionImpact?,
    val failures: List<String>
)

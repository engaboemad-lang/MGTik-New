package ps.mgtik.hotspot.domain.repository

/**
 * IGenerationScheduler - تشغيل ساغا التوليد في الخلفية (WorkManager + Foreground).
 * عمل فريد لكل تنفيذ: الجدولة المتكررة لنفس المعرّف لا تُنشئ عاملين متوازيين.
 */
interface IGenerationScheduler {
    fun schedule(executionId: String)
}

package ps.mgtik.hotspot.domain.repository

/**
 * IOfflineScheduler - واجهة لجدولة مهام المزامنة في الخلفية (§5.3)
 */
interface IOfflineScheduler {
    /**
     * إطلاق عملية المزامنة الفورية أو المجدولة عند توفر الاتصال.
     */
    fun scheduleSync()

    /** شبكة أمان دورية (كل 15 دقيقة عند توفر الاتصال) لتنفيذ ما تعذّر أو تراكم؛ تُستدعى عند إقلاع التطبيق. */
    fun schedulePeriodicSync()
    
    /**
     * إلغاء كافة المهام المجدولة.
     */
    fun cancelAll()
}

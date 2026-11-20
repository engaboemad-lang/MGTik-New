package ps.mgtik.hotspot.domain.repository

/**
 * INotificationPublisher - منتج الإشعارات: نقطة واحدة تُنشئ إشعارات مركز الإشعارات (S20) عند وقوع أحداث فعلية.
 * فشل الكتابة لا يجوز أن يُفشل العملية الأصلية، لذا الدوال لا ترمي ولا تُرجع نتيجة.
 * المعرّفات حتمية (مبنية على معرّف الحدث) فتكرار الاستدعاء (مثل retry للـ Worker) لا يُنشئ إشعاراً مكرراً.
 */
interface INotificationPublisher {
    /** دفعة مسجَّلة بنجاح لفاتورة. */
    suspend fun paymentRecorded(paymentId: String, invoiceId: String, amountMinor: Long)

    /** انتهاء توليد الكروت؛ [partial] = اكتمل مع فشل بعض الكروت. */
    suspend fun generationCompleted(executionId: String, partial: Boolean, orderId: String? = null)

    /** فشل نهائي لتوليد الكروت. */
    suspend fun generationFailed(executionId: String, reason: String, orderId: String? = null)

    /** فشل عملية معلّقة في الطابور نهائياً (فشل منطقي أو استنفاد المحاولات)؛ [reason] رمز الخطأ. */
    suspend fun queueCommandFailed(commandId: String, reason: String)
}

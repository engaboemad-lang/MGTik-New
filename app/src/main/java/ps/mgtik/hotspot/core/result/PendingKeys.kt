package ps.mgtik.hotspot.core.result

/**
 * مفاتيح Idempotency للعمليات المالية الجارية: المفتاح يُنشأ مرة لكل «عملية منطقية» (بصمتها: النوع + الهدف + المبلغ)،
 * ويُعاد استخدامه عند إعادة المحاولة بعد فشل لا يُعرف مصيره (مهلة/انقطاع بعد وصول الطلب فعلاً)،
 * ويُمسح عند النجاح فقط. كان كل ضغطة جديدة تولّد مفتاحاً جديداً فيُسجَّل التحصيل/الإيداع مرتين إن نجحت المحاولة الأولى
 * وضاع ردّها.
 */
class PendingKeys {
    private val keys = HashMap<String, String>()

    @Synchronized
    fun keyFor(signature: String): String = keys.getOrPut(signature) { IdempotencyKeyProvider.generateKey() }

    @Synchronized
    fun clear(signature: String) {
        keys.remove(signature)
    }
}

package ps.mgtik.hotspot.data.router.lifecycle

import ps.mgtik.hotspot.core.result.ApiError

/**
 * تصنيف عمليات الراوتر (4.4): هل تُعاد المحاولة تلقائياً بعد خطأ قابل للإعادة؟
 * القراءات والعمليات المتحقَّق منها قبل الإعادة (إنشاء/تفعيل/حذف/تعطيل) قابلة للإعادة؛ إعادة التشغيل والتهيئة والصيانة لا.
 * يُستعمل حقل [ApiError.retryable] الموجود: السياسة تُضيّق فقط ولا توسّع (لا تجعل خطأً غير قابل قابلاً).
 */
enum class RouterOperation(val retryable: Boolean, val verifyBeforeRetry: Boolean) {
    READ(retryable = true, verifyBeforeRetry = false),
    CREATE_USER(retryable = true, verifyBeforeRetry = true),
    ACTIVATE_PACKAGE(retryable = true, verifyBeforeRetry = true),
    DELETE_USER(retryable = true, verifyBeforeRetry = true),
    DISABLE_USER(retryable = true, verifyBeforeRetry = true),
    REBOOT_ROUTER(retryable = false, verifyBeforeRetry = false),
    PROVISION(retryable = false, verifyBeforeRetry = false),
    MAINTENANCE(retryable = false, verifyBeforeRetry = false)
}

object RouterOperationPolicy {
    fun isRetryable(op: RouterOperation, error: ApiError): Boolean = op.retryable && error.retryable

    /** يعيد الخطأ نفسه بعلم retryable مطابق لسياسة العملية. */
    fun classify(op: RouterOperation, error: ApiError): ApiError =
        if (error.retryable && !op.retryable) error.copy(retryable = false) else error
}

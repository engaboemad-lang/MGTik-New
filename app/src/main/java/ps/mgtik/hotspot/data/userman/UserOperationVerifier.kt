package ps.mgtik.hotspot.data.userman

import ps.mgtik.hotspot.core.result.ApiError

/**
 * المهمة 4.4 — «تحقق من نتيجة العملية قبل إعادة تنفيذها».
 * منطق نقي يقرّر هل مستخدم موجود على الراوتر هو نتيجة محاولتنا نحن (إنشاء انقطع ردّه)، وهل الخطأ «غامض الإرسال».
 */
internal object UserOperationVerifier {

    /**
     * أخطاء قد تكون العملية فيها وصلت الراوتر ونُفِّذت وضاع الرد (انقطاع/مهلة أثناء الطلب).
     * ERR_ROUTER_OFFLINE ليست منها: البوابة رفضت العملية قبل إرسالها فإعادتها آمنة دون تحقق.
     */
    fun isAmbiguousSendError(code: String?): Boolean =
        code == ApiError.CODE_NETWORK_UNAVAILABLE || code == ApiError.CODE_TIMEOUT

    /**
     * هل [existing] هو المستخدم الذي حاولنا إنشاءه؟ الاسم والتعليق (وسم الدفعة) والتعطيل والباقة (إن طُلبت) يجب أن تتطابق.
     * [requirePassword]: في مسار «الاسم موجود» دون خطأ اتصال نشترط أيضاً تطابق كلمة المرور المقروءة، كي لا يُحسب تصادم اسم
     * عشوائي داخل الدفعة نفسها مستخدماً واحداً مُنشأً مرتين.
     */
    fun isSameUser(
        existing: UmUser?, comment: String, password: String, packageName: String?, disabled: Boolean,
        requirePassword: Boolean
    ): Boolean {
        if (existing == null) return false
        if (existing.comment.orEmpty().trim() != comment.trim()) return false
        if (existing.disabled != disabled) return false
        if (packageName != null && existing.packageName != packageName) return false
        if (requirePassword && (existing.password.isNullOrBlank() || existing.password != password)) return false
        return true
    }
}

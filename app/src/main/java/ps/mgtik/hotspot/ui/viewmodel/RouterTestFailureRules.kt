package ps.mgtik.hotspot.ui.viewmodel

import ps.mgtik.hotspot.core.result.ApiError

/**
 * أي خطوة اختبار فشلت لكل سبب (وصول ← مصادقة ← قراءة):
 * مضيف غير قابل للوصول/مهلة/فشل اتصال = الوصول؛ منفذ مغلق أو خدمة REST غير متاحة أو مصادقة = المصادقة؛ صلاحيات غير كافية = القراءة.
 */
internal object RouterTestFailureRules {
    fun statuses(code: String?): List<TestStepStatus> = when (code) {
        ApiError.CODE_ROUTER_PORT_CLOSED,
        ApiError.CODE_ROUTER_AUTH_FAILED,
        ApiError.CODE_ROUTER_REST_UNAVAILABLE ->
            listOf(TestStepStatus.SUCCESS, TestStepStatus.ERROR, TestStepStatus.IDLE)
        ApiError.CODE_ROUTER_PERMISSION_DENIED ->
            listOf(TestStepStatus.SUCCESS, TestStepStatus.SUCCESS, TestStepStatus.ERROR)
        else -> listOf(TestStepStatus.ERROR, TestStepStatus.IDLE, TestStepStatus.IDLE)
    }
}

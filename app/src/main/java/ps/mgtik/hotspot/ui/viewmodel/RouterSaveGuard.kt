package ps.mgtik.hotspot.ui.viewmodel

import ps.mgtik.hotspot.core.network.RouterConfig
import ps.mgtik.hotspot.core.network.sameEndpoint

/**
 * المهمة 2: هل يحتاج الحفظ إلى تأكيد؟ نعم إن لم يوجد اختبار ناجح، أو تغيّر أي إعداد مؤثر في الاتصال
 * (العنوان/المنافذ/المستخدم/كلمة المرور/SSL/نوع النقل) بعد آخر اختبار ناجح. دالة نقية.
 */
internal object RouterSaveGuard {
    fun needsConfirmation(lastTested: RouterConfig?, current: RouterConfig): Boolean =
        lastTested == null || !current.sameEndpoint(lastTested)
}

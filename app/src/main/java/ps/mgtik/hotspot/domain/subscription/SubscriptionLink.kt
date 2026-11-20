package ps.mgtik.hotspot.domain.subscription

import ps.mgtik.hotspot.domain.model.LocalUserRecord
import ps.mgtik.hotspot.domain.model.Subscription
import ps.mgtik.hotspot.domain.model.SubscriptionStatus
import ps.mgtik.hotspot.domain.model.SystemClass

/** نتيجة ربط سجل المستخدم باشتراكه. */
sealed class SubscriptionLink {
    /** مستخدم عادي: لا اشتراك له. */
    data object NotSubscriber : SubscriptionLink()
    /** مشترك لا يوجد اشتراك صالح مرتبط به (تُعرض حالة واضحة بدل شاشة فارغة). */
    data object Missing : SubscriptionLink()
    data class Found(val subscription: Subscription) : SubscriptionLink()
}

/**
 * يحدد اشتراك المشترك الشهري دون افتراض أن معرّف المستخدم = معرّف الاشتراك:
 *  1. تطابق المعرّفين **مع** تطابق اسم دخول الراوتر (الضمان البرمجي: معاملة الإنشاء تكتبهما معاً بالمعرّف نفسه).
 *  2. وإلا فبحث باسم دخول الراوتر (فريد عند الإنشاء)، ويُفضَّل الأحدث غير الملغى عند تعدد السجلات.
 * تطابق المعرّف وحده دون تطابق اسم الدخول لا يُعتمد (علاقة غير سليمة).
 */
object SubscriptionLinkResolver {
    fun resolve(user: LocalUserRecord, subscriptions: List<Subscription>): SubscriptionLink {
        if (user.systemClass != SystemClass.SUBSCRIPTION) return SubscriptionLink.NotSubscriber
        subscriptions.firstOrNull { it.id == user.recordUuid && it.customerUsername == user.routerUsername }
            ?.let { return SubscriptionLink.Found(it) }
        val byUsername = subscriptions.filter { it.id.isNotBlank() && it.customerUsername == user.routerUsername }
        val best = byUsername.filter { it.status != SubscriptionStatus.CANCELLED }.maxByOrNull { it.createdAt }
            ?: byUsername.maxByOrNull { it.createdAt }
        return if (best != null) SubscriptionLink.Found(best) else SubscriptionLink.Missing
    }
}

package ps.mgtik.hotspot.domain.model

import kotlinx.serialization.Serializable

/** وحدة مدة الاشتراك: DAYS مدة متصلة (24 ساعة × العدد)، MONTHS أشهر تقويمية من بداية الصلاحية. */
@Serializable
enum class PeriodUnit { DAYS, MONTHS }

/** حالة تنفيذ آخر أمر على الراوتر. */
@Serializable
enum class SubscriptionExecution { SYNCED, PENDING, FAILED }

/** الأوامر التي تغيّر حساب المستخدم على الراوتر. */
@Serializable
enum class SubscriptionOp { ACTIVATE, RENEW, EXPIRE, SUSPEND, RESUME, CANCEL }

@Serializable
enum class SubscriptionPayStatus { UNPAID, PARTIAL, PAID }

/** فاتورة اشتراك: مستقلة تماماً عن فواتير الطلبات (collection منفصلة subscription_invoices). */
@Serializable
data class SubscriptionInvoice(
    val id: String = "",
    val subscriptionId: String = "",
    val periodNumber: Int = 1,
    val periodStart: String = "",
    val periodEnd: String = "",
    val priceMinor: Long = 0L,
    val discountMinor: Long = 0L,
    val totalMinor: Long = 0L,
    val paidMinor: Long = 0L,
    val dueDate: String = "",
    val createdAt: String = ""
) {
    val balanceMinor: Long get() = totalMinor - paidMinor
    val payStatus: SubscriptionPayStatus
        get() = when {
            totalMinor <= 0L -> SubscriptionPayStatus.PAID
            paidMinor <= 0L -> SubscriptionPayStatus.UNPAID
            paidMinor < totalMinor -> SubscriptionPayStatus.PARTIAL
            else -> SubscriptionPayStatus.PAID
        }
}

@Serializable
data class SubscriptionPayment(
    val id: String = "",
    val subscriptionId: String = "",
    val invoiceId: String = "",
    val amountMinor: Long = 0L,
    val method: String = "cash",
    val isVoid: Boolean = false,
    val createdAt: String = ""
)

enum class SubscriptionOpStatus { PENDING, DONE, FAILED }

/** سجل أمر تنفيذ على الراوتر: يحفظ المحاولات والنتيجة، ومعرّفه حتمي فلا يتكرر لنفس الفترة. */
@Serializable
data class SubscriptionOperation(
    val id: String = "",
    val subscriptionId: String = "",
    val op: SubscriptionOp = SubscriptionOp.ACTIVATE,
    val periodNumber: Int = 0,
    val status: String = "PENDING",
    val attempts: Int = 0,
    val lastError: String = "",
    val requestedAt: String = "",
    val executedAt: String = ""
)

/** مدخلات إنشاء اشتراك جديد (بعد اختيار الباقة؛ السعر والقيود لقطة منها). */
data class NewSubscription(
    val customerName: String,
    val customerPhone: String,
    val username: String,
    val password: String,
    val profileId: String,
    val profileName: String,
    val routerProfileName: String,
    val priceMinor: Long,
    val discountMinor: Long,
    val limitUptimeSeconds: Long?,
    val limitTransferBytes: Long?,
    val periodUnit: PeriodUnit,
    val periodCount: Int,
    /** null = يبدأ الآن؛ مستقبلي = اشتراك مجدول. */
    val startsAtIso: String?,
    val autoRenewal: Boolean,
    val notes: String,
    /** مالك الاشتراك (مرجع مالك فعلي). فارغ = بلا مالك (مسار لوحة الفوترة الحالي)؛ مسار S16 يمرّر مرجع «admin». */
    val ownerId: String = "",
    val ownerName: String = "",
    /** لقطة التسعير المركزي (تُملأ في CreateSubscriptionUseCase من PricingPolicy.resolveFor بسياق RETAIL). */
    val priceCurrency: String = "",
    val priceSource: String = "",
    val pricingType: String = "",
    val priceCategoryId: String = "",
    val priceRuleId: String = ""
)

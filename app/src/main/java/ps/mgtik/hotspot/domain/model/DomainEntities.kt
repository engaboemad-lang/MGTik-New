package ps.mgtik.hotspot.domain.model

import kotlinx.serialization.Serializable

// --- 5. معلومات الراوتر والاتصال ---
@Serializable
data class RouterInfo(
    val id: String = "",
    val name: String = "غير معرف",
    val ip: String = "0.0.0.0",
    val port: Int = 8728,
    val isConnected: Boolean = false,
    val uptimeFormatted: String = "00:00:00",
    val osMajor: String = "",
    val cpuUsagePercent: Int = 0,
    val memoryUsagePercent: Int = 0,
    val totalRamMb: Long = 0L,
    val lastCheckedAt: String = ""
)

@Serializable
data class RouterHealth(
    val isOnline: Boolean = false,
    val cpuLoadPercent: Int = 0,
    val freeMemoryMb: Long = 0L,
    val uptimeString: String = "",
    val rxSpeedKbps: Long = 0L,
    val txSpeedKbps: Long = 0L,
    val lastCheckedAt: String = "",
    /** الحالة انتقالية (اتصال/إعادة اتصال جارية): [isOnline] ليست نتيجة نهائية بعد. */
    val isChecking: Boolean = false
)

// --- 4.18 حسابات الملاك والموزعين ---

@Serializable
enum class OwnerStatus {
    ACTIVE, SUSPENDED, ARCHIVED
}

@Serializable
data class OwnerAccount(
    val ownerId: String = "",
    val name: String = "",
    val phone: String = "",
    val openingBalanceMinor: Long = 0L,
    val openingBalanceSetAt: String = "",
    val availableCreditMinor: Long = 0L,
    val outstandingInvoicesCount: Int = 0,
    val status: OwnerStatus = OwnerStatus.ACTIVE,
    val createdAt: String = ""
)

@Serializable
data class OwnerWorkspace(
    val ownerId: String,
    val account: OwnerAccount,
    val invoices: List<Invoice> = emptyList(),
    val payments: List<Payment> = emptyList(),
    val recentLedger: List<OwnerLedgerEntry> = emptyList()
)

@Serializable
enum class OwnerLedgerType {
    ADVANCE,
    REFUND_FROM_CREDIT,
    INVOICE_CHARGE,
    /** رصيد افتتاحي (يُضاف للرصيد). */
    OPENING_BALANCE,
    /** إعادة مبلغ للرصيد عند إلغاء/استرداد دفعة «credit». */
    CHARGE_REVERSAL
}

@Serializable
data class OwnerLedgerEntry(
    val ledgerEntryId: String,
    val ownerId: String,
    val type: OwnerLedgerType,
    val amountMinor: Long,
    val relatedInvoiceId: String?,
    val createdAt: String,
    /** ملاحظة القيد (مثلاً سبب الإيداع)؛ فارغة إن لم تُدخل. */
    val note: String = ""
)

@Serializable
enum class InvoiceStatus {
    DRAFT, PAID, DUE, PARTIALLY_PAID, CANCELED;

    /** يقبل تسجيل دفعة جديدة (الملغاة والمسدَّدة بالكامل لا تقبل). */
    val acceptsPayment: Boolean get() = this == DRAFT || this == DUE || this == PARTIALLY_PAID

    /** يمكن إلغاؤها دون force: لا دفعات عليها. المدفوعة جزئياً تُرفض في السحابة (cancelInvoice) ما لم يكن force. */
    val isCancellable: Boolean get() = this == DRAFT || this == DUE
}

@Serializable
data class Invoice(
    val id: String = "",
    val invoiceNumber: String = "",
    val orderId: String = "",
    val ownerId: String = "",
    val status: InvoiceStatus = InvoiceStatus.DRAFT,
    val subtotalMinor: Long = 0,
    val discountType: String? = null,
    val discountValueMinor: Long? = null,
    /** الإجمالي المستحق بعد الخصم (الحقل الوحيد المخزَّن في السحابة totalMinor وفي Room total_minor). */
    val totalMinor: Long = 0,
    val amountPaidMinor: Long = 0,
    val amountDueMinor: Long = 0,
    val dueDate: String? = null,
    val notes: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val businessId: String = "",
    val ownerNameSnapshot: String = "",
    val currency: String = "₪",
    val paymentMethod: String = "",
    val isPaid: Boolean = false,
    val issueDate: String = ""
)

@Serializable
/** القيم تطابق ما يكتبه CloudBillingDataSource فعلاً: active / canceled / void / refunded. */
enum class PaymentStatus {
    ACTIVE, CANCELED, VOID, REFUNDED
}

@Serializable
data class Payment(
    val id: String = "",
    val invoiceId: String = "",
    val amountMinor: Long = 0L,
    val method: String = "cash",
    val reference: String? = null,
    val notes: String? = null,
    val status: PaymentStatus = PaymentStatus.ACTIVE,
    val createdAt: String = "",
    val ownerId: String = "",
    /** ما استُرد فعلاً من هذه الدفعة (status = REFUNDED)؛ المحصَّل الصافي = amountMinor - refundedMinor. */
    val refundedMinor: Long = 0L
)

// --- إعدادات النظام ومعلومات الشركة (§24) ---

@Serializable
data class NetworkCompanyInfo(
    val companyName: String = "",
    val networkName: String = "",
    val logoUrl: String = "",
    val address: String = "",
    val phone: String = "",
    val whatsapp: String = "",
    val email: String = "",
    val website: String = "",
    val loginUrl: String = "",
    val footerNotes: String = "",
    val currencySymbol: String = "₪"
)

// --- 4.15 طلبات التوليد والطلبات (Orders & Batches) ---

@Serializable
enum class OrderStatus {
    DRAFT, CLOSED, CANCELLED, REOPENED
}

@Serializable
enum class BatchStatus {
    PENDING, COMPLETED, FAILED
}

@Serializable
data class Order(
    val id: String = "",
    val ownerId: String = "",
    val status: OrderStatus = OrderStatus.DRAFT,
    val linkedBatchIds: List<String> = emptyList(),
    val invoiceId: String? = null,
    val createdAt: String = "",
    val closedAt: String? = null,
    val totalCards: Int = 0,
    val totalAmountMinor: Long = 0L,
    val createdBy: String = "",
    val businessId: String = "",
    val ownerNameSnapshot: String = ""
)

@Serializable
data class Batch(
    val id: String = "",
    val businessId: String = "",
    val orderId: String = "",
    val ownerId: String = "",
    val ownerNameSnapshot: String = "",
    val cloudProfileId: String = "",
    val profileNameSnapshot: String = "",
    val requestedQuantity: Int = 0,
    val successfulQuantity: Int = 0,
    val status: BatchStatus = BatchStatus.COMPLETED,
    val createdAt: String = "",
    val totalAmountMinor: Long = 0L,
    val wholesalePriceSnapshot: Long = 0L
)

// --- 4.16 الاشتراكات (Subscriptions) ---

/**
 * الحالة الإدارية للاشتراك (يملكها MGTik). منفصلة عن حالة التنفيذ على الراوتر [SubscriptionExecution]:
 * ACTIVE لا تعني أن الحساب فُعّل فعلاً على الراوتر.
 */
@Serializable
enum class SubscriptionStatus {
    DRAFT, SCHEDULED, ACTIVE, EXPIRED, SUSPENDED, CANCELLED
}

@Serializable
data class Subscription(
    val id: String = "",
    val subscriptionId: String = "",
    val customerUuid: String = "",
    /** اسم دخول حساب المستخدم المقابل (وليس اسم العميل). */
    val customerUsername: String = "",
    val ownerId: String = "",
    val ownerName: String = "",
    val profileId: String = "",
    val profileName: String = "",
    val profileClassificationSnapshot: String = "",
    val status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    /** بداية الصلاحية (ISO مرتَّب). */
    val startDate: String = "",
    /** موعد التجديد التالي: يساوي expiresAt عند autoRenewal وإلا فارغ. */
    val nextRenewal: String = "",
    val billingCycle: String = "monthly",
    val priceMinor: Long = 0L,
    val discountMinor: Long = 0L,
    val finalPriceMinor: Long = 0L,
    /** لقطة تسعير الاشتراك وقت إنشائه (لا تتغير بتعديل أسعار الفئة لاحقاً): العملة، مصدر السعر، نوع التسعير، الفئة، القاعدة. فارغ = اشتراك سابق للتسعير المركزي. */
    val priceCurrency: String = "",
    val priceSource: String = "",
    val pricingType: String = "",
    val priceCategoryId: String = "",
    val priceRuleId: String = "",
    val discountType: String? = null,
    val discountValue: Long = 0L,
    val autoRenewal: Boolean = false,
    val lastInvoiceId: String? = null,
    val notes: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val version: Int = 0,
    val uptimeUsed: String = "",
    val bytesIn: Long = 0L,
    val bytesOut: Long = 0L,

    // ---- العميل وبيانات الحساب التشغيلي ----
    val customerName: String = "",
    val customerPhone: String = "",
    /** كلمة مرور حساب المستخدم: تُحفظ في Firestore فقط (لا Room ولا سجلات). */
    val routerPassword: String = "",
    /** الباقة على الراوتر (لقطة وقت الإنشاء). */
    val routerProfileName: String = "",
    val limitUptimeSeconds: Long? = null,
    val limitTransferBytes: Long? = null,

    // ---- المدة والتواريخ ----
    val periodUnit: PeriodUnit = PeriodUnit.MONTHS,
    val periodCount: Int = 1,
    /** رقم الفترة الحالية (1 للأولى؛ يزيد بكل تجديد). */
    val periodNumber: Int = 0,
    val activatedAt: String = "",
    val expiresAt: String = "",
    val suspendedAt: String = "",

    // ---- حالة التنفيذ على الراوتر (منفصلة عن الحالة الإدارية) ----
    val execution: SubscriptionExecution = SubscriptionExecution.SYNCED,
    val pendingOp: SubscriptionOp? = null,
    val routerUserId: String = "",
    /** آخر حالة معطّل/مفعّل قُرئت من الراوتر؛ null = لم تُقرأ بعد. */
    val routerObservedDisabled: Boolean? = null,
    val lastSyncAt: String = "",
    val lastSyncError: String = "",
    val lastRenewKey: String = "",

    // ---- مجاميع مالية للاشتراك (المصدر: subscription_invoices/payments، وتُحدَّث في المعاملة نفسها) ----
    val totalInvoicedMinor: Long = 0L,
    val totalPaidMinor: Long = 0L
) {
    val balanceMinor: Long get() = totalInvoicedMinor - totalPaidMinor

    /** سعر الفترة الفعلي بعد الخصم (يطابق قاعدة [ps.mgtik.hotspot.domain.usecase.BillingRules.monthlyRecurringMinor]). */
    val effectivePriceMinor: Long
        get() = if (finalPriceMinor > 0L) finalPriceMinor else maxOf(priceMinor - discountMinor, 0L)
}

// --- 4.16 لوحة التحكم المالية ---

@Serializable
data class FinancialDashboard(
    val totalInvoicedMinor: Long = 0L,
    val totalPaidMinor: Long = 0L,
    val totalOutstandingMinor: Long = 0L,
    val totalRefundedMinor: Long = 0L,
    val activeSubscriptionsCount: Int = 0,
    val monthlyMrrMinor: Long = 0L,
    /** تحصيل ومستحق اشتراكات العملاء (نظام فواتير الاشتراكات المستقل) — لا يُدمج في أرقام فواتير الطلبات أعلاه. */
    val subscriptionsCollectedMinor: Long = 0L,
    val subscriptionsOutstandingMinor: Long = 0L,
    val currency: String = "₪"
)

// --- 4.16 سجل حركات الفاتورة والتحصيل ---

@Serializable
data class InvoiceEventLog(
    val eventId: String = "",
    val invoiceId: String = "",
    val eventType: String = "",
    val detail: Map<String, String> = emptyMap(),
    val performedBy: String = "",
    val occurredAt: String = ""
)

@Serializable
data class BillingSettings(
    val companyName: String = "",
    val companyLogoUrl: String? = null,
    val invoiceFooterNote: String? = null,
    val acceptedPaymentMethods: List<String> = emptyList(),
    val updatedAt: String = ""
)

// --- §6 مركز الإشعارات (S20) ---

/** فئات الإشعار المستخدمة فعلياً: النظام (SYSTEM) والفوترة (BILLING). تُخزَّن في Firestore بحقل "category" كاسم الـ enum. */
@Serializable
enum class NotificationCategory {
    SYSTEM, BILLING
}

/**
 * إشعار في مركز الإشعارات. يطابق مستند Firestore في مجموعة "notifications":
 * title/message/category/isRead/timestamp(Long ms) + حقل العزل adminUid (uid الأدمن، يُختم عند الكتابة من الجلسة).
 * معرّف المستند = id.
 */
@Serializable
data class MgtikNotification(
    val id: String = "",
    val title: String = "",
    val message: String = "",
    val category: NotificationCategory = NotificationCategory.SYSTEM,
    val isRead: Boolean = false,
    val timestamp: Long = 0L,
    val adminUid: String = "",
    /** وجهة اختيارية عند الضغط: [NotificationTarget.INVOICE] أو [NotificationTarget.ORDER] مع [targetId]. */
    val targetType: String? = null,
    val targetId: String? = null
)

/** قيم [MgtikNotification.targetType] المدعومة. */
object NotificationTarget {
    const val INVOICE = "INVOICE"
    const val ORDER = "ORDER"
    /** طابور العمليات المعلّقة (S29): وجهة إشعارات فشل العمليات المعلّقة. */
    const val OFFLINE_QUEUE = "OFFLINE_QUEUE"
}

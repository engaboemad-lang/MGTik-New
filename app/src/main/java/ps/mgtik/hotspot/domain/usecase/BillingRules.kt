package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.domain.model.FinancialDashboard
import ps.mgtik.hotspot.domain.model.Invoice
import ps.mgtik.hotspot.domain.model.OwnerAccount
import ps.mgtik.hotspot.domain.model.OwnerStatus
import ps.mgtik.hotspot.domain.model.InvoiceStatus
import ps.mgtik.hotspot.domain.model.Payment
import ps.mgtik.hotspot.domain.model.PaymentStatus
import ps.mgtik.hotspot.domain.model.PeriodUnit
import ps.mgtik.hotspot.domain.model.Subscription
import ps.mgtik.hotspot.domain.model.SubscriptionPayment
import ps.mgtik.hotspot.domain.model.SubscriptionStatus

/**
 * BillingRules - قواعد نقية (بلا Firebase/Android) لعمليات فواتير الطلبات ودفعاتها.
 *
 * كل المبالغ أعداد صحيحة بالوحدة الصغرى (Long)؛ لا Double في أي حساب مالي.
 * تُستدعى داخل معاملات Firestore في CloudBillingDataSource لتكون القاعدة نفسها هي التي تُختبَر وهي التي تُنفَّذ.
 * رموز الأخطاء تُرمى كـ Exception(code) وتُترجم في ErrorTranslator.
 */
object BillingRules {
    const val ERR_INVOICE_NOT_FOUND = "ERR_INVOICE_NOT_FOUND"
    const val ERR_INVOICE_NOT_PAYABLE = "ERR_INVOICE_NOT_PAYABLE"
    const val ERR_PAYMENT_EXCEEDS_DUE = "ERR_PAYMENT_EXCEEDS_DUE"
    const val ERR_PAYMENT_NOT_FOUND = "ERR_PAYMENT_NOT_FOUND"
    const val ERR_PAYMENT_ALREADY_REVERSED = "ERR_PAYMENT_ALREADY_REVERSED"
    const val ERR_REFUND_EXCEEDS_PAYMENT = "ERR_REFUND_EXCEEDS_PAYMENT"
    const val ERR_DISCOUNT_INVALID = "ERR_DISCOUNT_INVALID"
    const val ERR_DISCOUNT_BELOW_PAID = "ERR_DISCOUNT_BELOW_PAID"
    const val ERR_INVOICE_HAS_PAYMENTS = "ERR_INVOICE_HAS_PAYMENTS"
    const val ERR_INVOICE_NOT_CANCELED = "ERR_INVOICE_NOT_CANCELED"
    const val ERR_INVOICE_FIELD_NOT_UPDATABLE = "ERR_INVOICE_FIELD_NOT_UPDATABLE"
    const val ERR_PAYMENT_FIELD_NOT_UPDATABLE = "ERR_PAYMENT_FIELD_NOT_UPDATABLE"

    const val DISCOUNT_PERCENTAGE = "percentage"
    const val DISCOUNT_FIXED = "fixed"

    /** الحقول المسموح تعديلها مباشرة؛ المبالغ والحالة لا تتغير إلا عبر عمليات مالية صريحة (دفعة/خصم/إلغاء/إبطال). */
    val UPDATABLE_INVOICE_FIELDS: Set<String> = setOf("notes", "dueDate")
    val UPDATABLE_PAYMENT_FIELDS: Set<String> = setOf("notes", "reference")

    /**
     * فاتورة متأخرة: تقبل الدفع ومتبقيها > 0 وتاريخ استحقاقها (إن وُجد) قبل [todayIso] (أول 10 أحرف yyyy-MM-dd).
     * لا استحقاق محدد = لا تأخر (لا نخترع تاريخاً). الحالة المخزَّنة لا تتغير؛ هذا عرض محسوب فقط.
     */
    fun isOverdue(invoice: Invoice, todayIso: String): Boolean {
        val due = invoice.dueDate?.take(10)?.takeIf { it.length == 10 } ?: return false
        return invoice.status.acceptsPayment && outstandingMinor(invoice) > 0 && due < todayIso.take(10)
    }

    /** المتبقي على الفاتورة (لا يقل عن صفر). */
    fun dueMinor(totalMinor: Long, paidMinor: Long): Long = maxOf(totalMinor - paidMinor, 0L)

    /** مستحق فاتورة لأغراض الإجماليات: الملغاة لا مستحق عليها. */
    fun outstandingMinor(invoice: Invoice): Long =
        if (invoice.status == InvoiceStatus.CANCELED) 0L else dueMinor(invoice.totalMinor, invoice.amountPaidMinor)

    /** رمز الخطأ إن لم يجز تسجيل دفعة [amountMinor] على الفاتورة، وإلا null. الترتيب ثابت. */
    fun checkRecordPayment(
        invoiceExists: Boolean,
        status: InvoiceStatus?,
        totalMinor: Long,
        paidMinor: Long,
        amountMinor: Long
    ): String? = when {
        amountMinor <= 0 -> OwnerCreditRules.ERR_INVALID_AMOUNT
        !invoiceExists -> ERR_INVOICE_NOT_FOUND
        status == null || !status.acceptsPayment || dueMinor(totalMinor, paidMinor) <= 0 -> ERR_INVOICE_NOT_PAYABLE
        amountMinor > dueMinor(totalMinor, paidMinor) -> ERR_PAYMENT_EXCEEDS_DUE
        else -> null
    }

    /**
     * حالة الفاتورة بعد تغيّر مبالغها. الملغاة تبقى ملغاة مهما تغيّرت المبالغ (إبطال دفعة لا يُحيي فاتورة ملغاة).
     * عند عدم وجود مدفوع تبقى DRAFT كما هي، وما عداها يصير DUE.
     */
    fun statusFor(current: InvoiceStatus?, totalMinor: Long, paidMinor: Long): InvoiceStatus = when {
        current == InvoiceStatus.CANCELED -> InvoiceStatus.CANCELED
        totalMinor - paidMinor <= 0 -> InvoiceStatus.PAID
        paidMinor > 0 -> InvoiceStatus.PARTIALLY_PAID
        current == InvoiceStatus.DRAFT -> InvoiceStatus.DRAFT
        else -> InvoiceStatus.DUE
    }

    /** مبالغ الفاتورة بعد إبطال/استرداد [reversedMinor] من المدفوع (لا يقل المدفوع عن صفر). */
    fun afterReversal(current: InvoiceStatus?, totalMinor: Long, currentPaidMinor: Long, reversedMinor: Long): OwnerCreditRules.InvoiceAmounts {
        val paid = maxOf(currentPaidMinor - reversedMinor, 0L)
        // فاتورة مسدّدة أُبطلت دفعتها: لا تُعرف حالتها الأصلية (DRAFT/DUE) فتصير DUE أو PARTIALLY_PAID حسب المتبقي
        val status = statusFor(current, totalMinor, paid)
        return OwnerCreditRules.InvoiceAmounts(
            paidMinor = paid,
            dueMinor = totalMinor - paid,
            statusName = status.name.lowercase(),
            isPaid = status == InvoiceStatus.PAID
        )
    }

    /** الدفعة صارت ملغاة/مستردة (أو أي حالة غير ACTIVE): لا تقبل إبطالاً ولا استرداداً ثانياً. */
    fun isReversed(paymentStatus: PaymentStatus?): Boolean = paymentStatus != PaymentStatus.ACTIVE

    /** رمز الخطأ إن لم يجز استرداد [refundMinor] من دفعة مبلغها [paymentMinor]، وإلا null. */
    fun checkRefund(paymentMinor: Long, refundMinor: Long): String? = when {
        refundMinor <= 0 -> OwnerCreditRules.ERR_INVALID_AMOUNT
        refundMinor > paymentMinor -> ERR_REFUND_EXCEEDS_PAYMENT
        else -> null
    }

    /**
     * مبلغ الخصم الفعلي (وحدة صغرى) أو null إن كان غير صالح. النسبة عدد صحيح 0..100، والخصم الثابت 0..الإجمالي الفرعي.
     * القسمة الصحيحة تقرّب الخصم لأسفل (لا تنتج كسوراً)، فلا يتجاوز الخصم النسبة المطلوبة أبداً.
     */
    fun discountAmountMinor(type: String, value: Long, subtotalMinor: Long): Long? = when (type) {
        DISCOUNT_PERCENTAGE -> if (value in 0..100) subtotalMinor * value / 100 else null
        DISCOUNT_FIXED -> if (value in 0..subtotalMinor) value else null
        else -> null
    }

    data class DiscountResult(val totalMinor: Long, val dueMinor: Long, val amounts: OwnerCreditRules.InvoiceAmounts)

    /**
     * يطبّق خصماً على فاتورة تقبل الدفع. يرفض: حالة لا تقبل التعديل (مدفوعة/ملغاة)، خصماً غير صالح،
     * أو إجمالياً جديداً أقل من المدفوع فعلاً (كان ينتج فاتورة مدفوعة بزيادة ومتبقياً سالباً).
     */
    fun applyDiscount(
        status: InvoiceStatus?,
        subtotalMinor: Long,
        paidMinor: Long,
        type: String,
        value: Long
    ): Pair<String?, DiscountResult?> {
        if (status == null || !status.acceptsPayment) return ERR_INVOICE_NOT_PAYABLE to null
        val discount = discountAmountMinor(type, value, subtotalMinor) ?: return ERR_DISCOUNT_INVALID to null
        val total = subtotalMinor - discount
        if (total < paidMinor) return ERR_DISCOUNT_BELOW_PAID to null
        val newStatus = statusFor(status, total, paidMinor)
        return null to DiscountResult(
            totalMinor = total,
            dueMinor = total - paidMinor,
            amounts = OwnerCreditRules.InvoiceAmounts(paidMinor, total - paidMinor, newStatus.name.lowercase(), newStatus == InvoiceStatus.PAID)
        )
    }

    /**
     * رمز الخطأ إن لم يجز إلغاء الفاتورة، وإلا null. فاتورة عليها مدفوع لا تُلغى مباشرة (ولا بـ force):
     * إلغاؤها كان يترك دفعاتها ACTIVE ورصيد المالك بلا إعادة، فتختلف الفواتير عن الدفعات والتقارير.
     * المسار المعتمد: إبطال/استرداد الدفعات صراحةً ثم الإلغاء.
     */
    fun checkCancel(status: InvoiceStatus?, paidMinor: Long): String? = when {
        status == null -> ERR_INVOICE_NOT_FOUND
        paidMinor > 0 || status == InvoiceStatus.PAID || status == InvoiceStatus.PARTIALLY_PAID -> ERR_INVOICE_HAS_PAYMENTS
        else -> null
    }

    /** الحالة التي تعود إليها فاتورة ملغاة عند إعادة فتحها (DRAFT كما كان المسار سابقاً؛ أو حسب المدفوع إن وُجد). */
    fun reopenStatus(totalMinor: Long, paidMinor: Long): InvoiceStatus = when {
        paidMinor > 0 -> statusFor(InvoiceStatus.DUE, totalMinor, paidMinor)
        else -> InvoiceStatus.DRAFT
    }

    /** يرفض أي مفتاح خارج المسموح (كان updateInvoice/updatePayment يقبلان مبالغ وحالات). */
    fun disallowedKeys(fields: Map<String, Any?>, allowed: Set<String>): Set<String> = fields.keys - allowed

    // ───────────── الإجماليات (لوحة الملخص والتقارير) — مصدرها الفواتير والدفعات الفعلية ─────────────

    /** صافي المحصَّل من دفعة: الفعّالة بكامل مبلغها، والمستردة بمبلغها ناقص ما استُرد، وغير ذلك صفر. */
    fun collectedMinor(payment: Payment): Long = when (payment.status) {
        PaymentStatus.ACTIVE -> payment.amountMinor
        PaymentStatus.REFUNDED -> maxOf(payment.amountMinor - payment.refundedMinor, 0L)
        else -> 0L
    }

    data class Totals(
        val invoicedMinor: Long,
        val collectedMinor: Long,
        val outstandingMinor: Long,
        val refundedMinor: Long
    )

    fun totals(invoices: List<Invoice>, payments: List<Payment>): Totals = Totals(
        invoicedMinor = invoices.filter { it.status != InvoiceStatus.CANCELED }.sumOf { it.totalMinor },
        collectedMinor = payments.sumOf { collectedMinor(it) },
        outstandingMinor = invoices.sumOf { outstandingMinor(it) },
        refundedMinor = payments.filter { it.status == PaymentStatus.REFUNDED }.sumOf { it.refundedMinor }
    )

    /**
     * لوحة الملخص المالي الكاملة من القوائم نفسها (فواتير ودفعات واشتراكات). مصدر واحد تستعمله Overview والمستودع،
     * فلا تُفتح مستمعات Firestore مكررة لحساب الأرقام نفسها.
     */
    fun dashboard(invoices: List<Invoice>, payments: List<Payment>, subscriptions: List<Subscription>): FinancialDashboard {
        val t = totals(invoices, payments)
        return FinancialDashboard(
            totalInvoicedMinor = t.invoicedMinor,
            totalPaidMinor = t.collectedMinor,
            totalOutstandingMinor = t.outstandingMinor,
            totalRefundedMinor = t.refundedMinor,
            activeSubscriptionsCount = subscriptions.count { it.status == SubscriptionStatus.ACTIVE },
            monthlyMrrMinor = monthlyRecurringMinor(subscriptions),
            subscriptionsCollectedMinor = subscriptions.sumOf { maxOf(it.totalPaidMinor, 0L) },
            subscriptionsOutstandingMinor = subscriptions.sumOf { maxOf(it.balanceMinor, 0L) }
        )
    }

    /**
     * ملخص فترة لوحة Billing (حركة الفترة لا لقطة): مفوتر الطلبات غير الملغاة المنشأة في الفترة، وصافي ما حُصِّل من دفعات الفترة،
     * وما استُرد منها، ومحصَّل الاشتراكات (دفعات غير مبطلة). [range] = null لكل الفترات. المستحق ليس حركة فلا يدخل هنا (لقطة راهنة).
     * [subscriptionPayments] = null (لم تصل بعد/فشلت) ⇒ محصَّل الاشتراكات null لا صفر مضلل.
     */
    data class PeriodSummary(
        val invoicesCount: Int,
        val invoicedMinor: Long,
        val paymentsCount: Int,
        val collectedMinor: Long,
        val refundedMinor: Long,
        val subscriptionsCollectedMinor: Long?
    )

    fun periodSummary(
        invoices: List<Invoice>,
        payments: List<Payment>,
        subscriptionPayments: List<SubscriptionPayment>?,
        range: Pair<String, String>?
    ): PeriodSummary {
        fun inRange(iso: String) = range == null || ps.mgtik.hotspot.core.util.isWithinDateRange(iso, range.first, range.second)
        val inv = invoices.filter { it.status != InvoiceStatus.CANCELED && inRange(it.createdAt) }
        val pays = payments.filter { inRange(it.createdAt) }
        return PeriodSummary(
            invoicesCount = inv.size,
            invoicedMinor = inv.sumOf { it.totalMinor },
            paymentsCount = pays.count { collectedMinor(it) > 0 },
            collectedMinor = pays.sumOf { collectedMinor(it) },
            refundedMinor = pays.filter { it.status == PaymentStatus.REFUNDED }.sumOf { it.refundedMinor },
            subscriptionsCollectedMinor = subscriptionPayments
                ?.filter { !it.isVoid && inRange(it.createdAt) }?.sumOf { it.amountMinor }
        )
    }

    /**
     * أرصدة الملاك/الوكلاء وديونهم الحالية (لقطة راهنة لا فترة): الرصيد المتاح من حساب المالك، والمستحق من فواتيره غير الملغاة.
     * الملاك المؤرشفون خارج التقرير؛ الأكثر ديناً أولاً.
     */
    fun ownerBalances(owners: List<OwnerAccount>, invoices: List<Invoice>): Map<String, Any?> {
        val rows = owners.filter { it.status != OwnerStatus.ARCHIVED }.map { o ->
            mapOf(
                "label" to o.name,
                "availableCreditMinor" to o.availableCreditMinor,
                "outstandingMinor" to invoices.filter { it.ownerId == o.ownerId }.sumOf { outstandingMinor(it) }
            )
        }.sortedByDescending { it["outstandingMinor"] as Long }
        return mapOf(
            "ownersCount" to rows.size,
            "totalCreditMinor" to rows.sumOf { it["availableCreditMinor"] as Long },
            "totalOutstandingMinor" to rows.sumOf { it["outstandingMinor"] as Long },
            "owners" to rows
        )
    }

    /** نسبة نجاح التوليد (بطاقات ناجحة من المطلوبة) كعدد صحيح؛ null إن لم يُطلب شيء فلا تُخترع نسبة. */
    fun generationSuccessPercent(requested: Int, successful: Int): Int? =
        if (requested <= 0) null else (successful * 100L / requested).toInt()

    /** صافي محصَّل دفعات اليوم المحلي [today] (yyyy-MM-dd)؛ يستعمل حدود الأيام الموحَّدة في isWithinDateRange. */
    fun incomeForLocalDay(payments: List<Payment>, today: String): Long =
        payments.filter { ps.mgtik.hotspot.core.util.isWithinDateRange(it.createdAt, today, today) }.sumOf { collectedMinor(it) }

    /**
     * الإيراد الشهري المتكرر من الاشتراكات الفعّالة (سعر الفترة بعد الخصم مُطبَّعاً إلى 30 يوماً/شهر).
     * أعداد صحيحة فقط؛ الباقي من القسمة يُهمل (أقل من وحدة صغرى واحدة لكل اشتراك).
     */
    fun monthlyRecurringMinor(subscriptions: List<Subscription>): Long =
        subscriptions.filter { it.status == SubscriptionStatus.ACTIVE && it.periodCount > 0 }.sumOf { s ->
            val price = if (s.finalPriceMinor > 0L) s.finalPriceMinor else maxOf(s.priceMinor - s.discountMinor, 0L)
            when (s.periodUnit) {
                PeriodUnit.MONTHS -> price / s.periodCount
                PeriodUnit.DAYS -> price * 30L / s.periodCount
            }
        }
}

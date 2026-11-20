package ps.mgtik.hotspot.domain.subscription

import ps.mgtik.hotspot.domain.model.SubscriptionPayStatus
import java.time.Instant

/**
 * SubscriptionBilling - حسابات فواتير ومدفوعات الاشتراكات (نقية).
 * نظام مستقل تماماً عن محاسبة الطلبات والملاك: collections خاصة (subscription_invoices / subscription_payments)،
 * بلا رصيد مالك ولا دفتر ملاك، ولا يُستعمل فيه أي سجل من invoices/payments الخاصة بالطلبات.
 */
object SubscriptionBilling {

    const val ERR_AMOUNT_INVALID = "ERR_SUB_AMOUNT_INVALID"
    const val ERR_OVERPAY = "ERR_SUB_OVERPAY"
    const val ERR_INVOICE_SETTLED = "ERR_SUB_INVOICE_SETTLED"
    const val ERR_PAYMENT_NOT_FOUND = "ERR_SUB_PAYMENT_NOT_FOUND"
    const val ERR_INVOICE_NOT_FOUND = "ERR_SUB_INVOICE_NOT_FOUND"

    /** الصافي بعد الخصم. الخصم محصور بين 0 والسعر (صالح مسبقاً في validateNew). */
    fun netMinor(priceMinor: Long, discountMinor: Long): Long = priceMinor - discountMinor

    fun payStatus(totalMinor: Long, paidMinor: Long): SubscriptionPayStatus = when {
        totalMinor <= 0L -> SubscriptionPayStatus.PAID // فاتورة بصفر (خصم كامل/باقة مجانية): لا شيء مستحق
        paidMinor <= 0L -> SubscriptionPayStatus.UNPAID
        paidMinor < totalMinor -> SubscriptionPayStatus.PARTIAL
        else -> SubscriptionPayStatus.PAID
    }

    /**
     * رمز الخطأ إن لم يجز تسجيل دفعة بهذا المبلغ على فاتورة، وإلا null.
     * سياسة الاشتراكات: لا دفع زائد (لا رصيد دائن للعميل في هذا النظام).
     */
    fun checkPayment(totalMinor: Long, paidMinor: Long, amountMinor: Long): String? {
        val balance = totalMinor - paidMinor
        return when {
            amountMinor <= 0 -> ERR_AMOUNT_INVALID
            balance <= 0 -> ERR_INVOICE_SETTLED
            amountMinor > balance -> ERR_OVERPAY
            else -> null
        }
    }

    /** المدفوع بعد إلغاء دفعة (لا يقل عن صفر). */
    fun paidAfterVoid(paidMinor: Long, voidedAmountMinor: Long): Long = maxOf(paidMinor - voidedAmountMinor, 0L)

    /** متأخرة: فيها رصيد وتجاوزت موعد استحقاقها. */
    fun isOverdue(dueIso: String, balanceMinor: Long, now: Instant): Boolean {
        val due = SubscriptionRules.parse(dueIso) ?: return false
        return balanceMinor > 0 && due.isBefore(now)
    }

    fun invoiceId(subscriptionId: String, periodNumber: Int) = "${subscriptionId}_p$periodNumber"
}

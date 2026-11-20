package ps.mgtik.hotspot.ui.util

import android.content.Context
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.model.PaymentStatus

/**
 * نصوص العرض للفواتير: لا يظهر أي مفتاح/قيمة تقنية خام للمستخدم. القيم المجهولة تُعرض بنص عام مفهوم.
 */
object InvoiceTexts {
    fun methodRes(method: String): Int = when (method.lowercase()) {
        "cash" -> R.string.owner_method_cash
        "transfer" -> R.string.owner_method_transfer
        "credit" -> R.string.inv_method_credit
        else -> R.string.inv_method_other
    }

    fun paymentStatusRes(status: PaymentStatus): Int = when (status) {
        PaymentStatus.ACTIVE -> R.string.inv_pay_status_active
        PaymentStatus.VOID, PaymentStatus.CANCELED -> R.string.inv_pay_status_void
        PaymentStatus.REFUNDED -> R.string.inv_pay_status_refunded
    }

    /** الفاتورة التي ينشئها المحرك تحمل ملاحظة "Batch B-000001": تُعرض بالعربية ولا تظهر كنص برمجي. */
    fun displayNotes(context: Context, notes: String?): String? {
        val n = notes?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (n.startsWith("Batch ")) context.getString(R.string.inv_note_batch, n.removePrefix("Batch ").trim()) else n
    }

    /** yyyy-MM-dd من ISO المخزَّن؛ فارغ إن لم يوجد تاريخ. */
    fun dateOnly(iso: String?): String = iso?.take(10)?.takeIf { it.length == 10 } ?: ""

    /** يعزل النص (أرقام/عملات) كاتجاه LTR داخل فقرة RTL كي لا تنقلب الأرقام أو علامة العملة. */
    fun ltr(text: String): String = "\u2066$text\u2069"
}

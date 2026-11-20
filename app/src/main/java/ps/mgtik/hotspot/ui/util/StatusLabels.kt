package ps.mgtik.hotspot.ui.util

import androidx.annotation.StringRes
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.ui.components.chips.StatusTone
import ps.mgtik.hotspot.domain.model.InvoiceStatus
import ps.mgtik.hotspot.domain.model.OrderStatus

/** نص حالة الطلب القابل للترجمة (بدل عرض اسم الـ enum الخام للمستخدم). */
@StringRes
fun OrderStatus.labelRes(): Int = when (this) {
    OrderStatus.DRAFT -> R.string.order_status_draft
    OrderStatus.CLOSED -> R.string.order_status_closed
    OrderStatus.CANCELLED -> R.string.order_status_cancelled
    OrderStatus.REOPENED -> R.string.order_status_reopened
}

/** نص حالة الفاتورة (كانت القوائم تعرض الملغاة والمسودة «مستحقة» لأنها تفحص isPaid فقط). */
@StringRes
fun InvoiceStatus.labelRes(): Int = when (this) {
    InvoiceStatus.PAID -> R.string.owner_invoice_paid
    InvoiceStatus.PARTIALLY_PAID -> R.string.owner_invoice_partial
    InvoiceStatus.CANCELED -> R.string.owner_invoice_canceled
    InvoiceStatus.DRAFT -> R.string.owner_invoice_draft
    InvoiceStatus.DUE -> R.string.owner_invoice_due
}

/** لون دلالة حالة الطلب: مغلق = نجاح، أُعيد فتحه = تحذير، ملغى = خطأ، مسودة = محايد. */
fun OrderStatus.tone(): StatusTone = when (this) {
    OrderStatus.DRAFT -> StatusTone.NEUTRAL
    OrderStatus.CLOSED -> StatusTone.SUCCESS
    OrderStatus.CANCELLED -> StatusTone.ERROR
    OrderStatus.REOPENED -> StatusTone.WARNING
}

/** لون دلالة حالة الفاتورة. «متأخرة» شارة منفصلة بلون الخطأ، فالمستحقة العادية معلومة لا إنذار. */
fun InvoiceStatus.tone(): StatusTone = when (this) {
    InvoiceStatus.PAID -> StatusTone.SUCCESS
    InvoiceStatus.PARTIALLY_PAID -> StatusTone.WARNING
    InvoiceStatus.DUE -> StatusTone.INFO
    InvoiceStatus.CANCELED, InvoiceStatus.DRAFT -> StatusTone.NEUTRAL
}

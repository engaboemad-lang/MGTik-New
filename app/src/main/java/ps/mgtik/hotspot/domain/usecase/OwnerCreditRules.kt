package ps.mgtik.hotspot.domain.usecase

/**
 * OwnerCreditRules - قواعد نقية (بلا Firebase/Android) لتسوية فاتورة من رصيد المالك المدفوع مقدماً.
 *
 * نموذج الرصيد: محفظة مدفوعة مقدماً. الرصيد يزيد بالدفعة المقدمة/الافتتاحي، وينقص بالاسترجاع وبتسوية فاتورة منه،
 * ويعود بإلغاء/استرداد دفعة «credit». كل حركة = قيد واحد في owner_ledger + تغيير الرصيد في المعاملة نفسها.
 */
object OwnerCreditRules {
    /** طريقة الدفع المحجوزة لتسوية الفواتير من الرصيد؛ لا تُنشأ إلا عبر تسوية الرصيد (تخصم المحفظة). */
    const val METHOD_CREDIT = "credit"

    const val ERR_INVALID_AMOUNT = "ERR_INVALID_AMOUNT"
    const val ERR_OWNER_NOT_FOUND = "ERR_OWNER_NOT_FOUND"
    const val ERR_INVOICE_NOT_PAYABLE = "ERR_CREDIT_INVOICE_NOT_PAYABLE"
    const val ERR_EXCEEDS_DUE = "ERR_CREDIT_EXCEEDS_DUE"
    const val ERR_INSUFFICIENT_CREDIT = "ERR_INSUFFICIENT_CREDIT"
    const val ERR_CREDIT_METHOD_RESERVED = "ERR_CREDIT_METHOD_RESERVED"
    const val ERR_OWNER_FIELD_NOT_UPDATABLE = "ERR_OWNER_FIELD_NOT_UPDATABLE"
    /** وكيل موقوف/مؤرشف: لا عمليات جديدة (إنشاء اشتراك/توليد/تسعير) باسمه؛ المستخدمون والفواتير القائمة لا تتأثر. */
    const val ERR_OWNER_NOT_ACTIVE = "ERR_OWNER_NOT_ACTIVE"
    const val ERR_OWNER_NAME_REQUIRED = "ERR_OWNER_NAME_REQUIRED"
    const val ERR_OWNER_STATUS_INVALID = "ERR_OWNER_STATUS_INVALID"

    /** رمز الرفض لعملية جديدة باسم وكيل بحالة [rawStatus] (null = غير موجود)، وإلا null. */
    fun checkOwnerCanOperate(exists: Boolean, rawStatus: String?): String? = when {
        !exists -> ERR_OWNER_NOT_FOUND
        !rawStatus.equals("ACTIVE", ignoreCase = true) -> ERR_OWNER_NOT_ACTIVE
        else -> null
    }

    /** يتحقق من حقول التعديل الهوياتية (name/phone/status) قبل الكتابة؛ يرجع رمز الخطأ أو null. */
    fun validateIdentityUpdate(fields: Map<String, Any?>): String? {
        if (fields.containsKey("name") && (fields["name"] as? String).isNullOrBlank()) return ERR_OWNER_NAME_REQUIRED
        if (fields.containsKey("status")) {
            val st = fields["status"] as? String
            if (st == null || st !in setOf("ACTIVE", "SUSPENDED", "ARCHIVED")) return ERR_OWNER_STATUS_INVALID
        }
        return null
    }

    /** رمز الخطأ إن لم يجز خصم [amountMinor] من الرصيد لتسوية الفاتورة، وإلا null. الترتيب ثابت ومُختبَر. */
    fun checkApply(
        invoiceOwnerId: String?,
        invoiceAcceptsPayment: Boolean,
        amountDueMinor: Long,
        availableCreditMinor: Long,
        amountMinor: Long
    ): String? = when {
        amountMinor <= 0 -> ERR_INVALID_AMOUNT
        invoiceOwnerId.isNullOrBlank() || !invoiceAcceptsPayment || amountDueMinor <= 0 -> ERR_INVOICE_NOT_PAYABLE
        amountMinor > amountDueMinor -> ERR_EXCEEDS_DUE
        amountMinor > availableCreditMinor -> ERR_INSUFFICIENT_CREDIT
        else -> null
    }

    data class InvoiceAmounts(val paidMinor: Long, val dueMinor: Long, val statusName: String, val isPaid: Boolean)

    /** حالة الفاتورة بعد دفعة (نفس صيغة تخزين recordPayment: أسماء صغيرة). */
    fun afterPayment(totalMinor: Long, currentPaidMinor: Long, amountMinor: Long): InvoiceAmounts {
        val paid = currentPaidMinor + amountMinor
        val due = totalMinor - paid
        val isPaid = due <= 0
        return InvoiceAmounts(paid, due, if (isPaid) "paid" else "partially_paid", isPaid)
    }

    /** الرصيد بعد خصم/إعادة مبلغ. */
    fun creditAfterCharge(availableCreditMinor: Long, amountMinor: Long): Long = availableCreditMinor - amountMinor
    fun creditAfterRestore(availableCreditMinor: Long, amountMinor: Long): Long = availableCreditMinor + amountMinor

    /** الرصيد الافتتاحي يُضاف للرصيد القائم ولا يستبدله (دفعات مقدمة سبقته لا تُمحى). */
    fun creditAfterOpening(availableCreditMinor: Long, openingMinor: Long): Long = availableCreditMinor + openingMinor

    data class DueInvoice(val invoiceId: String, val dueMinor: Long, val createdAt: String)
    data class Allocation(val invoiceId: String, val amountMinor: Long)

    /**
     * توزيع [budgetMinor] على الفواتير المستحقة من الأقدم للأحدث (الصيغة الزمنية المرتَّبة؛ التعادل بالمعرّف).
     * لا يتجاوز مستحق أي فاتورة ولا الميزانية؛ تُهمل الفواتير غير المستحقة.
     */
    fun allocateOldestFirst(budgetMinor: Long, dues: List<DueInvoice>): List<Allocation> {
        var left = budgetMinor
        val out = mutableListOf<Allocation>()
        for (d in dues.filter { it.dueMinor > 0 }.sortedWith(compareBy({ it.createdAt }, { it.invoiceId }))) {
            if (left <= 0) break
            val take = minOf(left, d.dueMinor)
            out += Allocation(d.invoiceId, take)
            left -= take
        }
        return out
    }

    /** الحقول المسموح تعديلها عبر updateOwner؛ الرصيد وحقوله لا تتغير إلا بقيود دفتر. */
    val UPDATABLE_OWNER_FIELDS: Set<String> = setOf("name", "phone", "status")
}

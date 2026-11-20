package ps.mgtik.hotspot.data.local.entity

import ps.mgtik.hotspot.data.mapping.toEnumOrNull
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ps.mgtik.hotspot.domain.model.Invoice
import ps.mgtik.hotspot.domain.model.InvoiceStatus

/**
 * InvoiceEntity - كيان الفاتورة المخزنة محلياً.
 * تم التدقيق: توحيد مسميات الحقول وضمان سلامة التحويل (Safe Enum Mapping).
 */
@Entity(
    tableName = "cached_invoices",
    // ORDER BY created_at DESC في كل استعلامات القائمة (InvoiceDao). فلتر owner_id/status يستعمل نمط (:p IS NULL OR col = :p)
    // الذي لا يستفيد SQLite من فهرس له، فلا فهرس عليهما (كلفة كتابة بلا فائدة قراءة).
    indices = [Index(value = ["created_at"])]
)
data class InvoiceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "invoice_number") val invoiceNumber: String,
    @ColumnInfo(name = "order_id") val orderId: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "subtotal_minor") val subtotalMinor: Long,
    @ColumnInfo(name = "discount_type") val discountType: String?,
    @ColumnInfo(name = "discount_value_minor") val discountValueMinor: Long?,
    @ColumnInfo(name = "total_minor") val totalMinor: Long,
    @ColumnInfo(name = "amount_paid_minor") val amountPaidMinor: Long,
    @ColumnInfo(name = "amount_due_minor") val amountDueMinor: Long,
    @ColumnInfo(name = "due_date") val dueDate: String?,
    @ColumnInfo(name = "notes") val notes: String?,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "owner_name_snapshot") val ownerNameSnapshot: String, // تم تعديله ليتطابق مع الدومين
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "payment_method") val paymentMethod: String,
    @ColumnInfo(name = "is_paid") val isPaid: Boolean,
    @ColumnInfo(name = "issue_date") val issueDate: String,
    @ColumnInfo(name = "cached_at") val cachedAt: Long = System.currentTimeMillis()
) {
    /** null عند حالة ناقصة/مجهولة: يرفضها المستدعي ويسجّلها SYNC_ERROR (لا تحويل إلى DRAFT). */
    fun toDomainOrNull(): Invoice? {
        val parsedStatus = status.toEnumOrNull<InvoiceStatus>() ?: return null
        return Invoice(
            id = id,
            invoiceNumber = invoiceNumber,
            orderId = orderId,
            ownerId = ownerId,
            status = parsedStatus,
            subtotalMinor = subtotalMinor,
            discountType = discountType,
            discountValueMinor = discountValueMinor,
            totalMinor = totalMinor,
            amountPaidMinor = amountPaidMinor,
            amountDueMinor = amountDueMinor,
            dueDate = dueDate,
            notes = notes,
            createdAt = createdAt,
            updatedAt = updatedAt,
            businessId = businessId,
            ownerNameSnapshot = ownerNameSnapshot,
            currency = currency,
            paymentMethod = paymentMethod,
            isPaid = isPaid,
            issueDate = issueDate
        )
    }

    companion object {
        fun fromDomain(invoice: Invoice): InvoiceEntity {
            return InvoiceEntity(
                id = invoice.id,
                invoiceNumber = invoice.invoiceNumber,
                orderId = invoice.orderId,
                ownerId = invoice.ownerId,
                status = invoice.status.name,
                subtotalMinor = invoice.subtotalMinor,
                discountType = invoice.discountType,
                discountValueMinor = invoice.discountValueMinor,
                totalMinor = invoice.totalMinor,
                amountPaidMinor = invoice.amountPaidMinor,
                amountDueMinor = invoice.amountDueMinor,
                dueDate = invoice.dueDate,
                notes = invoice.notes,
                createdAt = invoice.createdAt,
                updatedAt = invoice.updatedAt,
                businessId = invoice.businessId,
                ownerNameSnapshot = invoice.ownerNameSnapshot,
                currency = invoice.currency,
                paymentMethod = invoice.paymentMethod,
                isPaid = invoice.isPaid,
                issueDate = invoice.issueDate
            )
        }
    }
}

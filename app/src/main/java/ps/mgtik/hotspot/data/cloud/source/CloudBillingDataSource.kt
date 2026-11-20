package ps.mgtik.hotspot.data.cloud.source

import ps.mgtik.hotspot.core.util.toSortableIso
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.safeApiCall
import ps.mgtik.hotspot.data.mapping.toEnumOrNull
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.usecase.BillingRules
import ps.mgtik.hotspot.domain.usecase.OwnerCreditRules
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CloudBillingDataSource - تنفيذ العمليات السحابية للفوترة والدفعات والتقارير (§4.16 - §4.20)
 */
@Singleton
class CloudBillingDataSource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val mapper: BillingDocMapper,
    private val auth: FirebaseAuth
) {
    private val invoiceCollection = firestore.collection("invoices")
    private val paymentCollection = firestore.collection("payments")
    private val ownerCollection = firestore.collection("owners")
    private val ledgerCollection = firestore.collection("owner_ledger")
    private val eventLogCollection = firestore.collection("invoice_event_logs")
    private val settingsDoc = firestore.collection("settings").document("billing")

    /**
     * سجل تدقيق مالي غير قابل للتعديل: يُكتب داخل معاملة العملية نفسها (ذرّياً مع أثرها المالي)، ومعرّفه
     * «مفتاح العملية_النوع» فإعادة المحاولة لا تكرّره. كان invoice_event_logs يُقرأ ولا يكتبه أحد فيبقى فارغاً.
     */
    private fun logEvent(
        tx: com.google.firebase.firestore.Transaction,
        invoiceId: String,
        type: String,
        detail: Map<String, Any?>,
        key: String
    ) {
        tx.set(eventLogCollection.document("${key}_$type"), eventFields(invoiceId, type, detail))
    }

    private fun eventFields(invoiceId: String, type: String, detail: Map<String, Any?>): Map<String, Any?> = hashMapOf(
        "invoiceId" to invoiceId,
        "eventType" to type,
        "detail" to detail,
        "performedBy" to (auth.currentUser?.uid ?: ""),
        "occurredAt" to FieldValue.serverTimestamp()
    )

    fun getInvoices(ownerId: String?, status: InvoiceStatus?): Flow<List<Invoice>> {
        var query: Query = invoiceCollection.orderBy("createdAt", Query.Direction.DESCENDING)
        ownerId?.let { query = query.whereEqualTo("ownerId", it) }
        // الحالة تُكتب أحياناً بحروف كبيرة (محرك التوليد) وأحياناً صغيرة (معاملات الدفع)؛ Firestore حساسة لحالة الأحرف
        status?.let { query = query.whereIn("status", listOf(it.name, it.name.lowercase())) }

        return query.snapshots().map { snapshot ->
            snapshot.documents.mapNotNull { doc -> mapper.invoice(doc) }
        }
    }

    fun getInvoiceDetails(invoiceId: String): Flow<Invoice?> {
        return invoiceCollection.document(invoiceId).snapshots().map { doc ->
            if (doc.exists()) mapper.invoice(doc) else null
        }
    }

    /**
     * تعديل مباشر لحقول غير مالية فقط (ملاحظات/تاريخ استحقاق). المبالغ والحالة والمدفوع لا تتغير إلا بعمليات
     * مالية صريحة (دفعة/خصم/إلغاء/إبطال)؛ كان أي مفتاح مقبولاً بما فيه totalMinor وstatus فيُفسد الفاتورة بصمت.
     */
    suspend fun updateInvoice(invoiceId: String, fields: Map<String, Any?>, idempotencyKey: String): AppResult<Unit> = safeApiCall {
        if (fields.isEmpty() || BillingRules.disallowedKeys(fields, BillingRules.UPDATABLE_INVOICE_FIELDS).isNotEmpty()) {
            throw Exception(BillingRules.ERR_INVOICE_FIELD_NOT_UPDATABLE)
        }
        val data = fields.toMutableMap()
        data["updatedAt"] = FieldValue.serverTimestamp()
        data["idempotencyKey"] = idempotencyKey
        val batch = firestore.batch()
        batch.update(invoiceCollection.document(invoiceId), data)
        batch.set(
            eventLogCollection.document("${idempotencyKey}_INVOICE_UPDATED"),
            eventFields(invoiceId, "INVOICE_UPDATED", mapOf("fields" to fields.keys.sorted().joinToString(",")))
        )
        batch.commit().await()
    }

    /**
     * إلغاء فاتورة لا مدفوع عليها. فاتورة عليها مدفوع تُرفض (حتى مع force) لأن إلغاءها كان يترك دفعاتها فعّالة
     * ورصيد المالك بلا إعادة؛ المسار المعتمد إبطال/استرداد الدفعات أولاً. إعادة المحاولة بعد نجاح سابق تنجح بلا أثر.
     */
    suspend fun cancelInvoice(invoiceId: String, force: Boolean, resolution: String?, idempotencyKey: String): AppResult<Unit> = safeApiCall {
        firestore.runTransaction { transaction ->
            val invoiceRef = invoiceCollection.document(invoiceId)
            val snap = transaction.get(invoiceRef)
            if (!snap.exists()) throw Exception(BillingRules.ERR_INVOICE_NOT_FOUND)
            val status = snap.getString("status").toEnumOrNull<InvoiceStatus>()
            if (status == InvoiceStatus.CANCELED) return@runTransaction Unit

            BillingRules.checkCancel(status, snap.getLong("amountPaidMinor") ?: 0L)?.let { throw Exception(it) }

            transaction.update(invoiceRef, mapOf(
                "status" to InvoiceStatus.CANCELED.name.lowercase(),
                "isPaid" to false,
                "idempotencyKey" to idempotencyKey,
                "cancelForce" to force,
                "resolution" to resolution,
                "updatedAt" to FieldValue.serverTimestamp()
            ))
            logEvent(transaction, invoiceId, "INVOICE_CANCELED", mapOf("resolution" to resolution), idempotencyKey)
            Unit
        }.await()
    }

    /** إعادة فتح فاتورة ملغاة فقط (كانت تكتب status=draft على أي فاتورة حتى المدفوعة). */
    suspend fun reopenInvoice(invoiceId: String, idempotencyKey: String): AppResult<Unit> = safeApiCall {
        firestore.runTransaction { transaction ->
            val invoiceRef = invoiceCollection.document(invoiceId)
            val snap = transaction.get(invoiceRef)
            if (!snap.exists()) throw Exception(BillingRules.ERR_INVOICE_NOT_FOUND)
            val status = snap.getString("status").toEnumOrNull<InvoiceStatus>()
            if (status != InvoiceStatus.CANCELED) {
                if (snap.getString("idempotencyKey") == idempotencyKey) return@runTransaction Unit
                throw Exception(BillingRules.ERR_INVOICE_NOT_CANCELED)
            }
            val total = snap.getLong("totalMinor") ?: 0L
            val paid = snap.getLong("amountPaidMinor") ?: 0L
            val reopened = BillingRules.reopenStatus(total, paid)
            transaction.update(invoiceRef, mapOf(
                "status" to reopened.name.lowercase(),
                "isPaid" to (reopened == InvoiceStatus.PAID),
                "idempotencyKey" to idempotencyKey,
                "updatedAt" to FieldValue.serverTimestamp()
            ))
            logEvent(transaction, invoiceId, "INVOICE_REOPENED", mapOf("status" to reopened.name.lowercase()), idempotencyKey)
            Unit
        }.await()
    }

    /**
     * خصم (ثابت/نسبة) على فاتورة تقبل الدفع: يُرفض على المدفوعة/الملغاة، وعلى قيمة غير صالحة، وعلى إجمالٍ جديد أقل من
     * المدفوع فعلاً. الحالة تُعاد حسابها (خصم يغطي المتبقي يسدّد الفاتورة). المفتاح المكرر لا يعيد التطبيق.
     */
    suspend fun applyInvoiceDiscount(invoiceId: String, type: String, value: Long, idempotencyKey: String): AppResult<Unit> = safeApiCall {
        firestore.runTransaction { transaction ->
            val invoiceRef = invoiceCollection.document(invoiceId)
            val snap = transaction.get(invoiceRef)
            if (!snap.exists()) throw Exception(BillingRules.ERR_INVOICE_NOT_FOUND)
            if (snap.getString("idempotencyKey") == idempotencyKey) return@runTransaction Unit

            val status = snap.getString("status").toEnumOrNull<InvoiceStatus>()
            val (error, result) = BillingRules.applyDiscount(
                status = status,
                subtotalMinor = snap.getLong("subtotalMinor") ?: 0L,
                paidMinor = snap.getLong("amountPaidMinor") ?: 0L,
                type = type,
                value = value
            )
            if (error != null || result == null) throw Exception(error ?: BillingRules.ERR_DISCOUNT_INVALID)

            transaction.update(invoiceRef, mapOf(
                "discountType" to type,
                "discountValueMinor" to value,
                "totalMinor" to result.totalMinor,
                "amountDueMinor" to result.dueMinor,
                "status" to result.amounts.statusName,
                "isPaid" to result.amounts.isPaid,
                "idempotencyKey" to idempotencyKey,
                "updatedAt" to FieldValue.serverTimestamp()
            ))
            logEvent(
                transaction, invoiceId, "DISCOUNT_APPLIED",
                mapOf("type" to type, "value" to value, "totalMinor" to result.totalMinor), idempotencyKey
            )
            Unit
        }.await()
    }

    fun listInvoiceEvents(invoiceId: String): Flow<List<InvoiceEventLog>> {
        return eventLogCollection
            .whereEqualTo("invoiceId", invoiceId)
            .orderBy("occurredAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.documents.map { doc ->
                    val rawDetail = doc.get("detail") as? Map<String, Any?> ?: emptyMap()
                    InvoiceEventLog(
                        eventId = doc.id,
                        invoiceId = invoiceId,
                        eventType = doc.getString("eventType") ?: "",
                        detail = rawDetail.mapValues { it.value?.toString() ?: "" },
                        performedBy = doc.getString("performedBy") ?: "",
                        occurredAt = doc.getTimestamp("occurredAt")?.toDate()?.toSortableIso() ?: ""
                    )
                }
            }
    }

    suspend fun getInvoiceForOrder(orderId: String): AppResult<Invoice?> = safeApiCall {
        val snap = invoiceCollection.whereEqualTo("orderId", orderId).limit(1).get().await()
        if (snap.isEmpty) null else mapper.invoice(snap.documents.first())
    }

    /**
     * تسجيل دفعة في معاملة واحدة. يرفض: مبلغاً ≤ 0، فاتورة غير موجودة/ملغاة/مسدّدة، ومبلغاً يتجاوز المتبقي
     * (كان يُقبل ويجعل المتبقي سالباً). معرّف الدفعة = مفتاح العملية فتكرار الإرسال يعيد الدفعة نفسها بلا أثر ثانٍ.
     */
    suspend fun recordPayment(
        invoiceId: String,
        amountMinor: Long,
        method: String,
        reference: String?,
        notes: String?,
        idempotencyKey: String
    ): AppResult<Payment> = safeApiCall {
        // «credit» تخصم محفظة المالك: لا تُنشأ إلا عبر applyCreditToInvoice وإلا صارت دفعة تُسدّد الفاتورة بلا خصم من الرصيد
        if (method.trim().equals(OwnerCreditRules.METHOD_CREDIT, ignoreCase = true)) {
            throw Exception(OwnerCreditRules.ERR_CREDIT_METHOD_RESERVED)
        }
        if (amountMinor <= 0) throw Exception(OwnerCreditRules.ERR_INVALID_AMOUNT)
        firestore.runTransaction { transaction ->
            val invoiceRef = invoiceCollection.document(invoiceId)
            val paymentRef = paymentCollection.document(idempotencyKey)
            val invoiceSnap = transaction.get(invoiceRef)
            val existing = transaction.get(paymentRef)

            if (existing.exists()) {
                return@runTransaction mapper.payment(existing)
                    ?: throw Exception("Existing payment ${existing.id} has a missing or unknown status")
            }

            val status = invoiceSnap.getString("status").toEnumOrNull<InvoiceStatus>()
            val currentPaid = invoiceSnap.getLong("amountPaidMinor") ?: 0L
            val total = invoiceSnap.getLong("totalMinor") ?: 0L
            BillingRules.checkRecordPayment(invoiceSnap.exists(), status, total, currentPaid, amountMinor)?.let { throw Exception(it) }

            val after = OwnerCreditRules.afterPayment(total, currentPaid, amountMinor)

            val paymentData = hashMapOf(
                "id" to idempotencyKey,
                "invoiceId" to invoiceId,
                "amountMinor" to amountMinor,
                "method" to method,
                "reference" to reference,
                "notes" to notes,
                "status" to PaymentStatus.ACTIVE.name.lowercase(),
                "idempotencyKey" to idempotencyKey,
                "createdAt" to FieldValue.serverTimestamp(),
                "ownerId" to (invoiceSnap.getString("ownerId") ?: "")
            )

            transaction.set(paymentRef, paymentData)
            transaction.update(invoiceRef, mapOf(
                "amountPaidMinor" to after.paidMinor,
                "amountDueMinor" to after.dueMinor,
                "status" to after.statusName,
                "isPaid" to after.isPaid,
                "updatedAt" to FieldValue.serverTimestamp()
            ))
            logEvent(
                transaction, invoiceId, "PAYMENT_RECORDED",
                mapOf("paymentId" to idempotencyKey, "amountMinor" to amountMinor, "method" to method), idempotencyKey
            )

            Payment(
                id = idempotencyKey,
                invoiceId = invoiceId,
                amountMinor = amountMinor,
                method = method,
                reference = reference,
                notes = notes,
                status = PaymentStatus.ACTIVE,
                createdAt = "",
                ownerId = invoiceSnap.getString("ownerId") ?: ""
            )
        }.await()
    }

    /**
     * تسوية (كلها أو بعضها) فاتورة مالك من رصيده المدفوع مقدماً، في معاملة واحدة ذرّية:
     * دفعة method=credit + تحديث الفاتورة + خصم الرصيد + قيد INVOICE_CHARGE. مفتاح العملية = معرّف الدفعة والقيد،
     * فإعادة المحاولة بعد نجاح سابق لا تخصم مرتين. يرفض: فاتورة بلا مالك/غير قابلة للدفع، مبلغاً > المستحق، أو > الرصيد.
     */
    suspend fun applyCreditToInvoice(invoiceId: String, amountMinor: Long, idempotencyKey: String): AppResult<Unit> = safeApiCall {
        firestore.runTransaction { tx ->
            val paymentRef = paymentCollection.document(idempotencyKey)
            val ledgerRef = ledgerCollection.document(idempotencyKey)
            val invoiceRef = invoiceCollection.document(invoiceId)

            if (tx.get(paymentRef).exists()) return@runTransaction Unit

            val invoiceSnap = tx.get(invoiceRef)
            if (!invoiceSnap.exists()) throw Exception(OwnerCreditRules.ERR_INVOICE_NOT_PAYABLE)
            val ownerId = invoiceSnap.getString("ownerId")
            if (ownerId.isNullOrBlank()) throw Exception(OwnerCreditRules.ERR_INVOICE_NOT_PAYABLE)
            val ownerRef = ownerCollection.document(ownerId)
            val ownerSnap = tx.get(ownerRef)
            if (!ownerSnap.exists()) throw Exception(OwnerCreditRules.ERR_OWNER_NOT_FOUND)

            val status = invoiceSnap.getString("status").toEnumOrNull<InvoiceStatus>()
            val total = invoiceSnap.getLong("totalMinor") ?: 0L
            val currentPaid = invoiceSnap.getLong("amountPaidMinor") ?: 0L
            val credit = ownerSnap.getLong("availableCreditMinor") ?: 0L

            OwnerCreditRules.checkApply(
                invoiceOwnerId = ownerId,
                invoiceAcceptsPayment = status?.acceptsPayment == true,
                amountDueMinor = total - currentPaid,
                availableCreditMinor = credit,
                amountMinor = amountMinor
            )?.let { throw Exception(it) }

            val after = OwnerCreditRules.afterPayment(total, currentPaid, amountMinor)

            tx.set(paymentRef, hashMapOf(
                "id" to idempotencyKey,
                "invoiceId" to invoiceId,
                "amountMinor" to amountMinor,
                "method" to OwnerCreditRules.METHOD_CREDIT,
                "reference" to "Owner Balance Settlement",
                "notes" to null,
                "status" to PaymentStatus.ACTIVE.name.lowercase(),
                "idempotencyKey" to idempotencyKey,
                "createdAt" to FieldValue.serverTimestamp(),
                "ownerId" to ownerId
            ))
            tx.update(invoiceRef, mapOf(
                "amountPaidMinor" to after.paidMinor,
                "amountDueMinor" to after.dueMinor,
                "status" to after.statusName,
                "isPaid" to after.isPaid,
                "updatedAt" to FieldValue.serverTimestamp()
            ))
            tx.update(ownerRef, "availableCreditMinor", OwnerCreditRules.creditAfterCharge(credit, amountMinor))
            tx.set(ledgerRef, hashMapOf(
                "ownerId" to ownerId,
                "type" to OwnerLedgerType.INVOICE_CHARGE.name,
                "amountMinor" to -amountMinor,
                "relatedInvoiceId" to invoiceId,
                "paymentId" to idempotencyKey,
                "idempotencyKey" to idempotencyKey,
                "createdAt" to FieldValue.serverTimestamp()
            ))
            logEvent(
                tx, invoiceId, "CREDIT_APPLIED",
                mapOf("paymentId" to idempotencyKey, "amountMinor" to amountMinor), idempotencyKey
            )
            Unit
        }.await()
    }

    /**
     * إعادة [amountMinor] إلى رصيد المالك + قيد CHARGE_REVERSAL (مفتاحه مفتاح العملية الحالية).
     * تُستدعى داخل المعاملة بعد إنهاء كل القراءات؛ لا تفعل شيئاً إن لم يوجد المالك.
     */
    private fun restoreCredit(
        tx: com.google.firebase.firestore.Transaction,
        ownerSnap: com.google.firebase.firestore.DocumentSnapshot?,
        invoiceId: String,
        paymentId: String,
        amountMinor: Long,
        idempotencyKey: String
    ) {
        if (ownerSnap == null || !ownerSnap.exists() || amountMinor <= 0) return
        val credit = ownerSnap.getLong("availableCreditMinor") ?: 0L
        tx.update(ownerSnap.reference, "availableCreditMinor", OwnerCreditRules.creditAfterRestore(credit, amountMinor))
        tx.set(ledgerCollection.document(idempotencyKey), hashMapOf(
            "ownerId" to ownerSnap.id,
            "type" to OwnerLedgerType.CHARGE_REVERSAL.name,
            "amountMinor" to amountMinor,
            "relatedInvoiceId" to invoiceId,
            "paymentId" to paymentId,
            "idempotencyKey" to idempotencyKey,
            "createdAt" to FieldValue.serverTimestamp()
        ))
    }

    /**
     * دفعة ملغاة/مستردة سابقاً: إن كانت العملية نفسها (إعادة محاولة بعد نجاح) فلا أثر ثانٍ ونجاح صامت،
     * وإن كانت عملية مختلفة فيُرفض صراحةً (كان يعود «نجاحاً» وهمياً لإبطال لم يحدث).
     */
    private fun isRetryOfReversal(paymentSnap: com.google.firebase.firestore.DocumentSnapshot, idempotencyKey: String): Boolean =
        paymentSnap.getString("reversalKey") == idempotencyKey ||
            (paymentSnap.getString("reversalKey") == null && paymentSnap.getString("idempotencyKey") == idempotencyKey && paymentSnap.id != idempotencyKey)

    private fun paymentStatusOf(paymentSnap: com.google.firebase.firestore.DocumentSnapshot): PaymentStatus =
        paymentSnap.getString("status").toEnumOrNull<PaymentStatus>() ?: throw Exception(BillingRules.ERR_PAYMENT_NOT_FOUND)

    /** إبطال دفعة فعّالة: يعيد مبلغها كاملاً من الفاتورة، ويُبقي الفاتورة الملغاة ملغاة، ويعيد رصيد المالك لدفعة credit. */
    suspend fun voidPayment(paymentId: String, reason: String, idempotencyKey: String): AppResult<Unit> = safeApiCall {
        firestore.runTransaction { transaction ->
            val paymentRef = paymentCollection.document(paymentId)
            val paymentSnap = transaction.get(paymentRef)
            if (!paymentSnap.exists()) throw Exception(BillingRules.ERR_PAYMENT_NOT_FOUND)
            if (BillingRules.isReversed(paymentStatusOf(paymentSnap))) {
                if (isRetryOfReversal(paymentSnap, idempotencyKey)) return@runTransaction Unit
                throw Exception(BillingRules.ERR_PAYMENT_ALREADY_REVERSED)
            }

            val invoiceId = paymentSnap.getString("invoiceId")?.takeIf { it.isNotBlank() } ?: throw Exception(BillingRules.ERR_INVOICE_NOT_FOUND)
            val amount = paymentSnap.getLong("amountMinor") ?: 0L
            val invoiceRef = invoiceCollection.document(invoiceId)
            val invoiceSnap = transaction.get(invoiceRef)
            if (!invoiceSnap.exists()) throw Exception(BillingRules.ERR_INVOICE_NOT_FOUND)

            // دفعة credit: الرصيد يعود لصاحبه (القراءة قبل أي كتابة)
            val isCredit = paymentSnap.getString("method").orEmpty().equals(OwnerCreditRules.METHOD_CREDIT, ignoreCase = true)
            val ownerId = paymentSnap.getString("ownerId")
            val ownerSnap = if (isCredit && !ownerId.isNullOrBlank()) transaction.get(ownerCollection.document(ownerId)) else null

            val after = BillingRules.afterReversal(
                current = invoiceSnap.getString("status").toEnumOrNull<InvoiceStatus>(),
                totalMinor = invoiceSnap.getLong("totalMinor") ?: 0L,
                currentPaidMinor = invoiceSnap.getLong("amountPaidMinor") ?: 0L,
                reversedMinor = amount
            )

            transaction.update(paymentRef, mapOf(
                "status" to PaymentStatus.VOID.name.lowercase(),
                "voidReason" to reason,
                "reversalKey" to idempotencyKey,
                "updatedAt" to FieldValue.serverTimestamp()
            ))
            transaction.update(invoiceRef, mapOf(
                "amountPaidMinor" to after.paidMinor,
                "amountDueMinor" to after.dueMinor,
                "status" to after.statusName,
                "isPaid" to after.isPaid,
                "updatedAt" to FieldValue.serverTimestamp()
            ))

            if (isCredit) restoreCredit(transaction, ownerSnap, invoiceId, paymentId, amount, idempotencyKey)
            logEvent(
                transaction, invoiceId, "PAYMENT_VOIDED",
                mapOf("paymentId" to paymentId, "amountMinor" to amount, "reason" to reason), idempotencyKey
            )
            Unit
        }.await()
    }

    /**
     * استرداد من دفعة فعّالة: المبلغ ≤ مبلغ الدفعة (كان الأكبر يُقبل ويجعل المدفوع سالباً)، وتصير الدفعة REFUNDED
     * مع المبلغ المسترد فيُحسب الصافي المحصَّل في الإجماليات. استرداد جزئي يُغلق الدفعة (لا استرداد ثانٍ عليها).
     */
    suspend fun refundPayment(paymentId: String, refundAmountMinor: Long, idempotencyKey: String): AppResult<Unit> = safeApiCall {
        if (refundAmountMinor <= 0) throw Exception(OwnerCreditRules.ERR_INVALID_AMOUNT)
        firestore.runTransaction { transaction ->
            val paymentRef = paymentCollection.document(paymentId)
            val paymentSnap = transaction.get(paymentRef)
            if (!paymentSnap.exists()) throw Exception(BillingRules.ERR_PAYMENT_NOT_FOUND)
            if (BillingRules.isReversed(paymentStatusOf(paymentSnap))) {
                if (isRetryOfReversal(paymentSnap, idempotencyKey)) return@runTransaction Unit
                throw Exception(BillingRules.ERR_PAYMENT_ALREADY_REVERSED)
            }

            val paymentAmount = paymentSnap.getLong("amountMinor") ?: 0L
            BillingRules.checkRefund(paymentAmount, refundAmountMinor)?.let { throw Exception(it) }

            val invoiceId = paymentSnap.getString("invoiceId")?.takeIf { it.isNotBlank() } ?: throw Exception(BillingRules.ERR_INVOICE_NOT_FOUND)
            val invoiceRef = invoiceCollection.document(invoiceId)
            val invoiceSnap = transaction.get(invoiceRef)
            if (!invoiceSnap.exists()) throw Exception(BillingRules.ERR_INVOICE_NOT_FOUND)

            val isCredit = paymentSnap.getString("method").orEmpty().equals(OwnerCreditRules.METHOD_CREDIT, ignoreCase = true)
            val ownerId = paymentSnap.getString("ownerId")
            val ownerSnap = if (isCredit && !ownerId.isNullOrBlank()) transaction.get(ownerCollection.document(ownerId)) else null

            val after = BillingRules.afterReversal(
                current = invoiceSnap.getString("status").toEnumOrNull<InvoiceStatus>(),
                totalMinor = invoiceSnap.getLong("totalMinor") ?: 0L,
                currentPaidMinor = invoiceSnap.getLong("amountPaidMinor") ?: 0L,
                reversedMinor = refundAmountMinor
            )

            transaction.update(paymentRef, mapOf(
                "status" to PaymentStatus.REFUNDED.name.lowercase(),
                "refundAmountMinor" to refundAmountMinor,
                "reversalKey" to idempotencyKey,
                "updatedAt" to FieldValue.serverTimestamp()
            ))
            transaction.update(invoiceRef, mapOf(
                "amountPaidMinor" to after.paidMinor,
                "amountDueMinor" to after.dueMinor,
                "status" to after.statusName,
                "isPaid" to after.isPaid,
                "updatedAt" to FieldValue.serverTimestamp()
            ))

            // استرداد دفعة credit يعيد للرصيد ما استُرد فعلاً
            if (isCredit) restoreCredit(transaction, ownerSnap, invoiceId, paymentId, refundAmountMinor, idempotencyKey)
            logEvent(
                transaction, invoiceId, "PAYMENT_REFUNDED",
                mapOf("paymentId" to paymentId, "refundMinor" to refundAmountMinor), idempotencyKey
            )
            Unit
        }.await()
    }

    /** تعديل ملاحظات/مرجع الدفعة فقط؛ المبلغ والحالة والفاتورة لا تتغير إلا بإبطال/استرداد صريح. */
    suspend fun updatePayment(paymentId: String, fields: Map<String, Any?>, idempotencyKey: String): AppResult<Unit> = safeApiCall {
        if (fields.isEmpty() || BillingRules.disallowedKeys(fields, BillingRules.UPDATABLE_PAYMENT_FIELDS).isNotEmpty()) {
            throw Exception(BillingRules.ERR_PAYMENT_FIELD_NOT_UPDATABLE)
        }
        val data = fields.toMutableMap()
        data["updatedAt"] = FieldValue.serverTimestamp()
        data["idempotencyKey"] = idempotencyKey
        paymentCollection.document(paymentId).update(data).await()
    }

    /** دفعات فاتورة واحدة (الأحدث أولاً). الترتيب هنا لا في الاستعلام لتفادي فهرس مركّب إضافي. */
    fun listInvoicePayments(invoiceId: String): Flow<List<Payment>> =
        paymentCollection.whereEqualTo("invoiceId", invoiceId).snapshots().map { snapshot ->
            snapshot.documents.mapNotNull { doc -> mapper.payment(doc) }.sortedByDescending { it.createdAt }
        }

    fun listAllPayments(ownerId: String?, dateRange: Pair<String, String>?): Flow<List<Payment>> {
        var query: Query = paymentCollection.orderBy("createdAt", Query.Direction.DESCENDING)
        ownerId?.let { query = query.whereEqualTo("ownerId", it) }
        
        return query.snapshots().map { snapshot ->
            val payments = snapshot.documents.mapNotNull { doc -> mapper.payment(doc) }
            // dateRange كان معامَلاً مُتجاهَلاً؛ الترشيح هنا (لا في الاستعلام) لتفادي فهرس مركّب إضافي
            if (dateRange == null) payments
            else payments.filter { ps.mgtik.hotspot.core.util.isWithinDateRange(it.createdAt, dateRange.first, dateRange.second) }
        }
    }

    fun getBillingSettings(): Flow<BillingSettings?> {
        return settingsDoc.snapshots().map { doc ->
            if (doc.exists()) {
                BillingSettings(
                    companyName = doc.getString("companyName") ?: "",
                    companyLogoUrl = doc.getString("companyLogoUrl"),
                    invoiceFooterNote = doc.getString("invoiceFooterNote"),
                    acceptedPaymentMethods = doc.get("acceptedPaymentMethods") as? List<String> ?: emptyList(),
                    updatedAt = doc.getTimestamp("updatedAt")?.toDate()?.toSortableIso() ?: ""
                )
            } else null
        }
    }

    suspend fun updateBillingSettings(fields: Map<String, Any?>, idempotencyKey: String): AppResult<Unit> = safeApiCall {
        val data = fields.toMutableMap()
        data["updatedAt"] = FieldValue.serverTimestamp()
        data["idempotencyKey"] = idempotencyKey
        settingsDoc.set(data, com.google.firebase.firestore.SetOptions.merge()).await()
    }

    /**
     * إجماليات لوحة الملخص مشتقة من الفواتير والدفعات الفعلية. كانت تُقرأ من مستند analytics/financial_summary
     * ولا أحد في التطبيق يكتبه، فتظهر الأصفار دائماً (المحصَّل والمستحق والمفوتر).
     */
    fun observeTotals(): Flow<BillingRules.Totals> =
        combine(invoiceCollection.snapshots(), paymentCollection.snapshots()) { invoiceSnap, paymentSnap ->
            BillingRules.totals(
                invoices = invoiceSnap.documents.mapNotNull { mapper.invoice(it) },
                payments = paymentSnap.documents.mapNotNull { mapper.payment(it) }
            )
        }
}

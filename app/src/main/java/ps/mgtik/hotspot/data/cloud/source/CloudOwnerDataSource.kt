package ps.mgtik.hotspot.data.cloud.source

import ps.mgtik.hotspot.core.util.toSortableIso
import ps.mgtik.hotspot.data.mapping.SyncErrorReporter
import ps.mgtik.hotspot.data.mapping.toEnumOrNull
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.safeApiCall
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.usecase.OwnerCreditRules
import ps.mgtik.hotspot.domain.usecase.OwnerInputValidator
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CloudOwnerDataSource - تنفيذ العمليات السحابية لحسابات الملاك والموزعين (§4.18)
 */
@Singleton
class CloudOwnerDataSource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val mapper: BillingDocMapper,
    private val reporter: SyncErrorReporter
) {
    private val ownersCollection = firestore.collection("owners")
    private val ledgerCollection = firestore.collection("owner_ledger")
    private val invoiceCollection = firestore.collection("invoices")
    private val paymentCollection = firestore.collection("payments")

    private companion object {
        /** سقف دفعات الملف المالي المعروضة (الأحدث أولاً)؛ الفواتير تُحمَّل كاملة لأن عدّ المستحقة يعتمد عليها. */
        const val PAYMENTS_LIMIT = 300L
        const val TAG = "CloudOwnerDataSource"
    }

    fun getOwnersSummary(): Flow<List<OwnerAccount>> {
        return ownersCollection.orderBy("name", Query.Direction.ASCENDING).snapshots().map { snapshot ->
            snapshot.documents.mapNotNull { doc -> mapToOwnerAccount(doc) }
        }
    }

    fun getOwnerAccount(ownerId: String): Flow<OwnerAccount?> {
        return ownersCollection.document(ownerId).snapshots().map { doc ->
            if (doc.exists()) mapToOwnerAccount(doc) else null
        }
    }

    /**
     * الرصيد الافتتاحي: يُضاف للرصيد القائم (لا يستبدله فتُمحى دفعات سبقته) ويُسجَّل قيد OPENING_BALANCE في الدفتر،
     * ومفتاح العملية = معرّف القيد فإعادة المحاولة بعد نجاح سابق لا تفشل بـ «مضبوط مسبقاً». يُضبط مرة واحدة لكل مالك.
     */
    suspend fun setOwnerOpeningBalance(ownerId: String, amountMinor: Long, idempotencyKey: String): AppResult<Unit> = safeApiCall {
        firestore.runTransaction { transaction ->
            val ref = ownersCollection.document(ownerId)
            val ledgerRef = ledgerCollection.document(idempotencyKey)
            if (transaction.get(ledgerRef).exists()) return@runTransaction Unit

            val snap = transaction.get(ref)
            if (!snap.exists()) throw Exception(OwnerCreditRules.ERR_OWNER_NOT_FOUND)
            if (snap.contains("openingBalanceMinor")) {
                throw Exception("CLOUD_OPENING_BALANCE_ALREADY_SET")
            }
            val credit = snap.getLong("availableCreditMinor") ?: 0L
            transaction.update(ref, mapOf(
                "openingBalanceMinor" to amountMinor,
                "availableCreditMinor" to OwnerCreditRules.creditAfterOpening(credit, amountMinor),
                "openingBalanceSetAt" to FieldValue.serverTimestamp(),
                "idempotencyKey" to idempotencyKey
            ))
            transaction.set(ledgerRef, hashMapOf(
                "ownerId" to ownerId,
                "type" to OwnerLedgerType.OPENING_BALANCE.name,
                "amountMinor" to amountMinor,
                "idempotencyKey" to idempotencyKey,
                "createdAt" to FieldValue.serverTimestamp()
            ))
            Unit
        }.await()
    }

    suspend fun recordOwnerAdvance(ownerId: String, amountMinor: Long, notes: String?, idempotencyKey: String): AppResult<Unit> = safeApiCall {
        firestore.runTransaction { transaction ->
            val ownerRef = ownersCollection.document(ownerId)
            val ledgerRef = ledgerCollection.document(idempotencyKey)
            
            if (transaction.get(ledgerRef).exists()) return@runTransaction Unit

            val ownerSnap = transaction.get(ownerRef)
            if (!ownerSnap.exists()) throw Exception(OwnerCreditRules.ERR_OWNER_NOT_FOUND)
            if (amountMinor <= 0) throw Exception(OwnerCreditRules.ERR_INVALID_AMOUNT)
            val currentCredit = ownerSnap.getLong("availableCreditMinor") ?: 0L
            
            val ledgerData = hashMapOf(
                "ownerId" to ownerId,
                "type" to OwnerLedgerType.ADVANCE.name,
                "amountMinor" to amountMinor,
                "notes" to notes,
                "idempotencyKey" to idempotencyKey,
                "createdAt" to FieldValue.serverTimestamp()
            )
            
            transaction.set(ledgerRef, ledgerData)
            transaction.update(ownerRef, "availableCreditMinor", currentCredit + amountMinor)
            Unit
        }.await()
    }

    suspend fun refundFromOwnerCredit(ownerId: String, amountMinor: Long, idempotencyKey: String): AppResult<Unit> = safeApiCall {
        firestore.runTransaction { transaction ->
            val ownerRef = ownersCollection.document(ownerId)
            val ledgerRef = ledgerCollection.document(idempotencyKey)
            
            if (transaction.get(ledgerRef).exists()) return@runTransaction Unit

            val ownerSnap = transaction.get(ownerRef)
            if (!ownerSnap.exists()) throw Exception(OwnerCreditRules.ERR_OWNER_NOT_FOUND)
            if (amountMinor <= 0) throw Exception(OwnerCreditRules.ERR_INVALID_AMOUNT)
            val currentCredit = ownerSnap.getLong("availableCreditMinor") ?: 0L
            
            if (currentCredit < amountMinor) {
                throw Exception("ERR_INSUFFICIENT_CREDIT")
            }

            val ledgerData = hashMapOf(
                "ownerId" to ownerId,
                "type" to OwnerLedgerType.REFUND_FROM_CREDIT.name,
                "amountMinor" to -amountMinor,
                "idempotencyKey" to idempotencyKey,
                "createdAt" to FieldValue.serverTimestamp()
            )
            
            transaction.set(ledgerRef, ledgerData)
            transaction.update(ownerRef, "availableCreditMinor", currentCredit - amountMinor)
            Unit
        }.await()
    }

    fun getOwnerWorkspace(ownerId: String, dateRange: Pair<String, String>?): Flow<OwnerWorkspace?> {
        val accountFlow = getOwnerAccount(ownerId)
        // كل فواتير المالك (لا آخر 10 فقط): عدد المستحقة يُشتق منها بدل حقل مخزَّن لم يكتبه أحد
        val invoicesFlow = invoiceCollection.whereEqualTo("ownerId", ownerId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots().map { it.documents.mapNotNull { d -> mapper.invoice(d) } }
            .catch { e -> Log.w(TAG, "owner invoices query failed (missing index?): ${e.message}"); emit(emptyList()) }
        val paymentsFlow = paymentCollection.whereEqualTo("ownerId", ownerId)
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(PAYMENTS_LIMIT)
            .snapshots().map { it.documents.mapNotNull { d -> mapper.payment(d) } }
            .catch { e -> Log.w(TAG, "owner payments query failed (missing index?): ${e.message}"); emit(emptyList()) }
        // فشل استعلام ثانوي (مثل فهرس مركّب غير منشور) لا يمنع فتح ملف المالك: تُعرض القائمة فارغة مع تحذير في السجل
        val ledgerFlow = getOwnerLedger(ownerId)
            .catch { e -> Log.w(TAG, "owner ledger query failed (missing index?): ${e.message}"); emit(emptyList()) }

        return combine(accountFlow, invoicesFlow, paymentsFlow, ledgerFlow) { acc, inv, pay, led ->
            if (acc != null) {
                val outstanding = inv.count { it.status == InvoiceStatus.DUE || it.status == InvoiceStatus.PARTIALLY_PAID }
                OwnerWorkspace(ownerId, acc.copy(outstandingInvoicesCount = outstanding), inv, pay, led)
            } else null
        }
    }

    fun getOwnerLedger(ownerId: String): Flow<List<OwnerLedgerEntry>> {
        return ledgerCollection.whereEqualTo("ownerId", ownerId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots().map { snapshot ->
                snapshot.documents.mapNotNull { doc ->
                    // نوع قيد مجهول لا يتحول إلى ADVANCE (يغيّر الرصيد المعروض): يُرفض القيد ويُسجَّل
                    val rawType = doc.getString("type")
                    val type = rawType.toEnumOrNull<OwnerLedgerType>()
                    if (type == null) {
                        reporter.rejected("owner_ledger", doc.id, if (rawType.isNullOrBlank()) "missing type" else "unknown type '$rawType'")
                        return@mapNotNull null
                    }
                    OwnerLedgerEntry(
                        ledgerEntryId = doc.id,
                        ownerId = ownerId,
                        type = type,
                        amountMinor = doc.getLong("amountMinor") ?: 0L,
                        relatedInvoiceId = doc.getString("relatedInvoiceId"),
                        createdAt = doc.getTimestamp("createdAt")?.toDate()?.toSortableIso() ?: "",
                        note = doc.getString("notes").orEmpty()
                    )
                }
            }
    }

    suspend fun saveOwner(account: OwnerAccount): AppResult<Unit> = safeApiCall {
        // رفض تكرار رقم الهاتف (بعد التطبيع، ويشمل المؤرشفين) قبل الإنشاء؛ الفحص هنا هو المرجع النهائي
        val existing = ownersCollection.get().await().documents.map { it.id to (it.getString("phone") ?: "") }
        if (OwnerInputValidator.isPhoneTaken(account.phone, existing, excludeOwnerId = account.ownerId)) {
            throw Exception(OwnerInputValidator.ERR_PHONE_DUPLICATE)
        }
        val ref = ownersCollection.document(account.ownerId.ifBlank { UUID.randomUUID().toString() })
        firestore.runTransaction { tx ->
            if (tx.get(ref).exists()) {
                // مالك قائم: تُحدَّث الهوية فقط. set() الكامل كان يصفّر الرصيد والرصيد الافتتاحي ويمحو تاريخ الإنشاء
                tx.update(ref, mapOf("name" to account.name, "phone" to account.phone, "status" to account.status.name))
            } else {
                tx.set(ref, hashMapOf(
                    "name" to account.name,
                    "phone" to account.phone,
                    "status" to account.status.name,
                    "availableCreditMinor" to 0L,
                    "createdAt" to FieldValue.serverTimestamp()
                ))
            }
            Unit
        }.await()
    }

    /** يعدّل الهوية فقط (name/phone/status). الرصيد وحقوله لا تتغير إلا بقيد دفتر؛ وإلا تجاوز أي تعديل عام الدفتر. */
    suspend fun updateOwner(ownerId: String, fields: Map<String, Any?>): AppResult<Unit> = safeApiCall {
        if (!OwnerCreditRules.UPDATABLE_OWNER_FIELDS.containsAll(fields.keys)) {
            throw Exception(OwnerCreditRules.ERR_OWNER_FIELD_NOT_UPDATABLE)
        }
        OwnerCreditRules.validateIdentityUpdate(fields)?.let { throw Exception(it) }
        (fields["phone"] as? String)?.let { phone ->
            val existing = ownersCollection.get().await().documents.map { it.id to (it.getString("phone") ?: "") }
            if (OwnerInputValidator.isPhoneTaken(phone, existing, excludeOwnerId = ownerId)) {
                throw Exception(OwnerInputValidator.ERR_PHONE_DUPLICATE)
            }
        }
        ownersCollection.document(ownerId).update(fields).await()
    }

    suspend fun deleteOwner(ownerId: String): AppResult<Unit> = safeApiCall {
        // بدلاً من الحذف النهائي الآمن، نقوم بالأرشفة لحماية السجلات المالية
        ownersCollection.document(ownerId).update("status", OwnerStatus.ARCHIVED.name).await()
    }

    /** null (مع SYNC_ERROR) إن كانت status ناقصة/مجهولة: مالك بحالة تالفة لا يصير ACTIVE (قد يكون موقوفاً). */
    private fun mapToOwnerAccount(doc: com.google.firebase.firestore.DocumentSnapshot): OwnerAccount? {
        val rawStatus = doc.getString("status")
        val status = rawStatus.toEnumOrNull<OwnerStatus>()
        if (status == null) {
            reporter.rejected("owners", doc.id, if (rawStatus.isNullOrBlank()) "missing status" else "unknown status '$rawStatus'")
            return null
        }
        return OwnerAccount(
            ownerId = doc.id,
            name = doc.getString("name") ?: "",
            phone = doc.getString("phone") ?: "",
            openingBalanceMinor = doc.getLong("openingBalanceMinor") ?: 0L,
            openingBalanceSetAt = doc.getTimestamp("openingBalanceSetAt")?.toDate()?.toSortableIso() ?: "",
            availableCreditMinor = doc.getLong("availableCreditMinor") ?: 0L,
            outstandingInvoicesCount = doc.getLong("outstandingInvoicesCount")?.toInt() ?: 0,
            status = status,
            createdAt = doc.getTimestamp("createdAt")?.toDate()?.toSortableIso() ?: ""
        )
    }
}

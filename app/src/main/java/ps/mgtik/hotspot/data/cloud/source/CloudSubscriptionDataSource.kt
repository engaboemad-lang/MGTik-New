package ps.mgtik.hotspot.data.cloud.source

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import ps.mgtik.hotspot.core.util.toSortableIso
import ps.mgtik.hotspot.data.mapping.SyncErrorReporter
import ps.mgtik.hotspot.data.mapping.toEnumOrNull
import ps.mgtik.hotspot.data.subscription.RouterObservation
import ps.mgtik.hotspot.core.result.RuleViolation
import ps.mgtik.hotspot.domain.model.PeriodUnit
import ps.mgtik.hotspot.domain.model.ProfileCategory
import ps.mgtik.hotspot.domain.model.SystemClass
import ps.mgtik.hotspot.domain.usecase.OwnerCreditRules
import ps.mgtik.hotspot.domain.usecase.SystemClassRules
import ps.mgtik.hotspot.domain.model.Subscription
import ps.mgtik.hotspot.domain.model.newestFirst
import ps.mgtik.hotspot.domain.model.SubscriptionExecution
import ps.mgtik.hotspot.domain.model.SubscriptionInvoice
import ps.mgtik.hotspot.domain.model.SubscriptionOp
import ps.mgtik.hotspot.domain.model.SubscriptionOperation
import ps.mgtik.hotspot.domain.model.SubscriptionPayment
import ps.mgtik.hotspot.domain.model.SubscriptionStatus
import ps.mgtik.hotspot.domain.subscription.RenewalResult
import ps.mgtik.hotspot.domain.subscription.SubscriberRecordRules
import ps.mgtik.hotspot.domain.subscription.SubscriptionBilling
import ps.mgtik.hotspot.domain.subscription.SubscriptionRules
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * مصدر الاشتراكات السحابي: المرجع الوحيد لبيانات الاشتراك الإدارية والمالية.
 *
 * collections مستقلة تماماً عن الطلبات والملاك:
 *  subscriptions · subscription_invoices · subscription_payments · subscription_ops (سجل أوامر الراوتر).
 *
 * كل تغيير يمرّ بمعاملة Firestore تقرأ الحالة الحالية وتطبّق دالة نقية من SubscriptionRules ثم تكتب،
 * فلا يصح أمر قديم على حالة تغيّرت (مثلاً إيقاف اشتراك جُدِّد).
 * رموز الرفض تُرمى كـ Exception(code) وتُحوَّل إلى ApiError برمزها (انظر ErrorTranslator).
 */
@Singleton
class CloudSubscriptionDataSource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val reporter: SyncErrorReporter
) {
    private val subCollection = firestore.collection("subscriptions")
    private val invoiceCollection = firestore.collection("subscription_invoices")
    private val paymentCollection = firestore.collection("subscription_payments")
    private val opCollection = firestore.collection("subscription_ops")
    private val userCollection = firestore.collection("local_users")
    private val categoryCollection = firestore.collection("profile_categories")

    // ───────────── القراءة ─────────────

    /** من الأحدث إلى الأقدم حسب تاريخ الإنشاء الفعلي (created_at يُكتب مرة واحدة عند الإنشاء ولا يُعدَّل لاحقاً). */
    fun getSubscriptions(): Flow<List<Subscription>> =
        subCollection.snapshots().map { snap ->
            snap.documents.mapNotNull { mapSubscription(it.id, it.data ?: emptyMap()) }.newestFirst()
        }

    fun getInvoices(subscriptionId: String): Flow<List<SubscriptionInvoice>> =
        invoiceCollection.whereEqualTo("subscription_id", subscriptionId).snapshots().map { snap ->
            snap.documents.map { mapInvoice(it.id, it.data ?: emptyMap()) }.sortedByDescending { it.periodNumber }
        }

    fun getPayments(subscriptionId: String): Flow<List<SubscriptionPayment>> =
        paymentCollection.whereEqualTo("subscription_id", subscriptionId).snapshots().map { snap ->
            snap.documents.map { mapPayment(it.id, it.data ?: emptyMap()) }.sortedByDescending { it.createdAt }
        }

    /** كل دفعات الاشتراكات (للتجميع حسب الفترة في لوحة Billing)؛ مستمع واحد للمجموعة. */
    fun getAllPayments(): Flow<List<SubscriptionPayment>> =
        paymentCollection.snapshots().map { snap -> snap.documents.map { mapPayment(it.id, it.data ?: emptyMap()) } }

    fun getOperations(subscriptionId: String): Flow<List<SubscriptionOperation>> =
        opCollection.whereEqualTo("subscription_id", subscriptionId).snapshots().map { snap ->
            snap.documents.mapNotNull { mapOp(it.id, it.data ?: emptyMap()) }.sortedByDescending { it.requestedAt }
        }

    suspend fun readAll(): List<Subscription> =
        subCollection.get().await().documents.mapNotNull { mapSubscription(it.id, it.data ?: emptyMap()) }

    suspend fun read(id: String): Subscription? {
        val doc = subCollection.document(id).get().await()
        return if (doc.exists()) mapSubscription(doc.id, doc.data ?: emptyMap()) else null
    }

    /** اشتراك قائم (غير ملغى) بنفس اسم الدخول؟ يمنع سجلين إداريين على حساب المستخدم واحد. */
    suspend fun usernameInUse(username: String): Boolean =
        subCollection.whereEqualTo("customer_username", username).get().await().documents
            .any { it.getString("status")?.uppercase() != SubscriptionStatus.CANCELLED.name }

    // ───────────── الإنشاء ─────────────

    /** إنشاء الاشتراك وفاتورة فترته الأولى معاً. معرّف المستند = مفتاح العملية: إعادة المحاولة تُرجع الموجود دون تكرار. */
    suspend fun create(sub: Subscription, invoice: SubscriptionInvoice): Subscription =
        firestore.runTransaction { tx ->
            val ref = subCollection.document(sub.id)
            val existing = tx.get(ref)
            if (existing.exists()) {
                return@runTransaction mapSubscription(existing.id, existing.data ?: emptyMap()) ?: sub
            }
            // وكيل موقوف/مؤرشف/محذوف لا يُنشأ باسمه اشتراك جديد (القراءة قبل أي كتابة).
            if (sub.ownerId.isNotBlank()) {
                val ownerSnap = tx.get(firestore.collection("owners").document(sub.ownerId))
                OwnerCreditRules.checkOwnerCanOperate(ownerSnap.exists(), ownerSnap.getString("status"))
                    ?.let { throw RuleViolation(it, "Owner cannot take new operations") }
            }
            // علامة استخدام الباقة (تمنع حذفها بالتوازي مع إنشاء الاشتراك). القراءة قبل أي كتابة.
            val profileSnap = sub.profileId.takeIf { it.isNotBlank() }
                ?.let { tx.get(firestore.collection("profiles").document(it)) }
            // الاشتراك لا يُنشأ إلا لباقة فئتها مصنّفة SUBSCRIPTION صراحةً (لا تخمين للفئات القديمة)
            if (profileSnap == null || !profileSnap.exists()) throw Exception(SubscriptionRules.ERR_PROFILE_REQUIRED)
            val catId = profileSnap.getString("category_id")
            val catSnap = catId?.let { tx.get(categoryCollection.document(it)) }
            SystemClassRules.checkProfileFor(
                SystemClass.SUBSCRIPTION,
                hasCategoryId = catId != null,
                category = catSnap?.takeIf { it.exists() }?.let {
                    ProfileCategory(id = it.id, systemClass = SystemClass.fromWire(it.getString("system_class")))
                }
            )?.let { throw RuleViolation(it, "Profile category is not a SUBSCRIPTION category") }
            val now = Timestamp.now()
            if (profileSnap.getBoolean("in_use") != true) {
                tx.update(profileSnap.reference, "in_use", true)
            }
            tx.set(ref, fieldsOf(sub) + mapOf("created_at" to now, "updated_at" to now))
            tx.set(invoiceCollection.document(invoice.id), invoiceFields(invoice, now))
            sub.pendingOp?.let { op ->
                tx.set(opCollection.document(SubscriptionRules.opId(sub.id, op, sub.version)), opFields(sub, op, now))
            }
            // سجل المستخدم (SUBSCRIPTION) في المعاملة نفسها: لا مشترك بلا اشتراك ولا اشتراك بلا سجل. المعرّف = معرّف الاشتراك (idempotent)
            tx.set(userCollection.document(sub.id), userRecordFields(sub, now))
            sub
        }.await()

    // ───────────── الانتقالات الإدارية ─────────────

    /**
     * يطبّق [change] على الحالة الحالية داخل معاملة. القيمة null = لم يعد مستحقاً (تُرجع null دون كتابة).
     * رمز الرفض يُرمى. يكتب سجل أمر الراوتر عند وجود أمر جديد معلّق.
     */
    suspend fun mutate(id: String, change: (Subscription) -> Subscription?): Subscription? =
        firestore.runTransaction { tx ->
            val ref = subCollection.document(id)
            val snap = tx.get(ref)
            if (!snap.exists()) throw Exception(SubscriptionRules.ERR_NOT_FOUND)
            val userSnap = tx.get(userCollection.document(id))
            val current = mapSubscription(snap.id, snap.data ?: emptyMap())
                ?: throw Exception(SubscriptionRules.ERR_INVALID_STATE)
            val updated = change(current) ?: return@runTransaction null
            val now = Timestamp.now()
            tx.update(ref, fieldsOf(updated) + mapOf("updated_at" to now))
            patchUserRecord(tx, userSnap, updated, now)
            val op = updated.pendingOp
            if (op != null && updated.version != current.version) {
                tx.set(opCollection.document(SubscriptionRules.opId(id, op, updated.version)), opFields(updated, op, now))
            }
            updated
        }.await()

    /**
     * تجديد + فاتورة الفترة الجديدة في معاملة واحدة. [key] = مفتاح التجديد؛ المطابق لآخر تجديد يُعاد كما هو (idempotent).
     * [autoOnly] = تجديد تلقائي: لا يُنفَّذ إلا إن كان مستحقاً فعلاً الآن (لم يُجدَّد يدوياً قبله).
     */
    suspend fun renew(id: String, key: String?, now: Instant, zone: ZoneId, autoOnly: Boolean): Subscription? =
        firestore.runTransaction { tx ->
            val ref = subCollection.document(id)
            val snap = tx.get(ref)
            if (!snap.exists()) throw Exception(SubscriptionRules.ERR_NOT_FOUND)
            val userSnap = tx.get(userCollection.document(id))
            val current = mapSubscription(snap.id, snap.data ?: emptyMap())
                ?: throw Exception(SubscriptionRules.ERR_INVALID_STATE)
            if (autoOnly && !SubscriptionRules.isAutoRenewDue(current, now)) return@runTransaction null
            val effectiveKey = key ?: SubscriptionRules.autoRenewKey(id, current.periodNumber + 1)
            if (current.lastRenewKey == effectiveKey) return@runTransaction current

            val ok = when (val renewal = SubscriptionRules.renew(current, now, effectiveKey, zone)) {
                is RenewalResult.Rejected -> throw Exception(renewal.code)
                is RenewalResult.Ok -> renewal
            }
            val total = SubscriptionBilling.netMinor(current.priceMinor, current.discountMinor)
            val invoice = SubscriptionInvoice(
                id = SubscriptionBilling.invoiceId(id, ok.sub.periodNumber),
                subscriptionId = id,
                periodNumber = ok.sub.periodNumber,
                periodStart = SubscriptionRules.iso(ok.periodStart),
                periodEnd = SubscriptionRules.iso(ok.periodEnd),
                priceMinor = current.priceMinor,
                discountMinor = current.discountMinor,
                totalMinor = total,
                dueDate = SubscriptionRules.iso(ok.periodStart)
            )
            val updated = ok.sub.copy(
                totalInvoicedMinor = current.totalInvoicedMinor + total,
                lastInvoiceId = invoice.id
            )
            val ts = Timestamp.now()
            tx.update(ref, fieldsOf(updated) + mapOf("updated_at" to ts))
            patchUserRecord(tx, userSnap, updated, ts)
            tx.set(invoiceCollection.document(invoice.id), invoiceFields(invoice, ts))
            tx.set(opCollection.document(SubscriptionRules.opId(id, SubscriptionOp.RENEW, updated.version)), opFields(updated, SubscriptionOp.RENEW, ts))
            updated
        }.await()

    // ───────────── المحاسبة ─────────────

    /** دفعة على فاتورة اشتراك. معرّف الدفعة = المفتاح؛ لا دفع زائد؛ تحديث الفاتورة ومجاميع الاشتراك معاً. */
    suspend fun recordPayment(invoiceId: String, amountMinor: Long, method: String, key: String) {
        firestore.runTransaction { tx ->
            val paymentRef = paymentCollection.document(key)
            if (tx.get(paymentRef).exists()) return@runTransaction Unit
            val invoiceRef = invoiceCollection.document(invoiceId)
            val invoiceSnap = tx.get(invoiceRef)
            if (!invoiceSnap.exists()) throw Exception(SubscriptionBilling.ERR_INVOICE_NOT_FOUND)
            val invoice = mapInvoice(invoiceSnap.id, invoiceSnap.data ?: emptyMap())
            val subRef = subCollection.document(invoice.subscriptionId)
            val subSnap = tx.get(subRef)
            if (!subSnap.exists()) throw Exception(SubscriptionRules.ERR_NOT_FOUND)

            SubscriptionBilling.checkPayment(invoice.totalMinor, invoice.paidMinor, amountMinor)?.let { throw Exception(it) }

            val now = Timestamp.now()
            tx.set(paymentRef, mapOf(
                "subscription_id" to invoice.subscriptionId,
                "invoice_id" to invoiceId,
                "amount_minor" to amountMinor,
                "method" to method,
                "is_void" to false,
                "created_at" to now
            ))
            tx.update(invoiceRef, mapOf("paid_minor" to invoice.paidMinor + amountMinor, "updated_at" to now))
            tx.update(subRef, mapOf(
                "total_paid_minor" to ((subSnap.getLong("total_paid_minor") ?: 0L) + amountMinor),
                "updated_at" to now
            ))
            Unit
        }.await()
    }

    /** إلغاء دفعة: يخفض المدفوع على الفاتورة والاشتراك. إلغاء مكرر = لا أثر. */
    suspend fun voidPayment(paymentId: String) {
        firestore.runTransaction { tx ->
            val paymentRef = paymentCollection.document(paymentId)
            val paymentSnap = tx.get(paymentRef)
            if (!paymentSnap.exists()) throw Exception(SubscriptionBilling.ERR_PAYMENT_NOT_FOUND)
            val payment = mapPayment(paymentSnap.id, paymentSnap.data ?: emptyMap())
            if (payment.isVoid) return@runTransaction Unit

            val invoiceRef = invoiceCollection.document(payment.invoiceId)
            val invoiceSnap = tx.get(invoiceRef)
            val subRef = subCollection.document(payment.subscriptionId)
            val subSnap = tx.get(subRef)
            val now = Timestamp.now()

            tx.update(paymentRef, mapOf("is_void" to true, "voided_at" to now))
            if (invoiceSnap.exists()) {
                val paid = invoiceSnap.getLong("paid_minor") ?: 0L
                tx.update(invoiceRef, mapOf("paid_minor" to SubscriptionBilling.paidAfterVoid(paid, payment.amountMinor), "updated_at" to now))
            }
            if (subSnap.exists()) {
                val paid = subSnap.getLong("total_paid_minor") ?: 0L
                tx.update(subRef, mapOf("total_paid_minor" to SubscriptionBilling.paidAfterVoid(paid, payment.amountMinor), "updated_at" to now))
            }
            Unit
        }.await()
    }

    // ───────────── نتيجة التنفيذ على الراوتر ─────────────

    /**
     * يسجّل نتيجة محاولة تنفيذ الأمر المعلّق على [expected]. تُتجاهل النتيجة إن تغيّر الأمر/الإصدار أثناء التنفيذ
     * (أمر أحدث حلّ محلّه) وتُرجع null. النجاح: SYNCED وتفريغ الأمر وحفظ ما قُرئ من الراوتر. الفشل: يبقى الأمر معلّقاً
     * (PENDING عند عطل قابل لإعادة المحاولة، FAILED عند رفض/عدم تطابق) مع رسالة الخطأ.
     */
    suspend fun completeSync(
        expected: Subscription,
        observed: RouterObservation?,
        error: String?,
        retryable: Boolean,
        now: Instant
    ): Subscription? =
        firestore.runTransaction { tx ->
            val ref = subCollection.document(expected.id)
            val snap = tx.get(ref)
            if (!snap.exists()) return@runTransaction null
            val userSnap = tx.get(userCollection.document(expected.id))
            val current = mapSubscription(snap.id, snap.data ?: emptyMap()) ?: return@runTransaction null
            if (current.pendingOp != expected.pendingOp || current.version != expected.version) return@runTransaction null
            val ts = Timestamp.now()
            val updated = if (error == null && observed != null) {
                current.copy(
                    execution = SubscriptionExecution.SYNCED,
                    pendingOp = null,
                    routerUserId = observed.routerUserId.ifBlank { current.routerUserId },
                    routerObservedDisabled = if (observed.userExists) observed.disabled else true,
                    uptimeUsed = observed.uptime.ifBlank { current.uptimeUsed },
                    bytesIn = if (observed.userExists) observed.bytesIn else current.bytesIn,
                    bytesOut = if (observed.userExists) observed.bytesOut else current.bytesOut,
                    lastSyncAt = SubscriptionRules.iso(now),
                    lastSyncError = ""
                )
            } else {
                current.copy(
                    execution = if (retryable) SubscriptionExecution.PENDING else SubscriptionExecution.FAILED,
                    lastSyncError = error.orEmpty().take(300),
                    lastSyncAt = SubscriptionRules.iso(now)
                )
            }
            tx.update(ref, fieldsOf(updated) + mapOf("updated_at" to ts))
            patchUserRecord(tx, userSnap, updated, ts)
            expected.pendingOp?.let { op ->
                tx.set(
                    opCollection.document(SubscriptionRules.opId(expected.id, op, expected.version)),
                    mapOf(
                        "attempts" to FieldValue.increment(1),
                        "status" to if (error == null) "DONE" else if (retryable) "PENDING" else "FAILED",
                        "last_error" to error.orEmpty().take(300),
                        "executed_at" to if (error == null) SubscriptionRules.iso(now) else ""
                    ),
                    SetOptions.merge()
                )
            }
            updated
        }.await()

    /** يحدّث ما قُرئ من الراوتر بلا تغيير حالة إدارية ولا أوامر (مزامنة/كشف تعارض). */
    suspend fun recordObservation(id: String, observed: RouterObservation, now: Instant) {
        subCollection.document(id).update(
            mapOf(
                "router_user_id" to observed.routerUserId,
                "router_observed_disabled" to if (observed.userExists) observed.disabled else true,
                "uptime_used" to observed.uptime,
                "bytes_in" to observed.bytesIn,
                "bytes_out" to observed.bytesOut,
                "last_sync_at" to SubscriptionRules.iso(now)
            )
        ).await()
    }

    // ───────────── التحويل من/إلى Firestore ─────────────

    // ───────────── سجل المستخدم المقابل (local_users) ─────────────

    private fun userStatusOf(s: Subscription) = SubscriberRecordRules.statusOf(s).name.lowercase()

    private fun userSyncOf(s: Subscription) = SubscriberRecordRules.syncOf(s).name.lowercase()

    private fun userRecordFields(s: Subscription, now: Timestamp): Map<String, Any?> = mapOf(
        "router_username" to s.customerUsername,
        "owner_id" to s.ownerId,
        "status" to userStatusOf(s),
        "origin" to "generated",
        "sync_status" to userSyncOf(s),
        "system_class" to SystemClass.SUBSCRIPTION.wire,
        "limit_uptime_seconds" to s.limitUptimeSeconds,
        "limit_transfer_bytes" to s.limitTransferBytes,
        "last_action" to "subscription_create",
        "created_at" to now,
        "updated_at" to now
    )

    /**
     * يُبقي حالة سجل المستخدم متسقة مع الاشتراك داخل المعاملة نفسها. لا يُنشئ سجلاً لاشتراك قديم بلا سجل
     * (لا سجلات وهمية): يحدّث فقط إن وُجدت الوثيقة (userSnap قُرئ قبل أي كتابة).
     */
    private fun patchUserRecord(tx: com.google.firebase.firestore.Transaction, userSnap: com.google.firebase.firestore.DocumentSnapshot, s: Subscription, now: Timestamp) {
        if (!userSnap.exists()) return
        tx.update(userSnap.reference, mapOf(
            "status" to userStatusOf(s),
            "sync_status" to userSyncOf(s),
            "last_action" to "subscription_${s.status.name.lowercase()}",
            "updated_at" to now
        ))
    }

    private fun fieldsOf(s: Subscription): Map<String, Any?> = mapOf(
        "subscription_id" to s.subscriptionId.ifBlank { s.id },
        "customer_uuid" to s.customerUuid,
        "customer_username" to s.customerUsername,
        "customer_name" to s.customerName,
        "customer_phone" to s.customerPhone,
        "router_password" to s.routerPassword,
        "owner_id" to s.ownerId,
        "owner_name" to s.ownerName,
        "profile_id" to s.profileId,
        "profile_name" to s.profileName,
        "profile_classification_snapshot" to s.profileClassificationSnapshot,
        "router_profile_name" to s.routerProfileName,
        "limit_uptime_seconds" to s.limitUptimeSeconds,
        "limit_transfer_bytes" to s.limitTransferBytes,
        "status" to s.status.name,
        "start_date" to s.startDate,
        "next_renewal" to s.nextRenewal,
        "billing_cycle" to s.billingCycle,
        "period_unit" to s.periodUnit.name,
        "period_count" to s.periodCount,
        "period_number" to s.periodNumber,
        "activated_at" to s.activatedAt,
        "expires_at" to s.expiresAt,
        "suspended_at" to s.suspendedAt,
        "price_minor" to s.priceMinor,
        "discount_minor" to s.discountMinor,
        "final_price_minor" to s.finalPriceMinor,
        "price_currency" to s.priceCurrency,
        "price_source" to s.priceSource,
        "pricing_type" to s.pricingType,
        "price_category_id" to s.priceCategoryId,
        "price_rule_id" to s.priceRuleId,
        "discount_type" to s.discountType,
        "discount_value" to s.discountValue,
        "auto_renewal" to s.autoRenewal,
        "last_invoice_id" to s.lastInvoiceId,
        "notes" to s.notes,
        "version" to s.version,
        "execution" to s.execution.name,
        "pending_op" to s.pendingOp?.name,
        "router_user_id" to s.routerUserId,
        "router_observed_disabled" to s.routerObservedDisabled,
        "last_sync_at" to s.lastSyncAt,
        "last_sync_error" to s.lastSyncError,
        "last_renew_key" to s.lastRenewKey,
        "uptime_used" to s.uptimeUsed,
        "bytes_in" to s.bytesIn,
        "bytes_out" to s.bytesOut,
        "total_invoiced_minor" to s.totalInvoicedMinor,
        "total_paid_minor" to s.totalPaidMinor
    )

    private fun invoiceFields(i: SubscriptionInvoice, now: Timestamp): Map<String, Any?> = mapOf(
        "subscription_id" to i.subscriptionId,
        "period_number" to i.periodNumber,
        "period_start" to i.periodStart,
        "period_end" to i.periodEnd,
        "price_minor" to i.priceMinor,
        "discount_minor" to i.discountMinor,
        "total_minor" to i.totalMinor,
        "paid_minor" to i.paidMinor,
        "due_date" to i.dueDate,
        "created_at" to now
    )

    private fun opFields(s: Subscription, op: SubscriptionOp, now: Timestamp): Map<String, Any?> = mapOf(
        "subscription_id" to s.id,
        "op" to op.name,
        "period_number" to s.periodNumber,
        "status" to "PENDING",
        "attempts" to 0L,
        "last_error" to "",
        "requested_at" to now.toDate().toSortableIso(),
        "executed_at" to ""
    )

    private fun mapSubscription(id: String, data: Map<String, Any?>): Subscription? {
        val rawStatus = data["status"] as? String
        val status = rawStatus.toEnumOrNull<SubscriptionStatus>()
        if (status == null) {
            reporter.rejected("subscriptions", id, if (rawStatus.isNullOrBlank()) "missing status" else "unknown status '$rawStatus'")
            return null
        }
        fun str(key: String) = data[key] as? String ?: ""
        fun long(key: String) = (data[key] as? Number)?.toLong() ?: 0L
        fun longOrNull(key: String) = (data[key] as? Number)?.toLong()
        fun iso(key: String) = (data[key] as? Timestamp)?.toDate()?.toSortableIso() ?: ""
        return Subscription(
            id = id,
            subscriptionId = str("subscription_id").ifBlank { id },
            customerUuid = str("customer_uuid"),
            customerUsername = str("customer_username"),
            customerName = str("customer_name"),
            customerPhone = str("customer_phone"),
            routerPassword = str("router_password"),
            ownerId = str("owner_id"),
            ownerName = str("owner_name"),
            profileId = str("profile_id"),
            profileName = str("profile_name"),
            profileClassificationSnapshot = str("profile_classification_snapshot"),
            routerProfileName = str("router_profile_name"),
            limitUptimeSeconds = longOrNull("limit_uptime_seconds"),
            limitTransferBytes = longOrNull("limit_transfer_bytes"),
            status = status,
            startDate = str("start_date"),
            nextRenewal = str("next_renewal"),
            billingCycle = str("billing_cycle").ifBlank { "monthly" },
            periodUnit = str("period_unit").toEnumOrNull<PeriodUnit>() ?: PeriodUnit.MONTHS,
            periodCount = long("period_count").toInt().coerceAtLeast(1),
            periodNumber = long("period_number").toInt(),
            activatedAt = str("activated_at"),
            expiresAt = str("expires_at"),
            suspendedAt = str("suspended_at"),
            priceMinor = long("price_minor"),
            discountMinor = long("discount_minor"),
            finalPriceMinor = long("final_price_minor"),
            priceCurrency = str("price_currency"),
            priceSource = str("price_source"),
            pricingType = str("pricing_type"),
            priceCategoryId = str("price_category_id"),
            priceRuleId = str("price_rule_id"),
            discountType = data["discount_type"] as? String,
            discountValue = long("discount_value"),
            autoRenewal = data["auto_renewal"] as? Boolean ?: false,
            lastInvoiceId = data["last_invoice_id"] as? String,
            notes = str("notes"),
            createdAt = iso("created_at"),
            updatedAt = iso("updated_at"),
            version = long("version").toInt(),
            execution = str("execution").toEnumOrNull<SubscriptionExecution>() ?: SubscriptionExecution.SYNCED,
            pendingOp = str("pending_op").toEnumOrNull<SubscriptionOp>(),
            routerUserId = str("router_user_id"),
            routerObservedDisabled = data["router_observed_disabled"] as? Boolean,
            lastSyncAt = str("last_sync_at"),
            lastSyncError = str("last_sync_error"),
            lastRenewKey = str("last_renew_key"),
            uptimeUsed = str("uptime_used"),
            bytesIn = long("bytes_in"),
            bytesOut = long("bytes_out"),
            totalInvoicedMinor = long("total_invoiced_minor"),
            totalPaidMinor = long("total_paid_minor")
        )
    }

    private fun mapInvoice(id: String, data: Map<String, Any?>): SubscriptionInvoice {
        fun str(key: String) = data[key] as? String ?: ""
        fun long(key: String) = (data[key] as? Number)?.toLong() ?: 0L
        return SubscriptionInvoice(
            id = id,
            subscriptionId = str("subscription_id"),
            periodNumber = long("period_number").toInt(),
            periodStart = str("period_start"),
            periodEnd = str("period_end"),
            priceMinor = long("price_minor"),
            discountMinor = long("discount_minor"),
            totalMinor = long("total_minor"),
            paidMinor = long("paid_minor"),
            dueDate = str("due_date"),
            createdAt = (data["created_at"] as? Timestamp)?.toDate()?.toSortableIso() ?: ""
        )
    }

    private fun mapPayment(id: String, data: Map<String, Any?>): SubscriptionPayment = SubscriptionPayment(
        id = id,
        subscriptionId = data["subscription_id"] as? String ?: "",
        invoiceId = data["invoice_id"] as? String ?: "",
        amountMinor = (data["amount_minor"] as? Number)?.toLong() ?: 0L,
        method = data["method"] as? String ?: "cash",
        isVoid = data["is_void"] as? Boolean ?: false,
        createdAt = (data["created_at"] as? Timestamp)?.toDate()?.toSortableIso() ?: ""
    )

    private fun mapOp(id: String, data: Map<String, Any?>): SubscriptionOperation? {
        val op = (data["op"] as? String).toEnumOrNull<SubscriptionOp>() ?: return null
        return SubscriptionOperation(
            id = id,
            subscriptionId = data["subscription_id"] as? String ?: "",
            op = op,
            periodNumber = (data["period_number"] as? Number)?.toInt() ?: 0,
            status = data["status"] as? String ?: "PENDING",
            attempts = (data["attempts"] as? Number)?.toInt() ?: 0,
            lastError = data["last_error"] as? String ?: "",
            requestedAt = data["requested_at"] as? String ?: "",
            executedAt = data["executed_at"] as? String ?: ""
        )
    }
}

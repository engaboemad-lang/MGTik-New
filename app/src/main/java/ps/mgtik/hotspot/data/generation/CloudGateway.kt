package ps.mgtik.hotspot.data.generation

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.domain.generation.BillingResult
import ps.mgtik.hotspot.domain.generation.CloudPort
import ps.mgtik.hotspot.domain.generation.PrepareResult
import ps.mgtik.hotspot.domain.generation.RunState
import ps.mgtik.hotspot.domain.generation.SagaError
import ps.mgtik.hotspot.domain.generation.SagaResult
import ps.mgtik.hotspot.domain.generation.StagedVoucher
import ps.mgtik.hotspot.domain.generation.UserLimits
import ps.mgtik.hotspot.data.cloud.source.ProfileSettingsReader
import ps.mgtik.hotspot.domain.usecase.ResolvedUserSettings
import ps.mgtik.hotspot.domain.model.PricingRule
import ps.mgtik.hotspot.domain.model.ProfileCategory
import ps.mgtik.hotspot.domain.model.SaleContext
import ps.mgtik.hotspot.domain.usecase.PricingPolicy
import ps.mgtik.hotspot.domain.usecase.UsageLimits
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CloudGateway - منفذ الساغا إلى Firestore.
 *
 * - كل الكتابات مفاتيحها مشتقة من الطلب (orderId/batchId/seq/INV-orderId) ⇒ إعادة التنفيذ آمنة.
 * - Firestore بلا اتصال لا يُكمل الكتابة (ينتظر الخادم): كل نداء بمهلة، وانتهاؤها يُرجَع كخطأ قابل للإعادة
 *   (الكتابة تبقى في طابور Firestore المحلي وتُطبَّق لاحقاً، وتكرارها آمن).
 * - الـ transactions تستخدم get بالمرجع فقط (لا استعلامات داخلها).
 */
@Singleton
class CloudGateway @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val settingsReader: ProfileSettingsReader
) : CloudPort {

    private val orders = firestore.collection("orders")
    private val batches = firestore.collection("batches")
    private val owners = firestore.collection("owners")
    private val profiles = firestore.collection("profiles")
    private val categories = firestore.collection("profile_categories")
    private val pricingRules = firestore.collection("pricing_rules")
    private val invoices = firestore.collection("invoices")
    private val counters = firestore.collection("counters")
    private val localUsers = firestore.collection("local_users")
    private val executions = firestore.collection("executions")
    /** profile_defaults/{profileId}: يحمل العدّاد التسلسلي الدائم للبروفايل (last_assigned_sequence). */
    private val profileDefaults = firestore.collection("profile_defaults")

    private class CloudFail(val error: SagaError) : Exception(error.code)

    private fun fail(code: String, message: String, retryable: Boolean = false): Nothing =
        throw CloudFail(SagaError(code, message, retryable))

    private suspend fun <T> call(block: suspend () -> T): SagaResult<T> = try {
        SagaResult.Ok(withTimeout(CLOUD_TIMEOUT_MS) { block() })
    } catch (e: CloudFail) {
        SagaResult.Err(e.error)
    } catch (e: TimeoutCancellationException) {
        SagaResult.Err(SagaError(ApiError.CODE_TIMEOUT, "Cloud call timed out", true))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val api = ApiError.mapThrowable(e)
        val transient = api.retryable || api.code == ApiError.CODE_NETWORK_UNAVAILABLE || api.code == ApiError.CODE_TIMEOUT
        SagaResult.Err(SagaError(api.code, api.message, transient))
    }

    // ------------------------------------------------------------------ PREPARE

    override suspend fun prepare(state: RunState): SagaResult<PrepareResult> = call {
        val spec = state.spec
        val batchRef = batches.document(state.batchId)

        // إعادة تنفيذ بعد انقطاع: الدفعة موجودة ⇒ نعيدها كما هي دون إعادة فحص المالك/الباقة
        batchRef.get().await().takeIf { it.exists() }?.let { return@call preparedFrom(state, it) }

        val profileDoc = profiles.document(spec.profileId).get().await()
        if (!profileDoc.exists() || profileDoc.getString("status")?.lowercase() != "active") {
            fail(ERR_PROFILE_UNAVAILABLE, "Profile is missing or not active")
        }
        // التوليد ينتج مستخدمين NORMAL فقط: باقة فئتها SUBSCRIPTION تُرفض. الفئة القديمة غير المصنّفة (أو بلا فئة)
        // لا تُمنع حتى لا تنكسر باقات قائمة؛ كل ما تولّده عادي بالتعريف ويُخزَّن system_class=normal.
        profileDoc.getString("category_id")?.let { catId ->
            val cat = categories.document(catId).get().await()
            if (cat.exists() && cat.getString("system_class")?.lowercase() == "subscription") {
                fail(ERR_PROFILE_CLASS_MISMATCH, "Profile belongs to a SUBSCRIPTION category; use subscriptions")
            }
        }
        // اسم الباقة في User Manager: المرجع الحقل المخزَّن في السحابة لا ما أرسلته الواجهة (قد تكون نسخة قديمة بعد إعادة تسمية)
        val routerProfileName = profileDoc.getString("linked_profile_name").orEmpty().ifBlank { spec.profileName }
        if (routerProfileName.isBlank()) fail(ERR_PROFILE_UNAVAILABLE, "Profile has no User Manager name")
        val ownerDoc = owners.document(spec.ownerId).get().await()
        // موقوف أو مؤرشف: لا دفعات جديدة (الفحص هنا هو المرجع؛ قائمة الواجهة مجرد تسهيل)
        if (!ownerDoc.exists() || ownerDoc.getString("status")?.uppercase() in setOf("ARCHIVED", "SUSPENDED")) {
            fail(ERR_OWNER_UNAVAILABLE, "Owner is missing, suspended or archived")
        }

        // قيود الاستخدام تُحسم هنا مرة واحدة وتُخزَّن في وثيقة الدفعة (لقطة): تعديل البروفايل لاحقاً لا يغيّر
        // دفعة جارية ولا إعادة محاولاتها. قيمة تالفة (صفر/سالب/فوق السقف) تُرفض الآن قبل لمس الراوتر.
        // المصدر نفسه الذي يستعمله الإنشاء الفردي (ProfileSettingsReader): البروفايل + تصنيفه، لا الحقول الخام وحدها.
        val settings = when (val r = settingsReader.resolve(profileDoc)) {
            is ProfileSettingsReader.Result.Ok -> r.settings
            is ProfileSettingsReader.Result.Invalid -> fail(r.code, r.detail)
        }
        val limits = settings.limits

        // نفس PricingPolicy المستعمل في الشاشات (خاص نشط للوكيل ← أساسي ← منع). القيمة تُثبَّت في وثيقة الدفعة أدناه
        // (price_per_voucher_minor + مصدرها) فلا تعيد الفوترة حسابها من السعر الحالي لاحقاً.
        // توليد الكروت يتم دائماً لمالك/وكيل = بيع جملة (السعر الخاص ← سعر جملة الفئة ← سعر البروفايل القديم).
        val resolved = resolvePrice(spec.profileId, spec.ownerId, profileDoc)
            ?: fail(PricingPolicy.ERR_PRICE_UNAVAILABLE, "No valid price for this profile/owner; set a wholesale price on its category first")
        val unitPrice = resolved.unitMinor
        val priceCategoryName = profileDoc.getString("category_id")
            ?.let { categories.document(it).get().await().getString("name_ar") }.orEmpty()
        val ownerName = ownerDoc.getString("name").orEmpty()
        val businessId = ownerDoc.getString("businessId").orEmpty()
        val counterRef = counters.document("batches")
        val orderRef = orders.document(state.orderId)

        firestore.runTransaction { tx ->
            val existing = tx.get(batchRef)
            if (existing.exists()) return@runTransaction preparedFrom(state, existing)

            // العلامة تُكتب في المعاملة نفسها التي تنشئ الدفعة: حذف الباقة (معاملة تقرأ الوثيقة ذاتها) لا يمكن أن
            // ينجح بالتوازي مع إنشاء دفعة عليها. نعيد قراءة الحالة هنا لأن القراءة السابقة خارج المعاملة.
            val profileSnap = tx.get(profiles.document(spec.profileId))
            if (!profileSnap.exists() || profileSnap.getString("status")?.lowercase() != "active") {
                fail(ERR_PROFILE_UNAVAILABLE, "Profile is missing or not active")
            }

            val counterSnap = tx.get(counterRef)
            val next = (counterSnap.getLong("last") ?: 0L) + 1
            if (profileSnap.getBoolean("in_use") != true) tx.update(profileSnap.reference, "in_use", true)
            val number = "B-" + next.toString().padStart(6, '0')
            tx.set(counterRef, mapOf("last" to next), SetOptions.merge())

            tx.set(
                batchRef,
                hashMapOf(
                    "id" to state.batchId,
                    "batch_number" to number,
                    "cloud_profile_id" to spec.profileId,
                    "profile_name" to routerProfileName,
                    "owner_id" to spec.ownerId,
                    "order_id" to state.orderId,
                    "orderId" to state.orderId, // CloudOrderDataSource.getBatchesForOrderFlow يستعلم بهذا الاسم
                    "execution_id" to state.executionId,
                    "requested_quantity" to spec.count,
                    "successful_quantity" to 0,
                    "failed_quantity" to 0,
                    "price_per_voucher_minor" to unitPrice,
                    // لقطة السعر التاريخية: القيمة، العملة، المصدر، نوع التسعير، الفئة، البروفايل، المالك، القاعدة، الوقت
                    "price_source" to resolved.source.name,
                    "price_rule_id" to resolved.ruleId,
                    "price_category_name" to priceCategoryName,
                    "price_at" to FieldValue.serverTimestamp(),
                    "total_amount_minor" to 0L,
                    "prefix" to spec.prefix,
                    "format" to spec.format,
                    "code_length" to spec.codeLength,
                    "mode" to spec.mode,
                    "password_type" to spec.passwordType,
                    "password_length" to spec.passwordLength,
                    "comment" to spec.comment,
                    "status" to "RUNNING",
                    "idempotency_key" to state.idempotencyKey,
                    "limit_uptime_seconds" to limits.uptimeSeconds,
                    "limit_transfer_bytes" to limits.transferBytes,
                    "download_kbps" to settings.downloadKbps,
                    "upload_kbps" to settings.uploadKbps,
                    "shared_users" to settings.sharedUsers,
                    "shared_unlimited" to settings.sharedUnlimited,
                    "validity_start" to settings.validityStart,
                    "validity_seconds" to settings.validitySeconds,
                    "validity_unlimited" to settings.validityUnlimited,
                    "created_at" to FieldValue.serverTimestamp()
                ) + resolved.snapshotFields(spec.profileId)
            )
            tx.set(
                orderRef,
                hashMapOf(
                    "ownerId" to spec.ownerId,
                    "ownerNameSnapshot" to ownerName,
                    "businessId" to businessId,
                    "status" to "DRAFT",
                    "linkedBatchIds" to listOf(state.batchId),
                    "idempotencyKey" to state.idempotencyKey,
                    "totalCards" to 0,
                    "totalAmountMinor" to 0L,
                    "createdBy" to "",
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            PrepareResult(state.orderId, state.batchId, number, unitPrice, ownerName, limits, settings, routerProfileName)
        }.await()
    }

    private fun preparedFrom(state: RunState, doc: com.google.firebase.firestore.DocumentSnapshot) = PrepareResult(
        orderId = doc.getString("order_id") ?: state.orderId,
        batchId = state.batchId,
        batchNumber = doc.getString("batch_number").orEmpty(),
        pricePerVoucherMinor = doc.getLong("price_per_voucher_minor") ?: 0L,
        ownerName = state.ownerName,
        // من وثيقة الدفعة نفسها (اللقطة الأصلية)، لا من البروفايل الحالي
        limits = limitsOf(doc, validate = false),
        settings = settingsOf(doc),
        profileName = doc.getString("profile_name")?.takeIf { it.isNotBlank() }
    )

    /** لقطة الإعدادات المحسومة المخزَّنة في وثيقة الدفعة (لا إعادة حسم من البروفايل الحالي). */
    private fun settingsOf(doc: com.google.firebase.firestore.DocumentSnapshot) = ResolvedUserSettings(
        limits = limitsOf(doc, validate = false),
        downloadKbps = doc.getLong("download_kbps")?.toInt(),
        uploadKbps = doc.getLong("upload_kbps")?.toInt(),
        sharedUsers = doc.getLong("shared_users")?.toInt(),
        sharedUnlimited = doc.getBoolean("shared_unlimited") ?: false,
        validityStart = doc.getString("validity_start"),
        validitySeconds = doc.getLong("validity_seconds"),
        validityUnlimited = doc.getBoolean("validity_unlimited") ?: false
    )

    /** قراءة القيود من وثيقة (بروفايل أو دفعة). validate=true يرفض القيم غير الصالحة؛ غير المحدد = null (لا صفر). */
    private fun limitsOf(doc: com.google.firebase.firestore.DocumentSnapshot, validate: Boolean): UserLimits {
        val uptime = doc.getLong("limit_uptime_seconds")
        val transfer = doc.getLong("limit_transfer_bytes")
        if (validate && (!UsageLimits.isUptimeValid(uptime) || !UsageLimits.isTransferValid(transfer))) {
            fail(ERR_PROFILE_LIMIT_INVALID, "Profile usage limits are invalid (uptime=$uptime, transfer=$transfer)")
        }
        return UserLimits(uptime, transfer)
    }

    /**
     * يقرأ فئة الباقة وقواعد التسعير (على مستوى الفئة والقديمة على مستوى البروفايل) ويمرّرها لـ [PricingPolicy.resolveFor]
     * (المصدر الوحيد للاختيار). null = لا سعر صالح.
     */
    private suspend fun resolvePrice(profileId: String, ownerId: String, profileDoc: com.google.firebase.firestore.DocumentSnapshot): PricingPolicy.Quote? {
        val categoryId = profileDoc.getString("category_id")
        val catDoc = categoryId?.let { categories.document(it).get().await() }?.takeIf { it.exists() }
        val category = catDoc?.let {
            ProfileCategory(
                id = it.id,
                nameAr = it.getString("name_ar").orEmpty(),
                currency = it.getString("currency"),
                retailPriceMinor = it.getLong("retail_price_minor"),
                wholesalePriceMinor = it.getLong("wholesale_price_minor")
            )
        }
        fun map(d: com.google.firebase.firestore.DocumentSnapshot) = PricingRule(
            id = d.id,
            cloudProfileId = d.getString("cloud_profile_id").orEmpty(),
            categoryId = d.getString("category_id").orEmpty(),
            retailPriceMinor = d.getLong("retail_price_minor") ?: 0L,
            effectiveFrom = d.getTimestamp("effective_from")?.toDate()?.time?.toString() ?: "",
            ownerId = d.getString("owner_id") ?: "",
            active = d.getBoolean("active") ?: true,
            context = SaleContext.fromWire(d.getString("context")) ?: SaleContext.WHOLESALE,
            currency = d.getString("currency").orEmpty()
        )
        val rules = buildList {
            addAll(pricingRules.whereEqualTo("cloud_profile_id", profileId).get().await().documents.map(::map))
            if (categoryId != null) addAll(pricingRules.whereEqualTo("category_id", categoryId).get().await().documents.map(::map))
        }
        val companyCurrency = firestore.collection("settings").document("company_info").get().await()
            .getString("currency_symbol") ?: "₪"
        return PricingPolicy.resolveFor(
            SaleContext.WHOLESALE, ownerId, category,
            profileDoc.getLong("price_minor") ?: 0L, rules, companyCurrency
        )
    }

    // ------------------------------------------------------------------ SEQUENCE

    /**
     * حجز ذرّي من العدّاد الدائم للبروفايل: معاملة Firestore تقرأ last_assigned_sequence وتكتب last+count، فلا يحصل توليدان
     * متزامنان (ولا جهازان) على الرقم نفسه. تجاوز [maxValue] يُرفض دون كتابة فلا يتلف العدّاد.
     * (القيمة -1 من المعاملة تعني «ممتلئ»؛ لا نرمي من داخل المعاملة كي لا يلتفّ الاستثناء بغلاف Firestore.)
     */
    override suspend fun reserveSequence(profileId: String, count: Int, maxValue: Long): SagaResult<Long> = call {
        val ref = profileDefaults.document(profileId)
        val start = firestore.runTransaction { tx ->
            val last = tx.get(ref).getLong(SEQUENCE_FIELD) ?: 0L
            if (last + count > maxValue) {
                -1L
            } else {
                tx.set(ref, mapOf(SEQUENCE_FIELD to last + count), SetOptions.merge())
                last + 1
            }
        }.await()
        if (start < 0) fail(SagaError.SEQUENCE_EXHAUSTED, "Profile sequence would exceed the username length capacity")
        start
    }

    // ------------------------------------------------------------------ COMMIT

    override suspend fun commitVouchers(state: RunState, vouchers: List<StagedVoucher>): SagaResult<Unit> = call {
        // وثيقتان لكل كرت (voucher + سجل المستخدم) ⇒ 250 كحد أقصى كي لا نتجاوز 500 عملية في دفعة Firestore
        require(vouchers.size <= 250) { "Firestore batch limit" }
        val col = batches.document(state.batchId).collection("vouchers")
        // لقطة الإعدادات من وثيقة الدفعة (ثابتة منذ PREPARE) تُكتب على كل سجل مستخدم كما في الإنشاء الفردي
        val batchDoc = batches.document(state.batchId).get().await()
        val snap = settingsOf(batchDoc)
        val categoryId = batchDoc.getString("price_category_id").orEmpty()
        val wb = firestore.batch()
        vouchers.forEach { v ->
            val docId = v.seq.toString().padStart(6, '0')
            wb.set(
                col.document(docId),
                hashMapOf(
                    "seq" to v.seq,
                    "username" to v.username,
                    "password" to v.password,
                    "profile_name" to state.spec.profileName,
                    "batch_id" to state.batchId
                )
            )
            // سجل المستخدم المحلي: ربط الحساب بالدفعة والمالك مع القيود الفعلية المطبقة على الراوتر.
            // المعرّف مشتق (batchId-seq) فإعادة التنفيذ تعيد كتابة السجل نفسه ولا تكرّره. لا يُكتب إلا لكروت
            // ROUTER_OK (الساغا تمرّر هنا ما نجح على الراوتر فقط) فلا سجل لحساب فشل إنشاؤه.
            wb.set(
                localUsers.document("${state.batchId}-$docId"),
                hashMapOf(
                    "router_username" to v.username,
                    "owner_id" to state.spec.ownerId,
                    "status" to "active",
                    "origin" to "generated",
                    "sync_status" to "synced",
                    "batch_id" to state.batchId,
                    // الربط بالباقة وبعملية التوليد: تتبّع كامل من المستخدم إلى الباقة والدفعة والتنفيذ
                    "creation_profile_id" to state.spec.profileId,
                    "creation_router_profile" to state.spec.profileName,
                    // الفئة التجارية وقت التوليد (من لقطة تسعير الدفعة)
                    "category_id" to categoryId.ifBlank { null },
                    "execution_id" to state.executionId,
                    "batch_number" to state.batchNumber,
                    "limit_uptime_seconds" to state.limits.uptimeSeconds,
                    "limit_transfer_bytes" to state.limits.transferBytes,
                    "download_kbps" to snap.downloadKbps,
                    "upload_kbps" to snap.uploadKbps,
                    "shared_users" to snap.sharedUsers,
                    "shared_unlimited" to snap.sharedUnlimited,
                    // سياسة البداية المطبَّقة؛ البدء الفعلي/الانتهاء يُملآن عند توفرهما من UM (لا يُخمَّنان لـ first_auth)
                    "validity_start" to snap.validityStart,
                    "system_class" to "normal", // التوليد = بطاقات عادية دائماً (SUBSCRIPTION له مسار الاشتراك)
                    "created_at" to FieldValue.serverTimestamp(),
                    "updated_at" to FieldValue.serverTimestamp()
                )
            )
        }
        wb.commit().await()
        Unit
    }

    override suspend fun finalizeBatch(state: RunState, status: String): SagaResult<Unit> = call {
        batches.document(state.batchId).update(
            mapOf(
                "status" to status,
                "successful_quantity" to state.successful,
                "failed_quantity" to state.failed,
                "total_amount_minor" to (if (status == "COMPLETED") state.successful * state.pricePerVoucherMinor else 0L),
                "completed_at" to FieldValue.serverTimestamp()
            )
        ).await()
        Unit
    }

    // ------------------------------------------------------------------ BILLING

    override suspend fun bill(state: RunState): SagaResult<BillingResult> = call {
        val invoiceId = "INV-${state.orderId}"
        val invoiceRef = invoices.document(invoiceId)
        val orderRef = orders.document(state.orderId)
        val counterRef = counters.document("invoices")
        val total = state.successful.toLong() * state.pricePerVoucherMinor
        // عملة الفاتورة = رمز عملة الشركة نفسه الذي تعرضه النتيجة وتقدّر به الواجهة (لا ₪ ثابتة)
        val currency = firestore.collection("settings").document("company_info").get().await()
            .getString("currency_symbol")?.takeIf { it.isNotBlank() } ?: "₪"

        firestore.runTransaction { tx ->
            val existing = tx.get(invoiceRef)
            if (existing.exists()) return@runTransaction BillingResult(invoiceId, existing.getLong("totalMinor") ?: total)

            val order = tx.get(orderRef)
            val next = (tx.get(counterRef).getLong("last") ?: 0L) + 1
            tx.set(counterRef, mapOf("last" to next), SetOptions.merge())

            tx.set(
                invoiceRef,
                hashMapOf(
                    "invoiceNumber" to "INV-" + next.toString().padStart(6, '0'),
                    "orderId" to state.orderId,
                    "ownerId" to state.spec.ownerId,
                    "ownerNameSnapshot" to (order.getString("ownerNameSnapshot") ?: state.ownerName),
                    "businessId" to (order.getString("businessId") ?: ""),
                    "status" to (if (total == 0L) "PAID" else "DUE"),
                    "subtotalMinor" to total,
                    "totalMinor" to total,
                    "amountPaidMinor" to 0L,
                    "amountDueMinor" to total,
                    "isPaid" to (total == 0L),
                    "currency" to currency,
                    "paymentMethod" to "",
                    "notes" to "Batch ${state.batchNumber}",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "issueDate" to FieldValue.serverTimestamp()
                )
            )
            tx.update(
                orderRef,
                mapOf(
                    "status" to "CLOSED",
                    "invoiceId" to invoiceId,
                    "totalCards" to state.successful,
                    "totalAmountMinor" to total,
                    "closedAt" to FieldValue.serverTimestamp()
                )
            )
            BillingResult(invoiceId, total)
        }.await()
    }

    override suspend fun cancelOrder(state: RunState): SagaResult<Unit> = call {
        orders.document(state.orderId).set(
            mapOf("status" to "CANCELLED", "closedAt" to FieldValue.serverTimestamp()),
            SetOptions.merge()
        ).await()
        Unit
    }

    // ------------------------------------------------------------------ MIRROR

    /** مرآة best-effort بلا await (بلا اتصال لن يكتمل الانتظار)، وأي فشل يُتجاهل. */
    override suspend fun mirror(state: RunState) {
        try {
            executions.document(state.executionId).set(
                hashMapOf(
                    "flowType" to "GENERATION",
                    "contextId" to state.batchId,
                    "status" to RunMapping.executionStatus(state.status).name,
                    "attemptNumber" to state.attempt,
                    "requestedQuantity" to state.requested,
                    "successfulQuantity" to state.successful,
                    "failedQuantity" to state.failed,
                    "errorCode" to state.errorCode,
                    "errorMessage" to state.errorMessage,
                    "startedAt" to (RunMapping.formatTime(state.createdAtMillis) ?: ""),
                    "completedAt" to RunMapping.formatTime(state.completedAtMillis)
                ),
                SetOptions.merge()
            )
        } catch (e: Exception) {
            // المرآة للرؤية فقط؛ مصدر الحقيقة محلي
        }
    }

    companion object {
        const val SEQUENCE_FIELD = "last_assigned_sequence"
        const val CLOUD_TIMEOUT_MS = 30_000L
        const val ERR_PROFILE_UNAVAILABLE = "ERR_GEN_PROFILE_UNAVAILABLE"
        const val ERR_PROFILE_CLASS_MISMATCH = "ERR_PROFILE_CLASS_MISMATCH"
        const val ERR_PROFILE_LIMIT_INVALID = "ERR_GEN_PROFILE_LIMIT_INVALID" // قديم؛ الرمز الموحّد: OperationalSettingsResolver.ERR_LIMIT_INVALID
        const val ERR_OWNER_UNAVAILABLE = "ERR_GEN_OWNER_UNAVAILABLE"
    }
}

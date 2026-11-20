package ps.mgtik.hotspot.data.cloud.source

import ps.mgtik.hotspot.core.util.toSortableIso
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.safeApiCall
import ps.mgtik.hotspot.data.mapping.FirestoreDocFields
import ps.mgtik.hotspot.data.mapping.ProfileDocMapper
import ps.mgtik.hotspot.data.mapping.SyncErrorReporter
import ps.mgtik.hotspot.data.mapping.toEnumOrNull
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.core.result.RuleViolation
import ps.mgtik.hotspot.domain.usecase.SystemClassRules
import ps.mgtik.hotspot.domain.usecase.CategoryRules
import ps.mgtik.hotspot.domain.usecase.CategoryHistoryRules
import ps.mgtik.hotspot.domain.usecase.PricingPolicy
import ps.mgtik.hotspot.domain.usecase.ReferenceGuard
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CloudProfileDataSource - تنفيذ العمليات السحابية للبروفايلات (§4)
 */
@Singleton
class CloudProfileDataSource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val reporter: SyncErrorReporter
) {
    private val profileCollection = firestore.collection("profiles")
    private val categoryCollection = firestore.collection("profile_categories")
    private val classificationCollection = firestore.collection("profile_classifications")
    private val pricingRulesCollection = firestore.collection("pricing_rules")
    private val defaultsCollection = firestore.collection("profile_defaults")

    private companion object {
        const val SEQUENCE_FIELD = "last_assigned_sequence"
        const val SEQUENCE_WRITE_TIMEOUT_MS = 15_000L
    }

    /**
     * لقطة باقات من Firestore مع ما يلزم لتقليم الكاش بأمان:
     * - [presentIds]: معرّفات كل المستندات في اللقطة، بما فيها المرفوضة (قيمة status ناقصة/مجهولة)،
     *   كي لا تُحذف النسخة المحلية لمستند موجود في السحابة لمجرد أنه غير مقروء.
     * - [fromCache]: true إن جاءت اللقطة من كاش الـ SDK (قد تكون فارغة/ناقصة دون أن تمثل الخادم).
     */
    data class ProfilesSnapshot(
        val profiles: List<Profile>,
        val presentIds: Set<String>,
        val fromCache: Boolean
    )

    fun getProfilesSnapshots(includeArchived: Boolean, categoryId: String?): Flow<ProfilesSnapshot> {
        var query = profileCollection.whereIn("status", if (includeArchived) listOf("active", "archived", "missing", "disabled") else listOf("active", "missing", "disabled"))
        if (categoryId != null) {
            query = query.whereEqualTo("category_id", categoryId)
        }

        // MetadataChanges.INCLUDE: وإلا فلن تصل لقطة الخادم حين تطابق بياناتها الكاش، فلا نعرف أنها مؤكَّدة.
        return query.snapshots(MetadataChanges.INCLUDE).map { snapshot ->
            ProfilesSnapshot(
                profiles = snapshot.documents.mapNotNull { doc -> mapProfile(doc) },
                presentIds = snapshot.documents.mapTo(HashSet()) { it.id },
                fromCache = snapshot.metadata.isFromCache
            )
        }
    }

    fun getProfiles(includeArchived: Boolean, categoryId: String?): Flow<List<Profile>> =
        getProfilesSnapshots(includeArchived, categoryId).map { it.profiles }.distinctUntilChanged()

    private fun mapProfile(doc: com.google.firebase.firestore.DocumentSnapshot): Profile? {
        val rawStatus = doc.getString("status")
        val status = rawStatus.toEnumOrNull<ProfileStatus>()
        if (status == null) {
            // لا تحويل إلى ACTIVE: باقة بحالة مجهولة لا تُعرض ولا تُستخدم في التوليد
            reporter.rejected("profiles", doc.id, if (rawStatus.isNullOrBlank()) "missing status" else "unknown status '$rawStatus'")
            return null
        }
        return ProfileDocMapper.profile(FirestoreDocFields(doc), status)
    }

    suspend fun saveProfile(profile: Profile): AppResult<Unit> = safeApiCall {
        healCategoryCounter(profile.categoryId)
        val data = hashMapOf<String, Any?>(
            "linked_profile_name" to profile.linkedProfileName,
            "display_name_en" to profile.displayNameEn,
            "display_name_ar" to profile.displayNameAr,
            "description" to profile.description,
            "category_id" to profile.categoryId,
            "classification_key" to profile.classificationKey,
            "status" to profile.status.name.lowercase(),
            "rate_limit_tx_kbps" to profile.rateLimitTxKbps,
            "rate_limit_rx_kbps" to profile.rateLimitRxKbps,
            "price_minor" to profile.priceMinor,
            "shared_users" to profile.sharedUsers,
            "validity_hours" to profile.validityHours,
            "quota_mb" to profile.quotaMb,
            "limit_uptime_seconds" to profile.limitUptimeSeconds,
            "limit_transfer_bytes" to profile.limitTransferBytes,
            "own_settings" to profile.ownSettings?.map { it.wire },
            "validity_start" to profile.validityStart,
            "validity_unlimited" to profile.validityUnlimited,
            "shared_unlimited" to profile.sharedUnlimited,
            "updated_at" to FieldValue.serverTimestamp()
        )
        val ref = profileCollection.document(profile.id)
        firestore.runTransaction<Any?> { tx ->
            val existing = tx.get(ref)
            val oldCat = existing.takeIf { it.exists() }?.getString("category_id")
            applyCategoryMove(tx, oldCat, profile.categoryId)
            // علامة الاستخدام تُنشأ false مع أول إنشاء ولا تُمسّ لاحقاً (لا تعود false إن صارت true)
            if (!existing.exists() || existing.get("in_use") == null) data["in_use"] = false
            tx.set(ref, data, SetOptions.merge())
            null
        }.await()
        Unit
    }

    suspend fun updateProfile(profileId: String, fields: Map<String, Any?>): AppResult<Unit> = safeApiCall {
        val updateData = fields.toMutableMap()
        updateData["updated_at"] = FieldValue.serverTimestamp()
        val ref = profileCollection.document(profileId)
        if ("category_id" in fields) {
            // نقل بين الفئات: التحقق من الفئة الجديدة + تعديل العدّادين بنفس المعاملة التي تكتب الباقة
            healCategoryCounter(fields["category_id"] as? String)
            firestore.runTransaction<Any?> { tx ->
                val existing = tx.get(ref)
                if (!existing.exists()) throw RuleViolation("NOT_FOUND", "Profile not found")
                applyCategoryMove(tx, existing.getString("category_id"), fields["category_id"] as? String)
                tx.update(ref, updateData)
                null
            }.await()
        } else {
            ref.update(updateData).await()
        }
        Unit
    }

    /**
     * يطبّق أثر نقل باقة من فئة إلى أخرى (داخل معاملة: كل القراءات قبل الكتابات).
     * الفئة الجديدة يجب أن تكون موجودة ونشطة وعدّادها مهيّأ؛ الإزالة (null) دائماً مسموحة.
     */
    private fun applyCategoryMove(tx: com.google.firebase.firestore.Transaction, oldCat: String?, newCat: String?) {
        if (oldCat == newCat) return
        val newSnap = newCat?.let { tx.get(categoryCollection.document(it)) }
        val oldSnap = oldCat?.let { tx.get(categoryCollection.document(it)) }
        if (newCat != null) {
            if (!ReferenceGuard.categoryAssignable(newSnap!!.exists(), newSnap.getString("status"))) {
                throw RuleViolation(ReferenceGuard.ERR_CATEGORY_INACTIVE, "Category is missing, disabled or archived")
            }
            if (newSnap.get("profile_count") == null) throw RuleViolation(ReferenceGuard.ERR_INTEGRITY_NOT_READY, "Category counter not ready")
            tx.update(newSnap.reference, "profile_count", FieldValue.increment(1))
        }
        if (oldCat != null && oldSnap != null && oldSnap.exists() && oldSnap.get("profile_count") != null) {
            tx.update(oldSnap.reference, "profile_count", FieldValue.increment(-1))
        }
    }

    /** يهيّئ عدّاد فئة قديمة (بلا profile_count) مرة واحدة بالعدّ الفعلي؛ لا يمسّ عدّاداً موجوداً (معاملة مشروطة). */
    private suspend fun healCategoryCounter(categoryId: String?) {
        if (categoryId == null) return
        val ref = categoryCollection.document(categoryId)
        val snap = ref.get().await()
        if (!snap.exists() || snap.get("profile_count") != null) return
        val actual = profileCollection.whereEqualTo("category_id", categoryId).get().await().size()
        firestore.runTransaction<Any?> { tx ->
            val cur = tx.get(ref)
            if (cur.exists() && cur.get("profile_count") == null) tx.update(ref, "profile_count", actual.toLong())
            null
        }.await()
    }

    /** يهيّئ علامة in_use لباقة قديمة بالفحص الفعلي (دفعات/اشتراكات)؛ لا يخفضها إن صارت true. */
    private suspend fun healProfileMarker(profileId: String) {
        val ref = profileCollection.document(profileId)
        val snap = ref.get().await()
        if (!snap.exists() || snap.get("in_use") != null) return
        val used = (countProfileReferencesRaw(profileId) > 0)
        firestore.runTransaction<Any?> { tx ->
            val cur = tx.get(ref)
            if (cur.exists() && cur.get("in_use") == null) tx.update(ref, "in_use", used)
            null
        }.await()
    }

    private suspend fun countProfileReferencesRaw(profileId: String): Int {
        val batches = firestore.collection("batches").whereEqualTo("cloud_profile_id", profileId).limit(1).get().await().size()
        val subs = firestore.collection("subscriptions").whereEqualTo("profile_id", profileId).limit(1).get().await().size()
        return batches + subs
    }

    // --- الفئات والتصنيفات ---
    fun getCategories(): Flow<List<ProfileCategory>> = categoryCollection.snapshots().map { snapshot ->
        snapshot.documents.map { doc ->
            mapCategory(doc)
        }
    }

    private fun mapCategory(doc: com.google.firebase.firestore.DocumentSnapshot) = ProfileCategory(
        id = doc.id,
        nameAr = doc.getString("name_ar") ?: "",
        nameEn = doc.getString("name_en") ?: "",
        priority = doc.getLong("priority")?.toInt() ?: 0,
        description = doc.getString("description"),
        status = doc.getString("status").toEnumOrNull<CategoryStatus>() ?: CategoryStatus.ACTIVE,
        systemClass = SystemClass.fromWire(doc.getString("system_class")),
        currency = doc.getString("currency"),
        retailPriceMinor = doc.getLong("retail_price_minor"),
        wholesalePriceMinor = doc.getLong("wholesale_price_minor")
    )

    fun getClassifications(): Flow<List<ProfileClassification>> = classificationCollection.snapshots().map { snapshot ->
        snapshot.documents.map { doc -> mapClassification(doc) }
    }

    /** التصنيفات القديمة (بلا الحقول التشغيلية) تُقرأ بقيم null = غير معرَّفة، فلا يرثها أحد. */
    private fun mapClassification(doc: com.google.firebase.firestore.DocumentSnapshot) =
        ProfileDocMapper.classification(FirestoreDocFields(doc))

    suspend fun getClassificationOnce(key: String): ProfileClassification? = safeApiCall {
        classificationCollection.document(key).get().await()
    }.let { (it as? AppResult.Success)?.data }?.takeIf { it.exists() }?.let { mapClassification(it) }

    suspend fun getClassificationsOnce(): List<ProfileClassification> = safeApiCall {
        classificationCollection.get().await().documents.map { mapClassification(it) }
    }.let { (it as? AppResult.Success)?.data ?: emptyList() }

    suspend fun setClassificationStatus(key: String, status: CategoryStatus): AppResult<Unit> = safeApiCall {
        classificationCollection.document(key).update(mapOf("status" to status.name.lowercase())).await()
    }

    /** عدد البروفايلات المرتبطة بتصنيف (كل الحالات). */
    suspend fun countProfilesInClassification(key: String): AppResult<Int> = safeApiCall {
        profileCollection.whereEqualTo("classification_key", key).get().await().size()
    }

    /** حالة الوكيل (ACTIVE/SUSPENDED/ARCHIVED…) أو null إن لم يوجد. */
    suspend fun ownerStatus(ownerId: String): AppResult<String?> = safeApiCall {
        val doc = firestore.collection("owners").document(ownerId).get().await()
        if (doc.exists()) (doc.getString("status")?.uppercase() ?: "ACTIVE") else null
    }

    // --- فحوص السلامة (تُنفَّذ في طبقة الخدمة لا الواجهة) ---

    suspend fun getCategoryOnce(categoryId: String): ProfileCategory? = safeApiCall {
        val doc = categoryCollection.document(categoryId).get().await()
        if (!doc.exists()) null else mapCategory(doc)
    }.let { (it as? AppResult.Success)?.data }

    suspend fun getCategoriesOnce(): List<ProfileCategory> = safeApiCall {
        categoryCollection.get().await().documents.map { doc ->
            mapCategory(doc)
        }
    }.let { (it as? AppResult.Success)?.data ?: emptyList() }

    /** عدد البروفايلات المرتبطة بفئة (كل الحالات، بما فيها المؤرشفة). */
    suspend fun countProfilesInCategory(categoryId: String): AppResult<Int> = safeApiCall {
        profileCollection.whereEqualTo("category_id", categoryId).get().await().size()
    }

    /** ارتباطات تمنع حذف بروفايل: دفعات مولّدة أو اشتراكات. (لا يُحسب السعر الخاص: يُحذف معه بأمان.) */
    suspend fun countProfileReferences(profileId: String): AppResult<Int> = safeApiCall {
        val batches = firestore.collection("batches").whereEqualTo("cloud_profile_id", profileId).limit(1).get().await().size()
        val subs = firestore.collection("subscriptions").whereEqualTo("profile_id", profileId).limit(1).get().await().size()
        batches + subs
    }

    /** عدد دفعات الباقة السابقة (للعرض في أثر تغيير المرجع). */
    suspend fun countBatchesForProfile(profileId: String): AppResult<Int> = safeApiCall {
        firestore.collection("batches").whereEqualTo("cloud_profile_id", profileId).get().await().size()
    }

    suspend fun getProfileOnce(profileId: String): Profile? =
        (safeApiCall { profileCollection.document(profileId).get().await() } as? AppResult.Success)
            ?.data?.takeIf { it.exists() }?.let { mapProfile(it) }

    suspend fun setCategoryStatus(categoryId: String, status: CategoryStatus): AppResult<Unit> = safeApiCall {
        val ref = categoryCollection.document(categoryId)
        firestore.runTransaction<Any?> { tx ->
            val snap = tx.get(ref)
            val before = snap.getString("status") ?: "active" // غياب الحقل = نشطة (فئات قديمة)
            val after = status.name.lowercase()
            tx.update(ref, mapOf("status" to after))
            if (snap.exists() && before != after) {
                tx.set(historyRef(categoryId), historyDoc(CategoryHistoryAction.STATUS_CHANGED, listOf(FieldChange("status", before, after))))
            }
            null
        }.await()
        Unit
    }

    /**
     * حذف ذري: يقرأ علامة in_use داخل المعاملة نفسها التي تحذف. إن أنشأت عملية متزامنة دفعة/اشتراكاً
     * فمعاملتها تكتب على الوثيقة نفسها ⇒ إحدى المعاملتين تُعاد وتشاهد نتيجة الأخرى، فلا يُحذف سجل صار مستخدماً.
     */
    suspend fun deleteProfile(profileId: String): AppResult<Unit> = safeApiCall {
        healProfileMarker(profileId)
        val ref = profileCollection.document(profileId)
        firestore.runTransaction<Any?> { tx ->
            val snap = tx.get(ref)
            if (!snap.exists()) return@runTransaction null
            when (ReferenceGuard.profileDeletable(snap.getBoolean("in_use"))) {
                ReferenceGuard.Verdict.BLOCKED -> throw RuleViolation(ReferenceGuard.ERR_PROFILE_IN_USE, "Profile is referenced by batches or subscriptions")
                ReferenceGuard.Verdict.NOT_READY -> throw RuleViolation(ReferenceGuard.ERR_INTEGRITY_NOT_READY, "Profile marker not ready")
                ReferenceGuard.Verdict.OK -> Unit
            }
            val catId = snap.getString("category_id")
            val catSnap = catId?.let { tx.get(categoryCollection.document(it)) }
            if (catSnap != null && catSnap.exists() && catSnap.get("profile_count") != null) {
                tx.update(catSnap.reference, "profile_count", FieldValue.increment(-1))
            }
            tx.delete(ref)
            null
        }.await()
        Unit
    }

    // --- حفظ/حذف الفئات والتصنيفات ---
    suspend fun saveCategory(category: ProfileCategory): AppResult<Unit> = safeApiCall {
        // الحالة لا تُكتب هنا (لها setCategoryStatus) كي لا يُعيد حفظ الاسم تفعيلاً لفئة معطّلة
        val data = mapOf(
            "name_ar" to category.nameAr,
            "name_en" to category.nameEn,
            "priority" to category.priority,
            "description" to category.description,
            // أسعار الفئة: تعديلها لا يمسّ أي دفعة/اشتراك سابق (لقطاتها محفوظة فيها)؛ null = غير محدد
            "retail_price_minor" to category.retailPriceMinor,
            "wholesale_price_minor" to category.wholesalePriceMinor
        )
        val ref = categoryCollection.document(category.id.ifBlank { UUID.randomUUID().toString() })
        firestore.runTransaction<Any?> { tx ->
            val existing = tx.get(ref)
            // التصنيف النظامي: إلزامي للفئة الجديدة، وثابت بعد تعيينه؛ الفئة القديمة غير المصنّفة تُعيَّن مرة واحدة
            val storedClass = existing.takeIf { it.exists() }?.let { SystemClass.fromWire(it.getString("system_class")) }
            SystemClassRules.checkClassImmutable(storedClass, category.systemClass)?.let {
                throw RuleViolation(it, "System class of a category cannot change")
            }
            // العملة ثابتة بعد تعيينها؛ الفئة القديمة بلا عملة تُعيَّن مرة واحدة
            val storedCurrency = existing.takeIf { it.exists() }?.getString("currency")
            CategoryRules.checkCurrencyImmutable(storedCurrency, category.currency)?.let {
                throw RuleViolation(it, "Currency of a category cannot change")
            }
            if (!existing.exists() && category.currency.isNullOrBlank()) {
                throw RuleViolation(CategoryRules.ERR_CURRENCY_REQUIRED, "Currency is required for a new category")
            }
            val effectiveClass = storedClass ?: category.systemClass
            if (!existing.exists() && effectiveClass == null) {
                throw RuleViolation(SystemClassRules.ERR_CATEGORY_CLASS_REQUIRED, "System class is required for a new category")
            }
            val withCurrency = if (storedCurrency.isNullOrBlank() && !category.currency.isNullOrBlank()) data + mapOf("currency" to category.currency) else data
            val withClass = if (storedClass == null && effectiveClass != null) withCurrency + mapOf("system_class" to effectiveClass.wire) else withCurrency
            // الفئة الجديدة تبدأ نشطة بعدّاد 0؛ التعديل لا يلمس الحالة ولا العدّاد
            val full = if (existing.exists()) withClass else withClass + mapOf("status" to "active", "profile_count" to 0L)
            // سجل التعديلات التجارية في المعاملة نفسها: يُكتب فقط ما تغيّر فعلاً، ولا تضيع كتابة بلا سجلها
            val changes = CategoryHistoryRules.diff(existing.takeIf { it.exists() }?.data, full)
            tx.set(ref, full, SetOptions.merge())
            if (!existing.exists() || changes.isNotEmpty()) {
                tx.set(
                    historyRef(ref.id),
                    historyDoc(if (existing.exists()) CategoryHistoryAction.CATEGORY_UPDATED else CategoryHistoryAction.CATEGORY_CREATED, changes)
                )
            }
            null
        }.await()
        Unit
    }

    /** حذف ذري للفئة: العدّاد يُقرأ داخل المعاملة؛ أي إنشاء/نقل باقة متزامن يعدّله فيتعارض ويُعاد. */
    suspend fun deleteCategory(categoryId: String): AppResult<Unit> = safeApiCall {
        healCategoryCounter(categoryId)
        val ref = categoryCollection.document(categoryId)
        firestore.runTransaction<Any?> { tx ->
            val snap = tx.get(ref)
            if (!snap.exists()) return@runTransaction null
            when (ReferenceGuard.categoryDeletable(snap.getLong("profile_count"))) {
                ReferenceGuard.Verdict.BLOCKED -> throw RuleViolation(ReferenceGuard.ERR_CATEGORY_NOT_EMPTY, "Category still has profiles")
                ReferenceGuard.Verdict.NOT_READY -> throw RuleViolation(ReferenceGuard.ERR_INTEGRITY_NOT_READY, "Category counter not ready")
                ReferenceGuard.Verdict.OK -> Unit
            }
            tx.delete(ref)
            null
        }.await()
        Unit
    }

    /**
     * حفظ تصنيف تشغيلي. الحالة لا تُكتب هنا (لها setClassificationStatus). تعديله لا يلمس أي بروفايل ولا مستخدم:
     * التطبيق على الموجودين خطوة صريحة منفصلة (ClassificationRules.planApply). الإعداد غير المعرَّف يُخزَّن null.
     */
    suspend fun saveClassification(classification: ProfileClassification): AppResult<Unit> = safeApiCall {
        val data = mapOf(
            "name_ar" to classification.nameAr,
            "name_en" to classification.nameEn,
            "description" to classification.description,
            "download_kbps" to classification.downloadKbps,
            "upload_kbps" to classification.uploadKbps,
            "shared_users" to classification.sharedUsers,
            "limit_uptime_seconds" to classification.limitUptimeSeconds,
            "limit_transfer_bytes" to classification.limitTransferBytes,
            "updated_at" to FieldValue.serverTimestamp()
        )
        val ref = classificationCollection.document(classification.key.ifBlank { UUID.randomUUID().toString() })
        firestore.runTransaction<Any?> { tx ->
            val exists = tx.get(ref).exists()
            tx.set(ref, if (exists) data else data + mapOf("status" to "active"), SetOptions.merge())
            null
        }.await()
        Unit
    }

    // --- التسعير والقيم الافتراضية والإحصائيات ---
    fun getPricingRules(profileId: String): Flow<List<PricingRule>> =
        pricingRulesCollection.whereEqualTo("cloud_profile_id", profileId).snapshots().map { snapshot ->
            snapshot.documents.map { doc -> mapRule(doc) }
        }

    /** رمز عملة الشركة (يُستعمل عندما لا تحمل الفئة عملة). */
    suspend fun companyCurrency(): String = safeApiCall {
        firestore.collection("settings").document("company_info").get().await().getString("currency_symbol")
    }.let { (it as? AppResult.Success)?.data } ?: "₪"

    private fun mapRule(doc: com.google.firebase.firestore.DocumentSnapshot) = PricingRule(
        id = doc.id,
        cloudProfileId = doc.getString("cloud_profile_id") ?: "",
        categoryId = doc.getString("category_id") ?: "",
        classificationKey = doc.getString("classification_key") ?: "",
        retailPriceMinor = doc.getLong("retail_price_minor") ?: 0L,
        costPriceMinor = doc.getLong("cost_price_minor") ?: 0L,
        effectiveFrom = doc.getTimestamp("effective_from")?.toDate()?.time?.toString() ?: "",
        ownerId = doc.getString("owner_id") ?: "",
        active = doc.getBoolean("active") ?: true,
        context = SaleContext.fromWire(doc.getString("context")) ?: SaleContext.WHOLESALE,
        currency = doc.getString("currency") ?: ""
    )

    /** الأسعار الخاصة على مستوى الفئة (النموذج الحالي). */
    fun getCategoryPricingRules(categoryId: String): Flow<List<PricingRule>> =
        pricingRulesCollection.whereEqualTo("category_id", categoryId).snapshots().map { s -> s.documents.map { mapRule(it) } }

    /** أسعار وكيل واحد (حقل واحد فلا فهرس مركّب). */
    fun getOwnerPricingRules(ownerId: String): Flow<List<PricingRule>> =
        pricingRulesCollection.whereEqualTo("owner_id", ownerId).snapshots().map { s -> s.documents.map { mapRule(it) } }

    suspend fun countCategoryRules(categoryId: String): AppResult<Int> = safeApiCall {
        pricingRulesCollection.whereEqualTo("category_id", categoryId).get().await().size()
    }

    /**
     * سعر خاص لمالك/وكيل على مستوى الفئة: مستند واحد بمعرّف ثابت category__owner__context فلا يتكرر.
     * يعيد القيمة السابقة (إن وُجدت) لسجل التدقيق. فحوص الفئة/المالك في المستودع.
     */
    suspend fun saveCategoryOwnerPrice(categoryId: String, ownerId: String, context: SaleContext, priceMinor: Long, currency: String): AppResult<Long?> = safeApiCall {
        val ref = pricingRulesCollection.document(PricingPolicy.categoryPriceDocId(categoryId, ownerId, context))
        firestore.runTransaction { tx ->
            val snap = tx.get(ref)
            val previous = snap.takeIf { it.exists() }?.getLong("retail_price_minor")
            val wasActive = snap.takeIf { it.exists() }?.getBoolean("active")
            tx.set(
                ref,
                mapOf(
                    "category_id" to categoryId,
                    "owner_id" to ownerId,
                    "context" to context.wire,
                    "currency" to currency,
                    "retail_price_minor" to priceMinor,
                    "active" to true,
                    "effective_from" to FieldValue.serverTimestamp()
                )
            )
            val changes = buildList {
                if (previous != priceMinor) add(FieldChange("owner_price_minor", previous?.toString(), priceMinor.toString()))
                if (wasActive == false) add(FieldChange("active", "false", "true"))
            }
            if (changes.isNotEmpty()) {
                tx.set(historyRef(categoryId), historyDoc(CategoryHistoryAction.OWNER_PRICE_SET, changes, ownerId, context))
            }
            previous
        }.await()
    }

    suspend fun setRuleActive(ruleId: String, active: Boolean): AppResult<Unit> = safeApiCall {
        val ref = pricingRulesCollection.document(ruleId)
        firestore.runTransaction<Any?> { tx ->
            val snap = tx.get(ref)
            val before = snap.getBoolean("active") ?: true
            tx.update(ref, mapOf("active" to active))
            val cat = snap.getString("category_id").orEmpty()
            // القواعد القديمة على مستوى البروفايل (بلا فئة) خارج سجل الفئات
            if (snap.exists() && cat.isNotBlank() && before != active) {
                tx.set(
                    historyRef(cat),
                    historyDoc(
                        CategoryHistoryAction.OWNER_PRICE_ACTIVE_CHANGED, listOf(FieldChange("active", before.toString(), active.toString())),
                        snap.getString("owner_id"), SaleContext.fromWire(snap.getString("context"))
                    )
                )
            }
            null
        }.await()
        Unit
    }

    /** حذف سعر خاص آمن تاريخياً: الدفعات والاشتراكات تحمل لقطة السعر لا مرجعاً حياً إليه؛ والحذف نفسه يُسجَّل في سجل الفئة بسعره السابق. */
    suspend fun deleteRule(ruleId: String): AppResult<Unit> = safeApiCall {
        val ref = pricingRulesCollection.document(ruleId)
        firestore.runTransaction<Any?> { tx ->
            val snap = tx.get(ref)
            tx.delete(ref)
            val cat = snap.getString("category_id").orEmpty()
            if (snap.exists() && cat.isNotBlank()) {
                tx.set(
                    historyRef(cat),
                    historyDoc(
                        CategoryHistoryAction.OWNER_PRICE_DELETED,
                        listOf(FieldChange("owner_price_minor", snap.getLong("retail_price_minor")?.toString(), null)),
                        snap.getString("owner_id"), SaleContext.fromWire(snap.getString("context"))
                    )
                )
            }
            null
        }.await()
        Unit
    }

    // ---- سجل تعديلات الفئة (ثابت: لا يُعدَّل ولا يُحذف؛ القواعد تمنع ذلك)
    private fun historyRef(categoryId: String) = categoryCollection.document(categoryId).collection("history").document()

    private fun historyDoc(
        action: CategoryHistoryAction, changes: List<FieldChange>, ownerId: String? = null, context: SaleContext? = null
    ): Map<String, Any?> = mapOf(
        "at" to FieldValue.serverTimestamp(),
        "action" to action.wire,
        "changes" to CategoryHistoryRules.toWire(changes),
        "owner_id" to ownerId,
        "context" to context?.wire
    )

    fun getCategoryHistory(categoryId: String, limit: Long = 200): Flow<List<CategoryHistoryEntry>> =
        categoryCollection.document(categoryId).collection("history")
            .orderBy("at", Query.Direction.DESCENDING).limit(limit).snapshots()
            .map { snap ->
                snap.documents.mapNotNull { d ->
                    val action = CategoryHistoryAction.fromWire(d.getString("action")) ?: return@mapNotNull null
                    CategoryHistoryEntry(
                        id = d.id, categoryId = categoryId,
                        at = d.getTimestamp("at")?.toDate()?.toSortableIso() ?: "",
                        action = action,
                        changes = CategoryHistoryRules.fromWire(d.get("changes")),
                        ownerId = d.getString("owner_id"),
                        context = SaleContext.fromWire(d.getString("context"))
                    )
                }
            }

    // ---- عدّ الارتباطات الفعلي (aggregate من الخادم؛ عند تعذّر الخادم يعود خطأ لا صفراً)
    private suspend fun countOf(q: Query): Int = q.count().get(AggregateSource.SERVER).await().count.toInt()

    suspend fun getProfileUsage(profileIds: List<String>): AppResult<Map<String, ProfileUsage>> = safeApiCall {
        val users = firestore.collection("local_users")
        coroutineScope {
            profileIds.distinct().map { pid ->
                async {
                    val single = countOf(users.whereEqualTo("creation_profile_id", pid))
                    // معرّف مستند الدفعة = batch_id في سجلات المستخدمين (انظر CloudGateway)
                    val batchIds = firestore.collection("batches").whereEqualTo("cloud_profile_id", pid).get().await().documents.map { it.id }
                    val viaBatches = batchIds.chunked(30).sumOf { countOf(users.whereIn("batch_id", it)) }
                    val subs = countOf(firestore.collection("subscriptions").whereEqualTo("profile_id", pid))
                    pid to ProfileUsage(normalUsers = single + viaBatches, subscriptions = subs, batches = batchIds.size)
                }
            }.awaitAll().toMap()
        }
    }

    suspend fun getRuleOnce(ruleId: String): PricingRule? = safeApiCall {
        pricingRulesCollection.document(ruleId).get().await()
    }.let { (it as? AppResult.Success)?.data }?.takeIf { it.exists() }?.let { mapRule(it) }

    fun getProfileDefaults(profileId: String): Flow<ProfileDefaults?> =
        defaultsCollection.document(profileId).snapshots().map { doc ->
            if (!doc.exists()) null else ProfileDefaults(
                profileId = doc.id,
                defaultRateLimit = doc.getString("default_rate_limit") ?: "8M/8M",
                defaultSharedUsers = doc.getLong("default_shared_users")?.toInt() ?: 1,
                defaultSessionTimeout = doc.getString("default_session_timeout") ?: "00:00:00",
                lastAssignedSequence = doc.getLong(SEQUENCE_FIELD) ?: 0L
            )
        }

    /** العدّاد التسلسلي لكل البروفايلات (معرّف البروفايل ← آخر رقم مستخدم)؛ غياب الوثيقة = 0. */
    fun observeUsernameSequences(): Flow<Map<String, Long>> =
        defaultsCollection.snapshots().map { snapshot ->
            snapshot.documents.associate { doc -> doc.id to (doc.getLong(SEQUENCE_FIELD) ?: 0L) }
        }.distinctUntilChanged()

    /**
     * تصفير (أو ضبط) العدّاد التسلسلي لبروفايل: كتابة merge فلا تمسّ بقية حقول الوثيقة.
     * كتابة Firestore بلا اتصال لا تكتمل قبل عودة الشبكة، فنُنهيها بخطأ بدل أن تعلّق الواجهة (الكتابة تبقى في طابور
     * الـ SDK وتُطبَّق عند الاتصال؛ تكرارها آمن لأنها تضبط قيمة لا تزيدها).
     */
    suspend fun setUsernameSequence(profileId: String, value: Long): AppResult<Unit> = safeApiCall {
        require(value >= 0) { "sequence must not be negative" }
        kotlinx.coroutines.withTimeoutOrNull(SEQUENCE_WRITE_TIMEOUT_MS) {
            defaultsCollection.document(profileId).set(mapOf(SEQUENCE_FIELD to value), SetOptions.merge()).await()
            true // await() يعيد Void (null)؛ لا يصح أن يُفسَّر كانتهاء المهلة
        } ?: throw java.io.IOException("Sequence update timed out (offline?)")
        Unit
    }

    suspend fun upsertProfileDefaults(defaults: ProfileDefaults): AppResult<Unit> = safeApiCall {
        val data = mapOf(
            "default_rate_limit" to defaults.defaultRateLimit,
            "default_shared_users" to defaults.defaultSharedUsers,
            "default_session_timeout" to defaults.defaultSessionTimeout
        )
        defaultsCollection.document(defaults.profileId).set(data, SetOptions.merge()).await()
    }

    /** إحصائيات الباقة: عدد الدفعات المولَّدة عليها (مجموعة batches، الحقل cloud_profile_id). */
    suspend fun getProfileStatistics(profileId: String): AppResult<Map<String, Any?>> = safeApiCall {
        val batches = firestore.collection("batches").whereEqualTo("cloud_profile_id", profileId).get().await()
        mapOf<String, Any?>(
            "batches_count" to batches.size(),
            "requested_cards" to batches.documents.sumOf { it.getLong("requested_quantity") ?: 0L }
        )
    }
}

package ps.mgtik.hotspot.domain.repository

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.usecase.PricingPolicy

/**
 * IProfileRepository - عقد إدارة البروفايلات والمزامنة (§4.1 - §4.8)
 * تم التحديث لدعم التنفيذ المباشر (skipQueue) لمنع حلقات التكرار في نظام الـ Offline.
 * skipQueue (معنى ثابت في كل المستودعات): false → تمر العملية عبر الطابور/WorkManager، true → تنفيذ مباشر بلا طابور.
 */
interface IProfileRepository {
    // عمليات البروفايل الأساسية (§4.1)
    fun getProfiles(includeArchived: Boolean = false, categoryId: String? = null): Flow<List<Profile>>
    fun getProfileById(profileId: String): Flow<Profile?>
    
    /**
     * @param linkExisting true = لا تُنشأ الباقة على الراوتر؛ تُربط بباقة موجودة عليه باسمها ([Profile.linkedProfileName])
     * وتُنشأ محلياً/سحابياً فقط. يفشل إن لم تكن موجودة على الراوتر.
     */
    suspend fun createCloudProfile(profile: Profile, idempotencyKey: String, skipQueue: Boolean = false, linkExisting: Boolean = false): AppResult<Unit>
    /** الباقات الموجودة فعلاً على الراوتر (للقائمة المنسدلة). */
    suspend fun getRouterProfileOptions(): AppResult<List<RouterProfileOption>>
    /** أسماء Limitations الموجودة على الراوتر (لاختيار مكوّن موجود/مشترك عند إنشاء باقة). */
    suspend fun getRouterLimitationNames(): AppResult<List<String>>
    suspend fun updateCloudProfile(profileId: String, fieldsToUpdate: Map<String, Any?>, idempotencyKey: String, skipQueue: Boolean = false): AppResult<Unit>
    suspend fun deleteCloudProfile(profileId: String, alsoDeleteFromRouter: Boolean, force: Boolean, idempotencyKey: String, skipQueue: Boolean = false): AppResult<Unit>

    // الفئات والتصنيفات (§4.2, §4.3)
    fun getProfileCategories(): Flow<List<ProfileCategory>>
    suspend fun saveProfileCategory(category: ProfileCategory, idempotencyKey: String): AppResult<Unit>
    suspend fun deleteProfileCategory(categoryId: String, idempotencyKey: String): AppResult<Unit>
    
    // تصنيفات الباقات التشغيلية: حفظها لا يغيّر بروفايلاً ولا مستخدماً؛ التطبيق على الموجودين صريح (معاينة ثم تنفيذ)
    fun getProfileClassifications(): Flow<List<ProfileClassification>>
    suspend fun saveProfileClassification(classification: ProfileClassification, idempotencyKey: String): AppResult<Unit>
    /** تعطيل/أرشفة/استعادة تصنيف: لا يحذف ربطات قائمة ولا يسمح بربطات جديدة بغير النشط. */
    suspend fun setClassificationStatus(key: String, status: CategoryStatus): AppResult<Unit>
    suspend fun countProfilesInClassification(key: String): AppResult<Int>
    /** ما سيتغير عند تطبيق التصنيف (بروفايلات + عدد المستخدمين المتأثرين إن قُرئ الراوتر). */
    suspend fun previewClassificationApply(key: String): AppResult<ClassificationApplyPlan>
    suspend fun applyClassification(key: String): AppResult<ClassificationApplyResult>

    // التسعير (§4.4, §4.5): المصدر الوحيد للسعر هو resolveQuote (فئة + سياق + أسعار المالكين)
    fun getPricingRules(profileId: String): Flow<List<PricingRule>>
    fun getCategoryPricingRules(categoryId: String): Flow<List<PricingRule>>
    /** كل الأسعار الخاصة لوكيل واحد (فئة + قديمة)، نشطة ومعطّلة، لعرض مصدر سعره في شاشته. */
    fun getOwnerPricingRules(ownerId: String): Flow<List<PricingRule>>
    /** السعر الذي ستُحاسَب به عملية جديدة؛ خطأ [PricingPolicy.ERR_PRICE_UNAVAILABLE] إن لم يوجد سعر صالح للسياق. */
    suspend fun resolveQuote(profileId: String, ownerId: String?, context: SaleContext): AppResult<PricingPolicy.Quote>
    /** إضافة/تعديل سعر خاص لمالك على مستوى الفئة (يسجَّل القديم والجديد في سجل التدقيق). */
    suspend fun saveCategoryOwnerPrice(categoryId: String, ownerId: String, context: SaleContext, priceMinor: Long, reason: String?): AppResult<Unit>
    /** تفعيل/تعطيل سعر خاص (فئة أو قديم على مستوى البروفايل). */
    suspend fun setPricingRuleActive(ruleId: String, active: Boolean): AppResult<Unit>
    suspend fun deletePricingRule(ruleId: String): AppResult<Unit>

    /** تعطيل/أرشفة/استعادة بروفايل. الاستعادة لا تتجاوز جاهزية السعر. */
    suspend fun setProfileStatus(profileId: String, status: ProfileStatus): AppResult<Unit>
    suspend fun setCategoryStatus(categoryId: String, status: CategoryStatus): AppResult<Unit>
    /** عدد البروفايلات المرتبطة بفئة (للعرض قبل الحذف). */
    suspend fun countProfilesInCategory(categoryId: String): AppResult<Int>

    /** عدّ فعلي (من الخادم) للمستخدمين/الاشتراكات/الدفعات المرتبطة بكل بروفايل؛ خطأ إن تعذّرت القراءة (لا أصفار بديلة). */
    suspend fun getProfileUsage(profileIds: List<String>): AppResult<Map<String, ps.mgtik.hotspot.domain.model.ProfileUsage>>

    /** سجل التعديلات التجارية للفئة (الأحدث أولاً). */
    fun getCategoryHistory(categoryId: String): Flow<List<ps.mgtik.hotspot.domain.model.CategoryHistoryEntry>>
    
    fun getProfileDefaults(profileId: String): Flow<ProfileDefaults?>
    suspend fun upsertProfileDefaults(defaults: ProfileDefaults, idempotencyKey: String): AppResult<Unit>

    // العدّاد التسلسلي الدائم لأسماء المستخدمين (يملكه البروفايل؛ مفتاحه معرّف البروفايل)
    fun observeUsernameSequences(): Flow<Map<String, Long>>
    /** تصفير العدّاد (يتبع تأكيد المستخدم في الواجهة). يضبط القيمة على 0 فلا يتكرر الأثر عند إعادة التنفيذ. */
    suspend fun resetUsernameSequence(profileId: String): AppResult<Unit>

    // المزامنة والترحيل والإحصائيات (§4.6, §4.7, §4.8)
    suspend fun reconcileRouterProfiles(): AppResult<Unit>
    /**
     * تغيير المرجع التقني (بروفايل MikroTik) للباقة. [repointUsers] يخصّ حالة الربط ببروفايل قائم فقط:
     * هل يُعاد ربط المستخدمين الحاليين (يغيّر خدمتهم فعلياً) أم يبقون كما هم. إعادة التسمية تُتبعهم دائماً.
     */
    suspend fun migrateProfileRename(profileId: String, newProfileName: String, idempotencyKey: String, skipQueue: Boolean = false, repointUsers: Boolean = true): AppResult<Unit>

    /** معاينة الأثر قبل الاعتماد. تتطلب اتصالاً بالراوتر؛ لا نعلن نجاح تحقق لم يحدث (يعيد خطأ إن تعذّر). */
    suspend fun previewReferenceChange(profileId: String, newProfileName: String): AppResult<ReferenceChangeImpact>
    suspend fun getProfileStatistics(profileId: String): AppResult<Map<String, Any?>>
}

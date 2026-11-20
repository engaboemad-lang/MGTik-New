package ps.mgtik.hotspot.data.repository

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ps.mgtik.hotspot.core.network.RouterConfig
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.data.cloud.source.CloudProfileDataSource
import ps.mgtik.hotspot.domain.repository.ISettingsRepository
import ps.mgtik.hotspot.data.local.cache.LocalCacheManager
import ps.mgtik.hotspot.core.network.string
import ps.mgtik.hotspot.data.offline.CommandCodec
import ps.mgtik.hotspot.data.offline.ProfileDeletePayload
import ps.mgtik.hotspot.data.offline.ProfileRenamePayload
import ps.mgtik.hotspot.data.offline.ProfileUpdatePayload
import ps.mgtik.hotspot.data.userman.UmPackage
import ps.mgtik.hotspot.data.userman.UmPackageSpec
import ps.mgtik.hotspot.data.userman.UmStartMode
import ps.mgtik.hotspot.data.userman.UmBurstDir
import ps.mgtik.hotspot.data.userman.UmComponentMode
import ps.mgtik.hotspot.data.userman.UmWrite
import ps.mgtik.hotspot.data.userman.UmLimitRules
import ps.mgtik.hotspot.data.userman.UmResetInterval
import ps.mgtik.hotspot.data.userman.UmSchedule
import ps.mgtik.hotspot.data.userman.UmWeekday
import ps.mgtik.hotspot.data.userman.UserManSource
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.usecase.ProfileInputValidator
import ps.mgtik.hotspot.domain.usecase.ProfileFieldRules
import ps.mgtik.hotspot.domain.usecase.RouterProfileImport
import ps.mgtik.hotspot.domain.usecase.UsageLimits
import ps.mgtik.hotspot.domain.usecase.SystemClassRules
import ps.mgtik.hotspot.domain.usecase.ClassificationRules
import ps.mgtik.hotspot.domain.usecase.CategoryRules
import ps.mgtik.hotspot.data.cloud.source.CloudUserDataSource
import ps.mgtik.hotspot.domain.usecase.PricingPolicy
import ps.mgtik.hotspot.domain.usecase.ReferenceChangePlanner
import ps.mgtik.hotspot.domain.repository.IProfileRepository
import ps.mgtik.hotspot.domain.repository.IOfflineCommandRepository
import ps.mgtik.hotspot.domain.repository.IOfflineScheduler
import ps.mgtik.hotspot.domain.repository.IAuditRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepositoryImpl @Inject constructor(
    private val cloudDataSource: CloudProfileDataSource,
    private val userMan: UserManSource,
    private val cloudUserDataSource: CloudUserDataSource,
    private val routerState: ps.mgtik.hotspot.domain.repository.IRouterStateManager,
    private val localCache: LocalCacheManager,
    private val offlineRepo: IOfflineCommandRepository,
    private val offlineScheduler: IOfflineScheduler,
    private val auditRepo: IAuditRepository
) : IProfileRepository {

    /**
     * الواجهة تقرأ من الكاش المحلي، وبمزامنة سحابية تعمل ما دام هناك مستمع فقط
     * (كانت تُطلق مستمعاً لا يتوقف عند كل استدعاء ⇒ تسريب وتكرار كتابات).
     */
    override fun getProfiles(includeArchived: Boolean, categoryId: String?): Flow<List<Profile>> = channelFlow {
        launch {
            cloudDataSource.getProfilesSnapshots(includeArchived, categoryId)
                .distinctUntilChanged() // تغيّر metadata وحده (بلا بيانات جديدة) لا يعيد كتابة Room
                .catch { e -> auditRepo.logAction("SYNC_ERROR", "Profiles refresh failed: ${e.message}", LogSeverity.ERROR) }
                .collect { snap ->
                    // الحفظ دائماً؛ التقليم فقط للقطة مؤكَّدة من الخادم وضمن نطاق الاستعلام (انظر syncProfiles):
                    // لا لقطة من كاش الـ SDK، ولا فارغة، ولا مفلترة بفئة، ولا حذف للمؤرشفة مع includeArchived=false
                    localCache.syncProfiles(
                        profiles = snap.profiles,
                        presentIds = snap.presentIds,
                        includeArchived = includeArchived,
                        categoryId = categoryId,
                        serverConfirmed = !snap.fromCache
                    )
                }
        }
        // القراءة المحلية بنفس نطاق الاستعلام: الكاش يحتفظ بالمؤرشفة الآن (لا يقلّمها includeArchived=false)،
        // فلا بدّ ألا تظهر في قائمة طُلبت بدونها، ولا باقات فئة أخرى في قائمة مفلترة بفئة.
        localCache.getCachedProfilesFlow()
            .map { cached ->
                cached.filter { p ->
                    (includeArchived || p.status != ProfileStatus.ARCHIVED) &&
                        (categoryId == null || p.categoryId == categoryId)
                }
            }
            .collect { send(it) }
    }

    /** إعداد راوتر صالح أو null (غير مُهيّأ). */
    private fun routerConfigOrNull(): RouterConfig? =
        routerState.status.value.config.takeIf { it.host.isNotBlank() }

    private fun noRouterConfig() = AppResult.Error(ApiError("NO_CONFIG", "Router connection is not configured"))

    /**
     * يستورد إعدادات [match] الفعلية إلى [cloud] (مزامنة): السرعة وعدد الأجهزة (بما فيه غير المحدود) والصلاحية وسياسة البداية وحدّا
     * الوقت/الحجم. ما لا يُمثَّل بدقة يبقى على قيمته المخزَّنة ويُضاف سببه إلى [partial] بدل تخزين قيمة تقريبية.
     * ownSettings لا تُمسّ: المزامنة الدورية لا تغيّر علاقة الباقة بتصنيفها.
     */
    private fun importFromRouter(cloud: Profile, match: UmPackage, partial: MutableList<String>): Profile {
        var option = routerOption(match)
        var limitsReliable = !match.limitationOrphaned
        repeat(2) {
            when (val r = RouterProfileImport.import(cloud, option, keepRateIfRouterUnlimited = true, limitsReliable = limitsReliable, claimOwnership = false)) {
                is RouterProfileImport.Result.Ok -> return r.profile
                is RouterProfileImport.Result.Rejected -> {
                    partial += "${cloud.id}: ${r.code}"
                    // يُستثنى الجزء غير القابل للتمثيل فقط ويُستورد الباقي
                    if (r.code == RouterProfileImport.ERR_VALIDITY_NOT_REPRESENTABLE) {
                        val own = RouterProfileImport.optionOf(cloud)
                        option = option.copy(validitySeconds = own.validitySeconds, validityUnlimited = own.validityUnlimited)
                    } else limitsReliable = false
                }
            }
        }
        return cloud
    }

    override suspend fun reconcileRouterProfiles(): AppResult<Unit> {
        val config = routerConfigOrNull() ?: return noRouterConfig()

        val routerProfilesRes = userMan.listPackages(config)
        if (routerProfilesRes is AppResult.Error) return AppResult.Error(routerProfilesRes.error)
        val routerProfiles = (routerProfilesRes as AppResult.Success).data

        val cloudProfiles = cloudDataSource.getProfiles(true, null).first()
        val categoriesById = cloudDataSource.getCategoriesOnce().associateBy { it.id }
        val partial = mutableListOf<String>()
        val updatedProfiles = cloudProfiles.map { cloud ->
            val match = routerProfiles.find { it.name == cloud.linkedProfileName }
            // المؤرشفة والمعطّلة إدارياً حالتان يقرّرهما المستخدم: المزامنة لا تعيد تفعيلهما ولا تغيّرهما
            val pinned = cloud.status == ProfileStatus.ARCHIVED || cloud.status == ProfileStatus.DISABLED
            if (match != null) {
                // وجودها على الراوتر لا يكفي لتفعيل باقة بلا سعر صالح (لا بيع بلا سعر): تبقى حالتها كما هي
                val activatable = !pinned && PricingPolicy.profilePriceReady(cloud.categoryId?.let { categoriesById[it] }, cloud.priceMinor)
                val imported = importFromRouter(cloud, match, partial)
                imported.copy(status = if (activatable) ProfileStatus.ACTIVE else cloud.status)
            } else {
                cloud.copy(status = if (pinned) cloud.status else ProfileStatus.MISSING)
            }
        }

        // السحابة هي مصدر الحقيقة: نكتب التغييرات فيها (وإلا ألغتها أول لقطة سحابية لاحقة) ثم نحدّث الكاش
        var firstError: AppResult.Error? = null
        updatedProfiles.zip(cloudProfiles).filter { (new, old) -> new != old }.forEach { (new, old) ->
            val fields = RouterProfileImport.cloudFields(old, new).toMutableMap()
            if (new.status != old.status) fields["status"] = new.status.name.lowercase()
            if (fields.isEmpty()) return@forEach
            val res = cloudDataSource.updateProfile(new.id, fields)
            if (res is AppResult.Error && firstError == null) firstError = res
        }
        if (partial.isNotEmpty()) {
            // قيم على الراوتر لا تُمثَّل بدقة في الباقة (مثلاً صلاحية ليست ساعات كاملة): لا تُخزَّن مقرَّبة، وتُسجَّل لتُعالَج يدوياً
            auditRepo.logAction("PROFILE_RECONCILE_PARTIAL", partial.take(20).joinToString("; "), LogSeverity.WARNING)
        }
        localCache.saveProfiles(updatedProfiles)
        return firstError ?: AppResult.Success(Unit)
    }

    // =====================================================================
    // عمليات الكتابة. المعنى الثابت لـ skipQueue في كل المشروع:
    //   false → تُسجَّل في الطابور ويتولاها WorkManager (OfflineCommandDispatcher)،
    //   true  → تنفيذ مباشر الآن (راوتر ثم سحابة ثم كاش) بلا طابور.
    // المنفّذ يستدعي دائماً بـ true. الحمولات في data/offline/CommandPayloads.kt.
    // =====================================================================

    private suspend fun queueCommand(type: OfflineCommandType, payload: String, key: String) {
        offlineRepo.addCommand(OfflineCommand(type = type, payloadJson = payload, idempotencyKey = key))
        offlineScheduler.scheduleSync()
    }

    private suspend fun cachedProfile(profileId: String): Profile? =
        localCache.getCachedProfilesFlow().first().find { it.id == profileId }

    override suspend fun getRouterLimitationNames(): AppResult<List<String>> {
        val config = routerConfigOrNull() ?: return noRouterConfig()
        return when (val res = userMan.listLimitations(config)) {
            is AppResult.Success -> AppResult.Success(res.data.map { it.name }.sortedBy { it.lowercase() })
            is AppResult.Error -> AppResult.Error(res.error)
            else -> AppResult.Error(ApiError.unexpectedClientError(IllegalStateException("Loading")))
        }
    }

    override suspend fun getRouterProfileOptions(): AppResult<List<RouterProfileOption>> {
        val config = routerConfigOrNull() ?: return noRouterConfig()
        return when (val res = userMan.listPackages(config)) {
            is AppResult.Success -> AppResult.Success(
                res.data.filter { it.name != "default" }.map { raw ->
                    routerOption(raw)
                }.sortedBy { it.name.lowercase() }
            )
            is AppResult.Error -> AppResult.Error(res.error)
            else -> AppResult.Error(ApiError.unexpectedClientError(IllegalStateException("Loading")))
        }
    }

    override suspend fun createCloudProfile(requested: Profile, idempotencyKey: String, skipQueue: Boolean, linkExisting: Boolean): AppResult<Unit> {
        // التصنيف التشغيلي: يجب أن يكون موجوداً ونشطاً، والبروفايل يرث منه ما لا يحدّده بنفسه (تُثبَّت القيم الفعلية في حقوله)
        val classification = requested.classificationKey?.let { cloudDataSource.getClassificationOnce(it) }
        if (requested.classificationKey != null) {
            ClassificationRules.checkAssignable(classification)?.let { return AppResult.Error(ApiError(it, "Classification is missing, disabled or archived")) }
        }
        val profile = ClassificationRules.materialize(requested.copy(ownSettings = requested.ownSettings ?: emptySet()), classification)
        // قيود الاستخدام تُتحقَّق قبل أي كتابة (طابور/راوتر/سحابة): قيمة صفرية أو خارج المدى لا تُحفظ
        val limitErrors = buildMap<String, String> {
            if (!UsageLimits.isUptimeValid(profile.limitUptimeSeconds)) put("limitUptime", "ERR_PROFILE_LIMIT_UPTIME_INVALID")
            if (!UsageLimits.isTransferValid(profile.limitTransferBytes)) put("limitTransfer", "ERR_PROFILE_LIMIT_TRANSFER_INVALID")
        }
        if (limitErrors.isNotEmpty()) {
            return AppResult.Error(ApiError.validationError("Profile usage limits are invalid", limitErrors))
        }
        // تحقق مركزي من بقية الحقول التشغيلية (أجهزة، سرعة، صلاحية، سياسة بداية): نفسه للمسار المباشر وللطابور، لا يعتمد على الواجهة.
        // وضع الربط مستثنى: قيمه تأتي من الراوتر نفسه وتُتحقَّق عند الاستيراد أدناه.
        if (!linkExisting) {
            ProfileFieldRules.validateProfile(profile).takeIf { it.isNotEmpty() }
                ?.let { return AppResult.Error(ApiError.validationError("Profile values are invalid", it)) }
        }
        // كل باقة جديدة تتبع فئة واحدة مصنّفة نظامياً (ترث تصنيفها منها)
        val newCategory = profile.categoryId?.let { cloudDataSource.getCategoryOnce(it) }
        // السعر يأتي من الفئة؛ سعر البروفايل القديم لا يُطلب إلا لفئة بلا أسعار تجارية
        if (!PricingPolicy.profilePriceReady(newCategory, profile.priceMinor)) {
            return AppResult.Error(ApiError.validationError("Category has no valid price", mapOf("price" to PricingPolicy.ERR_PRICE_INVALID)))
        }
        SystemClassRules.checkCategoryForNewProfile(profile.categoryId != null, newCategory)?.let { return AppResult.Error(ApiError(it, "Profile category is missing or unclassified")) }
        profile.categoryId?.let { categoryAssignable(it)?.let { err -> return err } }
        if (!skipQueue) {
            queueCommand(OfflineCommandType.CREATE_PROFILE, CommandCodec.encode(profile), idempotencyKey)
            return AppResult.Success(Unit)
        }

        val config = routerConfigOrNull() ?: return noRouterConfig()
        if (linkExisting) {
            // ربط بباقة موجودة: لا كتابة على الراوتر إطلاقاً؛ يجب أن تكون موجودة فعلاً، ولا تُربط بها باقة محلية ثانية
            val onRouter = (userMan.listPackages(config) as? AppResult.Success)?.data
                ?: return AppResult.Error(ApiError("ROUTER_UNREACHABLE", "Could not read router profiles"))
            val raw = onRouter.firstOrNull { it.name == profile.linkedProfileName }
                ?: return AppResult.Error(ApiError.validationError("Router profile '${profile.linkedProfileName}' does not exist"))
            val alreadyLinked = localCache.getCachedProfilesFlow().first().any { it.linkedProfileName.equals(profile.linkedProfileName, ignoreCase = true) }
            if (alreadyLinked) return AppResult.Error(ApiError.validationError("Another profile already uses this router name"))
            // الإعدادات المحفوظة = الفعلية على الراوتر مهما أرسلته الواجهة: تُقرأ الآن من الراوتر وتُكتب فوق قيم الطلب (لا قيم شكلية)
            val imported = when (val r = RouterProfileImport.import(profile, routerOption(raw), limitsReliable = !raw.limitationOrphaned)) {
                is RouterProfileImport.Result.Ok -> r.profile
                is RouterProfileImport.Result.Rejected -> return AppResult.Error(ApiError(r.code, r.detail))
            }
            val cloudRes = cloudDataSource.saveProfile(imported)
            if (cloudRes is AppResult.Success) localCache.saveProfiles(listOf(imported))
            return cloudRes
        }
        // الراوتر أولاً كي لا تبقى باقة سحابية يتيمة إن فشل. إعادة التنفيذ (من الطابور) آمنة:
        // وجود الاسم على الراوتر مسبقاً يعني أن محاولة سابقة وصلته، فنكمل للسحابة.
        val spec = profile.toPackageSpec(retailMinor = retailMinor(newCategory, profile))
        // Limitation جديدة باسم يدوي لا تُكتب فوق Limitation موجودة (قد تكون مشتركة): الموجودة تُختار كمرجع صراحةً
        if (spec.limitationMode == UmComponentMode.MANAGE && spec.limitationNames.isNotEmpty()) {
            val profileExists = (userMan.listPackages(config) as? AppResult.Success)?.data?.any { it.name == spec.name } == true
            if (!profileExists) {
                val existing = (userMan.listLimitations(config) as? AppResult.Success)?.data?.map { it.name.lowercase() }?.toSet().orEmpty()
                val clash = spec.limitationNames.firstOrNull { it.lowercase() in existing }
                if (clash != null) return AppResult.Error(ApiError.validationError("Limitation '$clash' already exists on the router; select it as an existing component"))
            }
        }
        val routerRes = userMan.upsertPackage(config, spec)
        if (routerRes is AppResult.Error && !isCompleteOnRouter(config, spec)) return routerRes
        // المرجع التشغيلي ما يُقرأ من الراوتر بعد الكتابة، لا نجاح الأمر ولا حفظ السحابة
        verifyOnRouter(config, spec)?.let { return AppResult.Error(it) }

        val cloudRes = cloudDataSource.saveProfile(profile)
        if (cloudRes is AppResult.Success) localCache.saveProfiles(listOf(profile))
        return cloudRes
    }

    /**
     * هل الباقة على الراوتر مكتملة ومطابقة للمواصفة (قيم + رابط Limitation)؟ لتمييز «وصلت محاولة سابقة كاملة» عن رفض حقيقي أو
     * إنشاء جزئي (بروفايل بلا رابط قيود): وجود الاسم وحده لا يكفي، وإلا تُحفظ في السحابة باقة لا تُفرض قيودها على الراوتر.
     */
    private suspend fun isCompleteOnRouter(config: RouterConfig, spec: UmPackageSpec): Boolean =
        (userMan.verifyPackage(config, spec) as? AppResult.Success)?.data?.ok == true

    /** null = الراوتر يطابق المواصفة؛ وإلا خطأ يصف الفروق (أو تعذّر القراءة). */
    private suspend fun verifyOnRouter(config: RouterConfig, spec: UmPackageSpec): ApiError? =
        when (val v = userMan.verifyPackage(config, spec)) {
            is AppResult.Success -> if (v.data.ok) null else ApiError(ERR_ROUTER_VERIFY_MISMATCH, "Router does not match after write: ${v.data.describe()}", retryable = true)
            is AppResult.Error -> v.error
            else -> ApiError.unexpectedClientError(IllegalStateException("Loading"))
        }

    override suspend fun deleteCloudProfile(profileId: String, alsoDeleteFromRouter: Boolean, force: Boolean, idempotencyKey: String, skipQueue: Boolean): AppResult<Unit> {
        if (!skipQueue) {
            val payload = ProfileDeletePayload(profileId, alsoDeleteFromRouter, force)
            queueCommand(OfflineCommandType.DELETE_PROFILE, CommandCodec.encode(payload), idempotencyKey)
            return AppResult.Success(Unit)
        }

        // محذوفة مسبقاً (إعادة تنفيذ من الطابور) = نجاح، لا خطأ
        val profile = cachedProfile(profileId) ?: return AppResult.Success(Unit)

        // سلامة البيانات: بروفايل له دفعات/اشتراكات لا يُحذف نهائياً (حتى مع force)؛ الأرشفة هي الخيار
        when (val refs = cloudDataSource.countProfileReferences(profileId)) {
            is AppResult.Error -> return refs
            is AppResult.Success -> if (refs.data > 0) return AppResult.Error(
                ApiError(ERR_PROFILE_IN_USE, "Profile is referenced by batches or subscriptions; archive it instead")
            )
            else -> Unit
        }

        // الراوتر أولاً؛ إن فشل (ولم يكن force) لا نحذف شيئاً. الباقة المفقودة أصلاً من الراوتر لا تحتاج حذفاً منه.
        if (alsoDeleteFromRouter && profile.status != ProfileStatus.MISSING) {
            val config = routerConfigOrNull()
            if (config == null) {
                if (!force) return noRouterConfig()
            } else {
                // حارس الراوتر: باقة عليها مستخدمون فعليون لا تُحذف (حتى مع force) كي لا يفقدوا باقتهم.
                when (val on = userMan.usersOnPackage(config, profile.linkedProfileName)) {
                    is AppResult.Error -> if (!force) return on
                    is AppResult.Success -> if (on.data.isNotEmpty()) return AppResult.Error(
                        ApiError(ERR_PROFILE_HAS_ROUTER_USERS, "Package has ${on.data.size} users on the router; move or delete them first")
                    )
                    else -> Unit
                }
                val routerRes = userMan.deletePackage(config, profile.linkedProfileName)
                if (routerRes is AppResult.Error && !force) return routerRes
            }
        }

        val cloudRes = cloudDataSource.deleteProfile(profileId)
        if (cloudRes is AppResult.Success) localCache.removeProfile(profileId)
        return cloudRes
    }

    /**
     * تعديل حقول باقة. المفاتيح بأسماء حقول Firestore (انظر UPDATABLE_FIELDS)؛ تغيير الاسم التقني له
     * migrateProfileRename. الحقول المؤثرة على الراوتر (السرعة، الأجهزة) تُطبَّق عليه أولاً ثم السحابة.
     */
    override suspend fun updateCloudProfile(profileId: String, requestedFields: Map<String, Any?>, idempotencyKey: String, skipQueue: Boolean): AppResult<Unit> {
        var fieldsToUpdate = requestedFields
        if (fieldsToUpdate.isEmpty()) return AppResult.Success(Unit)
        val unknown = fieldsToUpdate.keys - UPDATABLE_FIELDS
        if (unknown.isNotEmpty()) {
            return AppResult.Error(ApiError.validationError("Fields not updatable: ${unknown.sorted().joinToString()}"))
        }
        // تحقق مركزي من النوع والمدى: يسري على الحفظ المباشر وعلى ما يصل من الطابور (المنفّذ يعيد استدعاء هذه الدالة)،
        // فلا تُكتب قيمة شكلية (صفر، سالب، نص بدل رقم) على الراوتر ولا السحابة مهما كان مصدر الطلب.
        ProfileFieldRules.validateFields(fieldsToUpdate).takeIf { it.isNotEmpty() }
            ?.let { return AppResult.Error(ApiError.validationError("Profile values are invalid", it)) }

        if ("price_minor" in fieldsToUpdate) {
            val p = (fieldsToUpdate["price_minor"] as? Number)?.toLong()
            if (p == null || !PricingPolicy.isPriceValid(p)) {
                return AppResult.Error(ApiError.validationError("Base price is invalid", mapOf("price" to PricingPolicy.ERR_PRICE_INVALID)))
            }
        }
        // ربط تصنيف جديد: موجود ونشط (بقاء ربط بتصنيف مؤرشف دون تغيير لا يمنع حفظ بقية الحقول). مستقل تماماً عن الفئة والسعر.
        if ("classification_key" in fieldsToUpdate) {
            val target = fieldsToUpdate["classification_key"] as? String
            val current = cloudDataSource.getProfileOnce(profileId)?.classificationKey
            if (target != null && target != current) {
                ClassificationRules.checkAssignable(cloudDataSource.getClassificationOnce(target))
                    ?.let { return AppResult.Error(ApiError(it, "Classification is missing, disabled or archived")) }
            }
        }
        // نقل بروفايل إلى فئة: يجب أن تكون موجودة ونشطة (إزالة الفئة = null مسموحة). لا مساس بالراوتر ولا بالأسعار.
        (fieldsToUpdate["category_id"] as? String)?.let { categoryAssignable(it)?.let { err -> return err } }
        if ("category_id" in fieldsToUpdate) {
            // الباقة تبقى تابعة لفئة، ولا تنتقل إلى فئة بتصنيف نظامي مختلف (فلا يتغير نوعها بأثر رجعي)
            val target = fieldsToUpdate["category_id"] as? String
                ?: return AppResult.Error(ApiError(SystemClassRules.ERR_PROFILE_CATEGORY_REQUIRED, "A profile must belong to a category"))
            val from = cloudDataSource.getProfileOnce(profileId)?.categoryId?.let { cloudDataSource.getCategoryOnce(it)?.systemClass }
            val to = cloudDataSource.getCategoryOnce(target)?.systemClass
            if (!SystemClassRules.moveAllowed(from, to)) {
                return AppResult.Error(ApiError(SystemClassRules.ERR_PROFILE_MOVE_CLASS_MISMATCH, "Cannot move a profile to a category with a different system class"))
            }
        }

        // اتساق الحقول مع الحالة الحالية (مثلاً صلاحية غير محدودة مع ساعات): يُرفض قبل التسجيل في الطابور أيضاً
        cachedProfile(profileId)?.let { cur ->
            ProfileFieldRules.validateCombination(cur.withFields(fieldsToUpdate), fieldsToUpdate.keys).takeIf { it.isNotEmpty() }
                ?.let { return AppResult.Error(ApiError.validationError("Profile values are invalid", it)) }
        }

        if (!skipQueue) {
            val payload = ProfileUpdatePayload(profileId, CommandCodec.fieldsToJson(fieldsToUpdate))
            queueCommand(OfflineCommandType.UPDATE_PROFILE, CommandCodec.encode(payload), idempotencyKey)
            return AppResult.Success(Unit)
        }

        val profile = cachedProfile(profileId) ?: return AppResult.Error(ApiError("NOT_FOUND", "Profile not found locally"))
        var updated = profile.withFields(fieldsToUpdate)

        // الوراثة: بعد التعديل تُثبَّت القيم الفعلية (الموروثة من التصنيف أو الخاصة) في حقول البروفايل التشغيلية،
        // فيقرؤها التوليد والراوتر كما هي. الحقول المالية والفئة لا تدخل هذا الحساب أبداً.
        if (fieldsToUpdate.keys.any { it in INHERITANCE_FIELDS }) {
            val classification = updated.classificationKey?.let { cloudDataSource.getClassificationOnce(it) }
            val effective = ClassificationRules.materialize(updated.copy(ownSettings = updated.ownSettings ?: emptySet()), classification)
            val derived = buildMap<String, Any?> {
                if (effective.rateLimitRxKbps != updated.rateLimitRxKbps) put("rate_limit_rx_kbps", effective.rateLimitRxKbps)
                if (effective.rateLimitTxKbps != updated.rateLimitTxKbps) put("rate_limit_tx_kbps", effective.rateLimitTxKbps)
                if (effective.sharedUsers != updated.sharedUsers) put("shared_users", effective.sharedUsers)
                if (effective.limitUptimeSeconds != updated.limitUptimeSeconds) put("limit_uptime_seconds", effective.limitUptimeSeconds)
                if (effective.limitTransferBytes != updated.limitTransferBytes) put("limit_transfer_bytes", effective.limitTransferBytes)
            }
            fieldsToUpdate = fieldsToUpdate + derived
            updated = effective
        }

        // إعادة الفحص عند التنفيذ بحالة الباقة الحالية (قد تغيّرت منذ التسجيل في الطابور)
        ProfileFieldRules.validateCombination(updated, fieldsToUpdate.keys).takeIf { it.isNotEmpty() }
            ?.let { return AppResult.Error(ApiError.validationError("Profile values are invalid", it)) }

        val touchesRouter = fieldsToUpdate.keys.any { it in ROUTER_FIELDS }
        if (touchesRouter && profile.status != ProfileStatus.MISSING) {
            val config = routerConfigOrNull() ?: return noRouterConfig()
            val specU = updated.copy(linkedProfileName = profile.linkedProfileName).toPackageSpec(fieldsToUpdate.keys)
            val routerRes = userMan.upsertPackage(config, specU)
            if (routerRes is AppResult.Error) return routerRes
            verifyOnRouter(config, specU)?.let { return AppResult.Error(it) }
        }

        // own_settings يُنقل نصاً في الطابور؛ Firestore يخزّنه قائمة
        val cloudFields = fieldsToUpdate["own_settings"]?.let { raw ->
            fieldsToUpdate + ("own_settings" to ClassificationRules.ownSettingsFromCsv(raw as? String)?.map { it.wire })
        } ?: fieldsToUpdate
        val cloudRes = cloudDataSource.updateProfile(profileId, cloudFields)
        if (cloudRes is AppResult.Success) {
            localCache.saveProfiles(listOf(updated))
            if (fieldsToUpdate.keys.any { it == "category_id" || it == "price_minor" }) {
                pushRetailPrice(updated, updated.categoryId?.let { cloudDataSource.getCategoryOnce(it) })
            }
        }
        return cloudRes
    }

    /** الإعدادات الفعلية لباقة الراوتر كاملة (السرعة، الأجهزة بما فيه غير المحدود، الصلاحية، سياسة البداية، الحدود). */
    private fun routerOption(raw: UmPackage): RouterProfileOption = RouterProfileOption(
        name = raw.name,
        rateLimitRxKbps = raw.rxKbps,
        rateLimitTxKbps = raw.txKbps,
        sharedUsers = raw.sharedUsers,
        sharedUnlimited = raw.sharedUnlimited,
        validitySeconds = raw.validitySeconds,
        validityUnlimited = raw.validityUnlimited,
        validityStart = raw.startMode?.let { if (it == UmStartMode.ON_ASSIGN) VALIDITY_START_ASSIGNED else VALIDITY_START_FIRST_AUTH },
        limitUptimeSeconds = raw.uptimeSeconds,
        limitTransferBytes = raw.transferBytes
    )

    /**
     * باقة Cloud ← مواصفة باقة UserMan. الحصة: limitTransferBytes أولاً، وإلا quotaMb (1 ميغابايت = 1048576 بايت).
     * [touched] = مفاتيح الحقول التي غيّرها المستخدم (null = إنشاء: تُكتب كل القيم المحددة). عند التعديل لا يُكتب إلا ما لُمس،
     * فلا يُصفَّر حد وُضع على الراوتر مباشرة ولا تُستبدل قيمة بافتراضية؛ الحد الممسوح صراحةً (مفتاحه ملموس وقيمته null) يُرسل 0.
     */
    private fun Profile.toPackageSpec(touched: Set<String>? = null, retailMinor: Long? = null): UmPackageSpec {
        fun t(vararg keys: String) = touched == null || keys.any { it in touched }
        // الإنشاء يكتب كل ما حُدِّد (بما فيه حدّا التنزيل/الرفع وBurst والأولوية والتصفير والجدولة والسعر)؛ التعديل لا يكتب إلا ما لُمس
        val writes: Set<UmWrite> = if (touched == null) UmWrite.ALL else buildSet {
            if (t("rate_limit_rx_kbps", "rate_limit_tx_kbps")) add(UmWrite.SPEED)
            if (t("shared_users", "shared_unlimited")) add(UmWrite.SHARED_USERS)
            if (t("validity_hours", "validity_unlimited")) add(UmWrite.VALIDITY)
            if (t("validity_start")) add(UmWrite.START_MODE)
            if (t("limit_uptime_seconds")) add(UmWrite.UPTIME_LIMIT)
            if (t("limit_transfer_bytes", "quota_mb")) add(UmWrite.TRANSFER_LIMIT)
        }
        return UmPackageSpec(
            name = linkedProfileName,
            rxKbps = rateLimitRxKbps,
            txKbps = rateLimitTxKbps,
            sharedUsers = sharedUsers,
            sharedUnlimited = sharedUnlimited,
            startMode = if (validityStart == "assigned") UmStartMode.ON_ASSIGN else UmStartMode.FIRST_AUTH,
            validityUnlimited = validityUnlimited,
            uptimeSeconds = if (touched == null) limitUptimeSeconds else (limitUptimeSeconds ?: 0L),
            transferBytes = if (touched == null) ownTransferBytes else (ownTransferBytes ?: 0L),
            // 0 ساعة = غير محددة (لا تُكتب)؛ غير المحدودة تأتي صراحةً من validityUnlimited
            validitySeconds = validityHours.takeIf { it > 0 }?.let { it * 3_600L },
            // اختيار المكوّنات يخص الإنشاء فقط؛ التعديل لا يغيّر روابط Limitations (تُدار من شاشتها)
            limitationNames = if (touched == null) limitationNames else emptyList(),
            limitationMode = if (touched == null && limitationsReference) UmComponentMode.REFERENCE else UmComponentMode.MANAGE,
            writes = writes,
            downloadBytes = limitExtras?.takeIf { touched == null }?.downloadBytes,
            uploadBytes = limitExtras?.takeIf { touched == null }?.uploadBytes,
            burstRx = limitExtras?.takeIf { touched == null && it.burstRxKbps > 0 }?.let { UmBurstDir(it.burstRxKbps, it.burstRxThresholdKbps, it.burstRxTimeSeconds) },
            burstTx = limitExtras?.takeIf { touched == null && it.burstTxKbps > 0 }?.let { UmBurstDir(it.burstTxKbps, it.burstTxThresholdKbps, it.burstTxTimeSeconds) },
            // الأولوية الافتراضية (8) والتصفير المعطَّل والجدولة الفارغة لا تُرسل: لا نفرض شيئاً لم يخترْه المستخدم
            priority = limitExtras?.takeIf { touched == null }?.priority?.takeIf { it != UmLimitRules.PRIORITY_DEFAULT },
            resetInterval = limitExtras?.takeIf { touched == null }?.resetInterval?.let { UmResetInterval.fromWire(it) }?.takeIf { it != UmResetInterval.DISABLED },
            schedule = limitExtras?.takeIf { touched == null }?.toSchedule()?.takeIf { !it.isAlwaysOn },
            priceMinor = retailMinor.takeIf { touched == null }
        )
    }

    private fun PackageLimitExtras.toSchedule(): UmSchedule? {
        val from = scheduleFromSeconds ?: return null
        val till = scheduleTillSeconds ?: return null
        val days = scheduleDays.split(',').mapNotNull { UmWeekday.fromWire(it) }.toSet().ifEmpty { UmWeekday.ALL }
        return UmSchedule(from, till, days)
    }

    /** سعر التجزئة المعتمد في MGTik: فئة الباقة أولاً، وإلا سعر البروفايل القديم؛ null = لا سعر صالح فلا يُدفع شيء. */
    private fun retailMinor(category: ProfileCategory?, profile: Profile): Long? =
        (category?.retailPriceMinor ?: profile.priceMinor).takeIf { it != null && it > 0L }

    /**
     * يدفع سعر التجزئة لحقل price على Profile القائم على الراوتر (v6 وv7). أفضل جهد: MGTik هو المرجع المالي، فلا يفشل حفظ
     * الباقة/الفئة بسببه؛ النتيجة تُسجَّل في سجل العمليات (نجاح مُتحقَّق منه بإعادة القراءة، أو تحذير).
     */
    private suspend fun pushRetailPrice(profile: Profile, category: ProfileCategory?) {
        if (profile.status == ProfileStatus.MISSING || profile.linkedProfileName.isBlank()) return
        val minor = retailMinor(category, profile) ?: return
        val config = routerConfigOrNull() ?: return
        val outcome = when (val r = userMan.setProfilePrice(config, profile.linkedProfileName, minor)) {
            is AppResult.Success -> when {
                r.data == null -> "skipped: profile not on router"
                r.data.priceMinor != null && r.data.priceMinor != minor -> "MISMATCH want=$minor got=${r.data.priceMinor}"
                else -> "ok price=$minor"
            }
            is AppResult.Error -> "FAILED ${r.error.code}"
            else -> "FAILED"
        }
        auditRepo.logAction("PROFILE_PRICE_SYNC", "profile=${profile.id} router=${profile.linkedProfileName} $outcome",
            if (outcome.startsWith("ok") || outcome.startsWith("skipped")) LogSeverity.INFO else LogSeverity.WARNING)
    }

    private suspend fun usedByOtherProfile(profileId: String, name: String): Boolean =
        localCache.getCachedProfilesFlow().first().any { it.id != profileId && it.linkedProfileName.equals(name, ignoreCase = true) }

    /**
     * معاينة أثر تغيير المرجع التقني قبل الاعتماد: ماذا سيحدث على الراوتر، كم مستخدماً مرتبطاً بالمرجع القديم،
     * كم دفعة سابقة (لا تتغير)، وهل تختلف إعدادات المرجع الموجود عن المخزَّنة. القرار من [ReferenceChangePlanner] نفسه
     * الذي يستعمله التنفيذ. تعذّر قراءة الراوتر = خطأ (لا نعلن تحققاً لم يحدث).
     */
    override suspend fun previewReferenceChange(profileId: String, newProfileName: String): AppResult<ReferenceChangeImpact> {
        val newName = newProfileName.trim()
        val profile = cachedProfile(profileId) ?: return AppResult.Error(ApiError("NOT_FOUND", "Profile not found locally"))
        val config = routerConfigOrNull() ?: return noRouterConfig()
        val routerProfiles = when (val res = userMan.listPackages(config)) {
            is AppResult.Success -> res.data
            is AppResult.Error -> return AppResult.Error(res.error)
            else -> return AppResult.Error(ApiError.unexpectedClientError(IllegalStateException("Loading")))
        }
        val oldName = profile.linkedProfileName
        val plan = ReferenceChangePlanner.plan(
            oldName, newName,
            oldOnRouter = routerProfiles.any { it.name == oldName },
            newOnRouter = routerProfiles.any { it.name == newName },
            usedByOtherProfile = usedByOtherProfile(profileId, newName)
        )
        val users = (userMan.listUsers(config) as? AppResult.Success)?.data
            ?.count { it.packageName == oldName }
        val batches = (cloudDataSource.countBatchesForProfile(profileId) as? AppResult.Success)?.data
        val target = routerProfiles.firstOrNull { it.name == newName }?.let { routerOption(it) }
        // المقارنة تشمل كل ما سيُستورد: السرعة، الأجهزة (غير المحدود ≠ عدد)، الصلاحية، سياسة البداية، حدّا الوقت/الحجم
        val current = RouterProfileImport.optionOf(profile)
        val differing = target?.let { RouterProfileImport.differing(current, it) }.orEmpty()
        // ما لا يمكن استيراده بدقة يُكشف هنا قبل الاعتماد (نفس الدالة التي ينفّذ بها الاعتماد)
        val rejection = if (plan.action == ReferenceChangeAction.REPOINT_TO_EXISTING) {
            routerProfiles.firstOrNull { it.name == newName }?.let { raw ->
                (RouterProfileImport.import(profile, routerOption(raw), limitsReliable = !raw.limitationOrphaned) as? RouterProfileImport.Result.Rejected)?.code
            }
        } else null
        return AppResult.Success(
            ReferenceChangeImpact(
                profileId = profileId, oldName = oldName, newName = newName,
                action = plan.action, block = plan.block,
                usersOnOldProfile = users, batchesCount = batches,
                currentSettings = current, targetSettings = target,
                settingsDiffer = differing.isNotEmpty(),
                differingSettings = differing,
                importProblem = rejection
            )
        )
    }

    /**
     * تغيير المرجع التقني للباقة (الراوتر والسحابة والكاش) وفق [ReferenceChangePlanner]، ويُعاد تقييمه لحظة التنفيذ
     * ببيانات الراوتر الحالية (لا نثق بمعاينة قديمة).
     *  - إعادة تسمية: يُعاد تسمية بروفايل الراوتر ويتبعه المستخدمون (نعيد ربطهم صراحةً؛ لا نفترض تحديث RouterOS للمراجع).
     *  - ربط ببروفايل قائم: القديم لا يُمسّ؛ المستخدمون الحاليون يُعاد ربطهم فقط إن طُلب ([repointUsers])، لأن ذلك يغيّر
     *    خدمتهم. إعدادات السرعة/الأجهزة المخزَّنة تُزامَن من البروفايل الموجود كي لا تصف الواجهة خدمة غير فعلية.
     *  - الأسعار والفئة والدفعات والبطاقات السابقة لا تتغير في أي حالة. إعادة التنفيذ بعد نجاح جزئي آمنة.
     */
    override suspend fun migrateProfileRename(profileId: String, newProfileName: String, idempotencyKey: String, skipQueue: Boolean, repointUsers: Boolean): AppResult<Unit> {
        val newName = newProfileName.trim()
        if (!ProfileInputValidator.isRouterNameValid(newName) || newName.equals("default", ignoreCase = true)) {
            return AppResult.Error(ApiError.validationError("Invalid router profile name"))
        }

        if (!skipQueue) {
            queueCommand(OfflineCommandType.MIGRATE_PROFILE, CommandCodec.encode(ProfileRenamePayload(profileId, newName, repointUsers)), idempotencyKey)
            return AppResult.Success(Unit)
        }

        val profile = cachedProfile(profileId) ?: return AppResult.Error(ApiError("NOT_FOUND", "Profile not found locally"))
        if (profile.linkedProfileName == newName) return AppResult.Success(Unit) // نُفِّذ سابقاً

        val oldName = profile.linkedProfileName
        val config = routerConfigOrNull() ?: return noRouterConfig()
        val routerProfiles = when (val res = userMan.listPackages(config)) {
            is AppResult.Success -> res.data
            is AppResult.Error -> return res
            else -> return AppResult.Error(ApiError.unexpectedClientError(IllegalStateException("Loading")))
        }
        val plan = ReferenceChangePlanner.plan(
            oldName, newName,
            oldOnRouter = routerProfiles.any { it.name == oldName },
            newOnRouter = routerProfiles.any { it.name == newName },
            usedByOtherProfile = usedByOtherProfile(profileId, newName)
        )
        if (plan.action == ReferenceChangeAction.BLOCKED) {
            return AppResult.Error(ApiError("ERR_REFERENCE_${plan.block}", "Reference change blocked: ${plan.block}"))
        }

        val fields = mutableMapOf<String, Any?>("linked_profile_name" to newName)
        var updated = profile.copy(linkedProfileName = newName)
        when (plan.action) {
            ReferenceChangeAction.RENAME_ROUTER_PROFILE -> {
                // UserMan: المستخدمون مرتبطون بالباقة نفسها، فإعادة التسمية تكفي ولا حاجة لإعادة توجيههم.
                val renamed = userMan.renamePackage(config, oldName, newName)
                if (renamed is AppResult.Error) return renamed
            }
            ReferenceChangeAction.REPOINT_TO_EXISTING -> {
                if (repointUsers) {
                    // إسناد باقة جديدة لكل مستخدم في UserMan يعني بدء فترة جديدة (تجديداً)، لذلك لا يُنفَّذ تلقائياً.
                    return AppResult.Error(ApiError(ERR_REPOINT_NOT_SUPPORTED, "Moving users to another package renews them in User Manager; not supported"))
                }
                // الإعدادات المخزَّنة تصبح إعدادات المرجع الموجود الفعلية كاملة (سرعة، أجهزة بما فيه غير المحدود، صلاحية، سياسة بداية،
                // حدّا الوقت/الحجم) كي لا تصف الواجهة والتوليد خدمة غير فعلية. ما لا يُمثَّل بدقة يوقف العملية قبل أي كتابة.
                routerProfiles.firstOrNull { it.name == newName }?.let { raw ->
                    when (val r = RouterProfileImport.import(updated, routerOption(raw), limitsReliable = !raw.limitationOrphaned)) {
                        is RouterProfileImport.Result.Rejected -> return AppResult.Error(ApiError(r.code, r.detail))
                        is RouterProfileImport.Result.Ok -> {
                            fields += RouterProfileImport.cloudFields(updated, r.profile)
                            updated = r.profile
                        }
                    }
                }
                // باقة كانت «مفقودة» على الراوتر وصار مرجعها موجوداً: تعود نشطة فقط إن كان سعرها صالحاً
                if (profile.status == ProfileStatus.MISSING && PricingPolicy.profilePriceReady(profile.categoryId?.let { cloudDataSource.getCategoryOnce(it) }, profile.priceMinor)) {
                    fields["status"] = ProfileStatus.ACTIVE.name.lowercase()
                    updated = updated.copy(status = ProfileStatus.ACTIVE)
                }
            }
            ReferenceChangeAction.BLOCKED -> Unit
        }

        val cloudRes = cloudDataSource.updateProfile(profileId, fields)
        if (cloudRes is AppResult.Success) {
            localCache.saveProfiles(listOf(updated))
            auditRepo.logAction("PROFILE_REFERENCE", "profile=$profileId $oldName -> $newName action=${plan.action} repointUsers=$repointUsers", LogSeverity.INFO)
        }
        return cloudRes
    }


    private fun Profile.withFields(fields: Map<String, Any?>): Profile {
        fun int(key: String) = (fields[key] as? Number)?.toInt()
        return copy(
            displayNameAr = fields["display_name_ar"] as? String ?: displayNameAr,
            displayNameEn = fields["display_name_en"] as? String ?: displayNameEn,
            description = if ("description" in fields) fields["description"] as? String else description,
            categoryId = if ("category_id" in fields) fields["category_id"] as? String else categoryId,
            classificationKey = if ("classification_key" in fields) fields["classification_key"] as? String else classificationKey,
            priceMinor = (fields["price_minor"] as? Number)?.toLong() ?: priceMinor,
            sharedUsers = int("shared_users") ?: sharedUsers,
            rateLimitRxKbps = int("rate_limit_rx_kbps") ?: rateLimitRxKbps,
            rateLimitTxKbps = int("rate_limit_tx_kbps") ?: rateLimitTxKbps,
            validityHours = int("validity_hours") ?: validityHours,
            quotaMb = int("quota_mb") ?: quotaMb,
            // الحقل الموجود بقيمة null يمسح القيد (غير مفروض)؛ الغائب يُبقي الحالي
            limitUptimeSeconds = if ("limit_uptime_seconds" in fields) (fields["limit_uptime_seconds"] as? Number)?.toLong() else limitUptimeSeconds,
            limitTransferBytes = if ("limit_transfer_bytes" in fields) (fields["limit_transfer_bytes"] as? Number)?.toLong() else limitTransferBytes,
            ownSettings = if ("own_settings" in fields) ClassificationRules.ownSettingsFromCsv(fields["own_settings"] as? String) else ownSettings,
            validityStart = (fields["validity_start"] as? String)?.takeIf { it == "first_auth" || it == "assigned" } ?: validityStart,
            validityUnlimited = fields["validity_unlimited"] as? Boolean ?: validityUnlimited,
            sharedUnlimited = fields["shared_unlimited"] as? Boolean ?: sharedUnlimited
        )
    }

    override fun getProfileById(profileId: String): Flow<Profile?> = localCache.getCachedProfilesFlow().map { list -> list.find { it.id == profileId } }
    override fun getProfileCategories(): Flow<List<ProfileCategory>> = cloudDataSource.getCategories()
    override suspend fun saveProfileCategory(category: ProfileCategory, idempotencyKey: String): AppResult<Unit> {
        val name = PricingPolicy.normalizeName(category.nameAr)
        if (name.isEmpty()) return AppResult.Error(ApiError.validationError("Category name is required"))
        // القيم التجارية (العملة للجديدة، الأسعار) تُتحقَّق قبل الكتابة؛ ثبات النوع والعملة تفرضه معاملة الحفظ
        CategoryRules.validate(category, isNew = category.id.isBlank()).takeIf { it.isNotEmpty() }
            ?.let { return AppResult.Error(ApiError.validationError("Category values are invalid", it)) }
        // لا فئتان بالاسم نفسه (بعد توحيد المسافات والحالة) — يشمل المعطّلة والمؤرشفة
        val clash = cloudDataSource.getCategoriesOnce().any {
            it.id != category.id && (PricingPolicy.normalizeName(it.nameAr) == name ||
                (category.nameEn.isNotBlank() && PricingPolicy.normalizeName(it.nameEn) == PricingPolicy.normalizeName(category.nameEn)))
        }
        if (clash) return AppResult.Error(ApiError(ERR_CATEGORY_NAME_EXISTS, "A category with this name already exists"))
        val res = cloudDataSource.saveCategory(category.copy(nameAr = category.nameAr.trim().replace(Regex("\\s+"), " ")))
        if (res is AppResult.Success && category.id.isNotBlank()) {
            // تغيّر سعر التجزئة ينعكس على حقل price لكل باقة في الفئة (أفضل جهد، لا يؤثر على نتيجة الحفظ)
            localCache.getCachedProfilesFlow().first().filter { it.categoryId == category.id }.forEach { pushRetailPrice(it, category) }
        }
        if (res is AppResult.Success) auditRepo.logAction(
            "CATEGORY_SAVE", "category=${category.id} retail=${category.retailPriceMinor ?: "-"} wholesale=${category.wholesalePriceMinor ?: "-"}", LogSeverity.INFO
        )
        return res
    }

    override suspend fun deleteProfileCategory(categoryId: String, idempotencyKey: String): AppResult<Unit> {
        // الحذف النهائي فقط لفئة بلا بروفايلات؛ لا يحذف بروفايلاً تلقائياً. غير ذلك: انقل البروفايلات أو أرشف الفئة.
        when (val n = cloudDataSource.countProfilesInCategory(categoryId)) {
            is AppResult.Error -> return n
            is AppResult.Success -> if (n.data > 0) return AppResult.Error(
                ApiError(ERR_CATEGORY_NOT_EMPTY, "Category still has ${n.data} profile(s); move them or archive the category")
            )
            else -> Unit
        }
        // أسعار المالكين المرتبطة بالفئة تُحذف صراحةً أولاً (لا يتيم لها بحذف صامت)
        when (val r = cloudDataSource.countCategoryRules(categoryId)) {
            is AppResult.Error -> return r
            is AppResult.Success -> if (r.data > 0) return AppResult.Error(
                ApiError(ERR_CATEGORY_HAS_RULES, "Category still has ${r.data} owner price rule(s); delete them or archive the category")
            )
            else -> Unit
        }
        return cloudDataSource.deleteCategory(categoryId)
    }

    override suspend fun setCategoryStatus(categoryId: String, status: CategoryStatus): AppResult<Unit> {
        // تعطيل/أرشفة الفئة لا يغيّر حالة بروفايلاتها (سياسة صريحة: لا تعطيل تلقائي لخدمات مستخدمة)
        val res = cloudDataSource.setCategoryStatus(categoryId, status)
        if (res is AppResult.Success) auditRepo.logAction("CATEGORY_STATUS", "Category $categoryId -> $status", LogSeverity.INFO)
        return res
    }

    override suspend fun countProfilesInCategory(categoryId: String): AppResult<Int> = cloudDataSource.countProfilesInCategory(categoryId)
    override suspend fun getProfileUsage(profileIds: List<String>): AppResult<Map<String, ProfileUsage>> = cloudDataSource.getProfileUsage(profileIds)
    override fun getCategoryHistory(categoryId: String): Flow<List<CategoryHistoryEntry>> = cloudDataSource.getCategoryHistory(categoryId)

    /** null = صالحة للإسناد؛ وإلا الخطأ. */
    private suspend fun categoryAssignable(categoryId: String): AppResult.Error? {
        val cat = cloudDataSource.getCategoryOnce(categoryId)
            ?: return AppResult.Error(ApiError.validationError("Category does not exist"))
        if (cat.status != CategoryStatus.ACTIVE) return AppResult.Error(ApiError(ERR_CATEGORY_INACTIVE, "Category is disabled or archived"))
        return null
    }

    // ------------------------------------------------------------------ تصنيفات الباقات (تشغيلية)

    override fun getProfileClassifications(): Flow<List<ProfileClassification>> = cloudDataSource.getClassifications()

    override suspend fun saveProfileClassification(classification: ProfileClassification, idempotencyKey: String): AppResult<Unit> {
        val errors = ClassificationRules.validate(classification)
        if (errors.isNotEmpty()) return AppResult.Error(ApiError.validationError("Classification values are invalid", errors))
        if (ClassificationRules.nameClash(classification, cloudDataSource.getClassificationsOnce())) {
            return AppResult.Error(ApiError(ClassificationRules.ERR_NAME_EXISTS, "A classification with this name already exists"))
        }
        // الحفظ لا يغيّر أي بروفايل ولا مستخدم؛ التطبيق خطوة صريحة منفصلة (previewClassificationApply / applyClassification)
        val res = cloudDataSource.saveClassification(classification.copy(nameAr = classification.nameAr.trim().replace(Regex("\\s+"), " ")))
        if (res is AppResult.Success) auditRepo.logAction("CLASSIFICATION_SAVE", "classification=${classification.key}", LogSeverity.INFO)
        return res
    }

    override suspend fun setClassificationStatus(key: String, status: CategoryStatus): AppResult<Unit> {
        // الأرشفة/التعطيل يمنع ربطات جديدة فقط؛ البروفايلات المرتبطة وعلاقاتها التاريخية تبقى كما هي
        val res = cloudDataSource.setClassificationStatus(key, status)
        if (res is AppResult.Success) auditRepo.logAction("CLASSIFICATION_STATUS", "classification=$key -> $status", LogSeverity.INFO)
        return res
    }

    override suspend fun countProfilesInClassification(key: String): AppResult<Int> = cloudDataSource.countProfilesInClassification(key)

    override suspend fun previewClassificationApply(key: String): AppResult<ClassificationApplyPlan> {
        val classification = cloudDataSource.getClassificationOnce(key)
            ?: return AppResult.Error(ApiError(ERR_CLASSIFICATION_NOT_FOUND, "Classification not found"))
        val plan = ClassificationRules.planApply(
            classification, cloudDataSource.getProfiles(true, null).first(), cloudDataSource.getCategoriesOnce()
        )
        // عدد المستخدمين المتأثرين من الراوتر؛ تعذّر القراءة = null (لا يُعرض كصفر)
        val config = routerConfigOrNull() ?: return AppResult.Success(plan)
        val users = (userMan.listUsers(config) as? AppResult.Success)?.data ?: return AppResult.Success(plan)
        val names = plan.items.map { it.profile.linkedProfileName }.toSet()
        return AppResult.Success(plan.copy(affectedUsers = users.count { it.packageName in names }))
    }

    /**
     * تطبيق التصنيف صراحةً على بروفايلاته (الخطة تُعاد حسابها لحظة التنفيذ ببيانات الحاضر):
     *  1. إعدادات بروفايل الراوتر (سرعة/جلسات) تُحدَّث على الراوتر ثم السحابة والكاش؛ تسري على كل مستخدميه.
     *  2. قيود المدة/الحجم تُطبَّق على حسابات المستخدمين العاديين على الراوتر ثم تُطابَق في سجلاتهم؛ المشتركون لا يُمسّون.
     *  3. فشل بروفايل أو مستخدم لا يوقف البقية، ويُسجَّل في النتيجة وسجل التدقيق. لا أسعار ولا فئات ولا بيانات تاريخية تُمسّ.
     * إعادة التنفيذ آمنة: ما طُبّق سابقاً لا يظهر في الخطة ثانيةً.
     */
    override suspend fun applyClassification(key: String): AppResult<ClassificationApplyResult> {
        val classification = cloudDataSource.getClassificationOnce(key)
            ?: return AppResult.Error(ApiError(ERR_CLASSIFICATION_NOT_FOUND, "Classification not found"))
        val plan = ClassificationRules.planApply(
            classification, cloudDataSource.getProfiles(true, null).first(), cloudDataSource.getCategoriesOnce()
        )
        if (plan.items.isEmpty()) return AppResult.Success(ClassificationApplyResult())
        val config = routerConfigOrNull() ?: return noRouterConfig()
        val routerNames = when (val res = userMan.listPackages(config)) {
            is AppResult.Success -> res.data.map { it.name }.toSet()
            is AppResult.Error -> return AppResult.Error(res.error)
            else -> return AppResult.Error(ApiError.unexpectedClientError(IllegalStateException("Loading")))
        }
        val routerUsers = when (val res = userMan.listUsers(config)) {
            is AppResult.Success -> res.data
            is AppResult.Error -> return AppResult.Error(res.error)
            else -> return AppResult.Error(ApiError.unexpectedClientError(IllegalStateException("Loading")))
        }

        var profilesUpdated = 0
        var usersUpdated = 0
        var skippedSubscription = 0
        val profileFailures = mutableListOf<String>()
        val userFailures = mutableListOf<String>()

        for (item in plan.items) {
            val p = item.profile
            val label = p.displayNameAr.ifBlank { p.linkedProfileName }
            val onRouter = p.linkedProfileName in routerNames
            if (item.touchesRouterProfile && onRouter && p.status != ProfileStatus.MISSING) {
                val spec = item.updated.copy(linkedProfileName = p.linkedProfileName).toPackageSpec(buildSet {
                    if (item.updated.rateLimitRxKbps != p.rateLimitRxKbps) add("rate_limit_rx_kbps")
                    if (item.updated.rateLimitTxKbps != p.rateLimitTxKbps) add("rate_limit_tx_kbps")
                    // الغير محدود قرار الباقة ولا يغيّره التصنيف؛ يُدرج مفتاحه كي يُكتب العدد/غير المحدود معاً كوحدة واحدة
                    if (item.updated.sharedUsers != p.sharedUsers || item.updated.sharedUnlimited != p.sharedUnlimited) {
                        add("shared_users"); add("shared_unlimited")
                    }
                    if (item.updated.limitUptimeSeconds != p.limitUptimeSeconds) add("limit_uptime_seconds")
                    if (item.updated.limitTransferBytes != p.limitTransferBytes) add("limit_transfer_bytes")
                })
                val r = userMan.upsertPackage(config, spec)
                if (r is AppResult.Error) { profileFailures += "$label: ${r.error.code}"; continue }
                // نجاح أمر الكتابة ليس دليلاً: لا تُحدَّث السحابة ولا الكاش ولا يُحتسب النجاح قبل قراءة الراوتر ومطابقتها للمواصفة
                val mismatch = verifyOnRouter(config, spec)
                if (mismatch != null) { profileFailures += "$label: ${mismatch.code}"; continue }
            }
            val fields = mapOf<String, Any?>(
                "rate_limit_rx_kbps" to item.updated.rateLimitRxKbps,
                "rate_limit_tx_kbps" to item.updated.rateLimitTxKbps,
                "shared_users" to item.updated.sharedUsers,
                "limit_uptime_seconds" to item.updated.limitUptimeSeconds,
                "limit_transfer_bytes" to item.updated.limitTransferBytes
            )
            val cloudRes = cloudDataSource.updateProfile(p.id, fields)
            if (cloudRes is AppResult.Error) { profileFailures += "$label: ${cloudRes.error.code}"; continue }
            localCache.saveProfiles(listOf(item.updated))
            profilesUpdated++

            if (item.touchesUserLimits) {
                val users = routerUsers.filter { it.packageName == p.linkedProfileName }
                if (item.userLimitsManagedBySubscription) { skippedSubscription += users.size; continue }
                for (u in users) {
                    // UserMan: الحدود طُبِّقت على الباقة نفسها أعلاه، فلا كتابة على الراوتر لكل مستخدم؛ نزامن سجل Cloud فقط.
                    val name = u.name
                    val sync = cloudUserDataSource.setRecordLimitsByUsername(name, item.updated.limitUptimeSeconds, item.updated.limitTransferBytes)
                    if (sync is AppResult.Error) userFailures += "$name: record ${sync.error.code}" else usersUpdated++
                }
            }
        }
        val result = ClassificationApplyResult(profilesUpdated, profileFailures, usersUpdated, userFailures, skippedSubscription)
        auditRepo.logAction(
            "CLASSIFICATION_APPLY",
            "classification=$key profiles=$profilesUpdated users=$usersUpdated skippedSubscription=$skippedSubscription " +
                "failedProfiles=${profileFailures.take(20)} failedUsers=${userFailures.take(20)}",
            if (result.hasFailures) LogSeverity.WARNING else LogSeverity.INFO
        )
        return AppResult.Success(result)
    }

    // ------------------------------------------------------------------ التسعير

    override fun getPricingRules(profileId: String): Flow<List<PricingRule>> = cloudDataSource.getPricingRules(profileId)
    override fun getCategoryPricingRules(categoryId: String): Flow<List<PricingRule>> = cloudDataSource.getCategoryPricingRules(categoryId)
    override fun getOwnerPricingRules(ownerId: String): Flow<List<PricingRule>> = cloudDataSource.getOwnerPricingRules(ownerId)

    /** المسار الوحيد لتحديد سعر عملية جديدة (جملة/تجزئة)؛ خطأ [PricingPolicy.ERR_PRICE_UNAVAILABLE] إن لم يوجد سعر صالح. */
    override suspend fun resolveQuote(profileId: String, ownerId: String?, context: SaleContext): AppResult<PricingPolicy.Quote> {
        val profile = cloudDataSource.getProfileOnce(profileId)
            ?: return AppResult.Error(ApiError("NOT_FOUND", "Profile not found"))
        val category = profile.categoryId?.let { cloudDataSource.getCategoryOnce(it) }
        val rules = buildList {
            addAll(cloudDataSource.getPricingRules(profileId).first())
            category?.let { addAll(cloudDataSource.getCategoryPricingRules(it.id).first()) }
        }
        val quote = PricingPolicy.resolveFor(context, ownerId, category, profile.priceMinor, rules, cloudDataSource.companyCurrency())
            ?: return AppResult.Error(ApiError(PricingPolicy.ERR_PRICE_UNAVAILABLE, "No valid price for this profile in this sale context"))
        return AppResult.Success(quote)
    }

    override suspend fun saveCategoryOwnerPrice(categoryId: String, ownerId: String, context: SaleContext, priceMinor: Long, reason: String?): AppResult<Unit> {
        if (!PricingPolicy.isPriceValid(priceMinor)) {
            return AppResult.Error(ApiError.validationError("Price is invalid", mapOf("price" to PricingPolicy.ERR_PRICE_INVALID)))
        }
        val category = cloudDataSource.getCategoryOnce(categoryId)
        CategoryRules.checkAcceptsNewLinks(category)?.let { return AppResult.Error(ApiError(it, "Category is missing, disabled or archived")) }
        val currency = category!!.currency?.takeIf { it.isNotBlank() }
            ?: return AppResult.Error(ApiError(CategoryRules.ERR_CURRENCY_REQUIRED, "Set the category currency first"))
        val ownerStatus = (cloudDataSource.ownerStatus(ownerId) as? AppResult.Success)?.data
        if (ownerStatus == null || ownerStatus == "ARCHIVED") {
            return AppResult.Error(ApiError.validationError("Owner is missing or archived"))
        }
        return when (val res = cloudDataSource.saveCategoryOwnerPrice(categoryId, ownerId, context, priceMinor, currency)) {
            is AppResult.Success -> {
                auditRepo.logAction(
                    "PRICE_SPECIAL_SET",
                    "category=$categoryId owner=$ownerId context=${context.wire} old=${res.data ?: "-"} new=$priceMinor reason=${reason.orEmpty()}",
                    LogSeverity.INFO
                )
                AppResult.Success(Unit)
            }
            is AppResult.Error -> AppResult.Error(res.error)
            else -> AppResult.Error(ApiError.unexpectedClientError(IllegalStateException("Loading")))
        }
    }

    override suspend fun setPricingRuleActive(ruleId: String, active: Boolean): AppResult<Unit> {
        if (active) {
            // إعادة التفعيل بعد التحقق من الصلاحية: السعر صالح والوكيل غير مؤرشف
            val rule = cloudDataSource.getRuleOnce(ruleId) ?: return AppResult.Error(ApiError("NOT_FOUND", "Special price not found"))
            if (!PricingPolicy.isPriceValid(rule.retailPriceMinor)) return AppResult.Error(ApiError.validationError("Price is invalid"))
            if ((cloudDataSource.ownerStatus(rule.ownerId) as? AppResult.Success)?.data.let { it == null || it == "ARCHIVED" }) {
                return AppResult.Error(ApiError.validationError("Owner is missing or archived"))
            }
        }
        val res = cloudDataSource.setRuleActive(ruleId, active)
        if (res is AppResult.Success) auditRepo.logAction("PRICE_SPECIAL_ACTIVE", "rule=$ruleId active=$active", LogSeverity.INFO)
        return res
    }

    override suspend fun deletePricingRule(ruleId: String): AppResult<Unit> {
        val res = cloudDataSource.deleteRule(ruleId)
        if (res is AppResult.Success) auditRepo.logAction("PRICE_SPECIAL_DELETE", "rule=$ruleId", LogSeverity.WARNING)
        return res
    }

    override suspend fun setProfileStatus(profileId: String, status: ProfileStatus): AppResult<Unit> {
        if (status == ProfileStatus.MISSING) return AppResult.Error(ApiError.validationError("MISSING is a sync state, not settable"))
        val profile = cloudDataSource.getProfileOnce(profileId) ?: return AppResult.Error(ApiError("NOT_FOUND", "Profile not found"))
        if (status == ProfileStatus.ACTIVE && !PricingPolicy.profilePriceReady(profile.categoryId?.let { cloudDataSource.getCategoryOnce(it) }, profile.priceMinor)) {
            // الاستعادة/التفعيل لا تتجاوز جاهزية السعر (سعر الفئة، أو سعر البروفايل القديم لفئة بلا أسعار)
            return AppResult.Error(ApiError(PricingPolicy.ERR_PRICE_UNAVAILABLE, "Set a valid price on the profile's category before activating"))
        }
        val res = cloudDataSource.updateProfile(profileId, mapOf("status" to status.name.lowercase()))
        if (res is AppResult.Success) {
            localCache.saveProfiles(listOf(profile.copy(status = status)))
            auditRepo.logAction("PROFILE_STATUS", "profile=$profileId ${profile.status} -> $status", LogSeverity.INFO)
            // بعد الاستعادة نتحقق من الراوتر إن توفر؛ لا نُعلن نجاح التحقق إن لم يتوفر (يبقى الاتصال/الحالة كما هي)
            if (status == ProfileStatus.ACTIVE && routerConfigOrNull() != null) reconcileRouterProfiles()
        }
        return res
    }

    override fun getProfileDefaults(profileId: String): Flow<ProfileDefaults?> = cloudDataSource.getProfileDefaults(profileId)
    override suspend fun upsertProfileDefaults(defaults: ProfileDefaults, idempotencyKey: String): AppResult<Unit> = cloudDataSource.upsertProfileDefaults(defaults)
    override suspend fun getProfileStatistics(profileId: String): AppResult<Map<String, Any?>> = cloudDataSource.getProfileStatistics(profileId)
    override fun observeUsernameSequences(): Flow<Map<String, Long>> = cloudDataSource.observeUsernameSequences()
    override suspend fun resetUsernameSequence(profileId: String): AppResult<Unit> {
        val res = cloudDataSource.setUsernameSequence(profileId, 0L)
        if (res is AppResult.Success) auditRepo.logAction("PROFILE_SEQUENCE_RESET", "Username sequence reset for profile $profileId", LogSeverity.WARNING)
        return res
    }

    private companion object {
        const val ERR_ROUTER_VERIFY_MISMATCH = "ERR_ROUTER_VERIFY_MISMATCH"
        const val ERR_PROFILE_IN_USE = "ERR_PROFILE_IN_USE"
        const val ERR_PROFILE_HAS_ROUTER_USERS = "ERR_PROFILE_HAS_ROUTER_USERS"
        const val ERR_REPOINT_NOT_SUPPORTED = "ERR_PROFILE_REPOINT_NOT_SUPPORTED"
        const val ERR_CATEGORY_NAME_EXISTS = "ERR_CATEGORY_NAME_EXISTS"
        const val ERR_CATEGORY_NOT_EMPTY = "ERR_CATEGORY_NOT_EMPTY"
        const val ERR_CATEGORY_INACTIVE = "ERR_CATEGORY_INACTIVE"
        const val ERR_CATEGORY_HAS_RULES = "ERR_CATEGORY_HAS_RULES"
        const val ERR_CLASSIFICATION_NOT_FOUND = "ERR_CLASSIFICATION_NOT_FOUND"
        /** حقول Firestore المسموح تعديلها عبر updateCloudProfile (الاسم التقني والحالة لهما مساراتهما). */
        val UPDATABLE_FIELDS = setOf(
            "display_name_ar", "display_name_en", "description", "category_id", "classification_key",
            "price_minor", "shared_users", "rate_limit_rx_kbps", "rate_limit_tx_kbps", "validity_hours", "quota_mb",
            "limit_uptime_seconds", "limit_transfer_bytes", "own_settings",
            "validity_start", "validity_unlimited", "shared_unlimited"
        )
        /** تعديل أحدها (أو الربط بتصنيف) يعيد حساب القيم الفعلية الموروثة. */
        val INHERITANCE_FIELDS = setOf(
            "classification_key", "own_settings", "shared_users", "rate_limit_rx_kbps", "rate_limit_tx_kbps",
            "limit_uptime_seconds", "limit_transfer_bytes"
        )
        /** الحقول التي تغيّر إعداد الباقة على الراوتر نفسه. */
        val ROUTER_FIELDS = setOf("shared_users", "rate_limit_rx_kbps", "rate_limit_tx_kbps", "limit_uptime_seconds", "limit_transfer_bytes", "validity_hours", "quota_mb",
            "validity_start", "validity_unlimited", "shared_unlimited")
    }
}

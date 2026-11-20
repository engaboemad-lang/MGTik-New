package ps.mgtik.hotspot.ui.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.IdempotencyKeyProvider
import ps.mgtik.hotspot.domain.model.PricingRule
import ps.mgtik.hotspot.domain.model.ProfileUsage
import ps.mgtik.hotspot.domain.model.CategoryHistoryEntry
import ps.mgtik.hotspot.domain.model.ReferenceChangeAction
import ps.mgtik.hotspot.domain.model.ReferenceChangeImpact
import ps.mgtik.hotspot.domain.model.RouterProfileOption
import ps.mgtik.hotspot.domain.usecase.RouterProfileImport
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.ProfileCategory
import ps.mgtik.hotspot.domain.model.SystemClass
import ps.mgtik.hotspot.domain.usecase.SystemClassRules
import ps.mgtik.hotspot.domain.model.ProfileClassification
import ps.mgtik.hotspot.domain.model.ProfileStatus
import ps.mgtik.hotspot.domain.usecase.CreateProfileUseCase
import ps.mgtik.hotspot.domain.usecase.DeleteProfileUseCase
import ps.mgtik.hotspot.domain.usecase.GetProfileCategoriesUseCase
import ps.mgtik.hotspot.domain.usecase.GetProfileClassificationsUseCase
import ps.mgtik.hotspot.domain.usecase.GetProfilesUseCase
import ps.mgtik.hotspot.domain.usecase.GetRouterProfileOptionsUseCase
import ps.mgtik.hotspot.domain.usecase.GetRouterLimitationNamesUseCase
import ps.mgtik.hotspot.domain.usecase.GetSessionStateUseCase
import ps.mgtik.hotspot.domain.usecase.ObserveUsernameSequencesUseCase
import ps.mgtik.hotspot.domain.usecase.ResetUsernameSequenceUseCase
import ps.mgtik.hotspot.domain.usecase.ProfileInputValidator
import ps.mgtik.hotspot.domain.usecase.ReconcileRouterProfilesUseCase
import ps.mgtik.hotspot.domain.usecase.*
import ps.mgtik.hotspot.domain.model.CategoryStatus
import ps.mgtik.hotspot.domain.model.OwnerAccount
import ps.mgtik.hotspot.domain.model.OperationalSetting
import ps.mgtik.hotspot.domain.model.SaleContext
import ps.mgtik.hotspot.domain.model.ClassificationApplyPlan
import ps.mgtik.hotspot.domain.model.ClassificationApplyResult
import ps.mgtik.hotspot.domain.model.OwnerStatus
import ps.mgtik.hotspot.domain.usecase.SaveProfileCategoryUseCase
import ps.mgtik.hotspot.domain.usecase.SaveProfileClassificationUseCase
import ps.mgtik.hotspot.domain.usecase.TransferUnit
import ps.mgtik.hotspot.ui.util.ErrorTranslator
import ps.mgtik.hotspot.domain.usecase.UptimeUnit
import ps.mgtik.hotspot.domain.usecase.UsageLimits
import java.util.UUID
import javax.inject.Inject

/**
 * نموذج عرض موحد للبروفايل مع تفاصيله (§8)
 */
data class ProfileUiModel(
    val profile: Profile,
    val isSynced: Boolean = true,
    val lastSyncMessage: String? = null
)

/** مفاتيح أخطاء حقول نافذة إنشاء الباقة. */
object ProfileField {
    const val ROUTER_NAME = "routerName"
    const val LINK_PROFILE = "linkProfile"
    const val DISPLAY_NAME_AR = "displayNameAr"
    const val PRICE = "price"
    const val RATE_LIMIT = "rateLimit"
    const val LIMIT_UPTIME = "limitUptime"
    const val LIMIT_TRANSFER = "limitTransfer"
    const val VALIDITY = "validity"
    const val SHARED_USERS = "sharedUsers"
}

enum class ValidityUnit(val hours: Int) { HOURS(1), DAYS(24), MONTHS(720) }

/**
 * الإعدادات التشغيلية الإضافية لنموذج الإنشاء (الصلاحية، عدد الأجهزة، سياسة البداية). النصوص الفارغة = «غير محدد»:
 * لا يُرسل شيء للراوتر لها (لا 0s ولا قيمة افتراضية مخفية). غير المحدود قرار صريح فقط.
 */
data class PackageOps(
    val validityText: String = "",
    val validityUnit: ValidityUnit = ValidityUnit.DAYS,
    val validityUnlimited: Boolean = false,
    val sharedUsersText: String = "",
    val sharedUnlimited: Boolean = false,
    val startAssigned: Boolean = false
)

object PackageOpsParser {
    /** null = فارغ (غير محدد)، -1 = خطأ، >0 = ساعات. */
    fun validityHours(ops: PackageOps): Int? {
        if (ops.validityUnlimited || ops.validityText.isBlank()) return null
        val n = ops.validityText.trim().toLongOrNull()?.takeIf { it > 0 } ?: return -1
        val h = n * ops.validityUnit.hours
        return if (h <= Int.MAX_VALUE) h.toInt() else -1
    }

    /** null = فارغ، -1 = خطأ، >0 = العدد. */
    fun sharedUsers(ops: PackageOps): Int? {
        if (ops.sharedUnlimited || ops.sharedUsersText.isBlank()) return null
        return ops.sharedUsersText.trim().toIntOrNull()?.takeIf { it in 1..9999 } ?: -1
    }
}

data class ProfilesUiState(
    val profiles: List<ProfileUiModel> = emptyList(),
    val categories: List<ProfileCategory> = emptyList(),
    val classifications: List<ProfileClassification> = emptyList(),
    val isLoading: Boolean = false,
    val isSyncingRouter: Boolean = false,
    val isSubmitting: Boolean = false,
    val showCreateDialog: Boolean = false,
    val showCreateCategoryDialog: Boolean = false,
    val showCreateClassificationDialog: Boolean = false,
    /** الباقة التي ينتظر حذفها تأكيد المستخدم. */
    val profilePendingDelete: Profile? = null,
    val isDeleting: Boolean = false,
    /** العدّاد التسلسلي الدائم لكل بروفايل (معرّف البروفايل ← آخر رقم مستخدم)؛ الغائب = 0. */
    val sequences: Map<String, Long> = emptyMap(),
    /** الباقة التي ينتظر تصفير عدّادها تأكيد المستخدم. */
    val profilePendingSequenceReset: Profile? = null,
    val isResettingSequence: Boolean = false,
    /** بروفايلات الراوتر غير المربوطة بباقة بعد (لخيار «ربط بروفايل موجود»). */
    val linkableRouterProfiles: List<RouterProfileOption> = emptyList(),
    val routerProfilesLoading: Boolean = false,
    /** Limitations الموجودة على الراوتر (اختيار مكوّن موجود/مشترك عند الإنشاء؛ فارغة إن تعذّرت القراءة). */
    val routerLimitationNames: List<String> = emptyList(),
    @StringRes val routerProfilesErrorResId: Int? = null,
    val lastSyncTime: Long? = null,
    /** صفحة تفاصيل الفئة المفتوحة (null = القائمة) وأسعار مالكيها الخاصة. فتحها/إغلاقها لا يحفظ شيئاً. */
    val selectedCategoryId: String? = null,
    val categoryRules: List<PricingRule> = emptyList(),
    val owners: List<OwnerAccount> = emptyList(),
    val isSavingPrice: Boolean = false,
    /** صفحة تفاصيل التصنيف المفتوحة، ومعاينة/نتيجة تطبيق تغييراته على الباقات والمستخدمين (خطوة صريحة منفصلة عن الحفظ). */
    val selectedClassificationKey: String? = null,
    val applyPlan: ClassificationApplyPlan? = null,
    val isPreviewingApply: Boolean = false,
    val isApplying: Boolean = false,
    val applyResult: ClassificationApplyResult? = null,
    /** فئة تنتظر تأكيد الحذف النهائي (يعرض عدد بروفايلاتها المحسوب محلياً؛ الفرض الفعلي في المستودع). */
    val categoryPendingDelete: ProfileCategory? = null,
    /** تغيير المرجع التقني: الباقة المعنية، خيارات الراوتر، نتيجة معاينة الأثر، وحالات الفحص/التنفيذ. */
    val referenceProfile: Profile? = null,
    /** الباقة المفتوحة في نافذة التعديل (الاسم العربي/الفئة/التصنيف). */
    val editProfile: Profile? = null,
    val isSavingEdit: Boolean = false,
    val referenceOptions: List<RouterProfileOption> = emptyList(),
    val referenceImpact: ReferenceChangeImpact? = null,
    val isCheckingReference: Boolean = false,
    val isApplyingReference: Boolean = false,
    @StringRes val referenceErrorResId: Int? = null,
    @StringRes val errorMessageResId: Int? = null,
    @StringRes val successMessageResId: Int? = null,
    /** مفتاح الحقل ← رسالة الخطأ (مورد نصي). */
    val validationErrors: Map<String, Int> = emptyMap(),
    /** صفحة تفاصيل الباقة: القسم المفتوح للتعديل، وأسعار الفئة الخاصة (مالكون/وكلاء)، وخطة التعديل التشغيلي بانتظار التأكيد. */
    val detailEditor: ProfileSection? = null,
    val detailRules: List<PricingRule> = emptyList(),
    val detailErrors: Map<String, Int> = emptyMap(),
    val isSavingSection: Boolean = false,
    val pendingOperationalPlan: ProfileDetailRules.OperationalPlan? = null,
    /** السعر النهائي ومصدره لكل سياق (بلا وكيل) كما تحسبه سياسة التسعير نفسها؛ null = لا سعر صالح (لا يُعرض صفراً). */
    val detailQuotes: Map<SaleContext, PricingPolicy.Quote?> = emptyMap(),
    /** أعداد الاستخدام الفعلية لكل بروفايل (من الخادم). فارغة + [usageFailed] = تعذّر العدّ؛ لا تُعرض أصفاراً. */
    val usage: Map<String, ProfileUsage> = emptyMap(),
    val usageLoading: Boolean = false,
    val usageFailed: Boolean = false,
    /** سجل التعديلات التجارية للفئة المفتوحة (الأحدث أولاً). */
    val categoryHistory: List<CategoryHistoryEntry> = emptyList()
)

/** أقسام تعديل صفحة تفاصيل الباقة (كل قسم يُحفظ مستقلاً عن الآخر). */
enum class ProfileSection { ADMIN, OPERATIONAL, COMMERCIAL }

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val getSessionStateUseCase: GetSessionStateUseCase,
    private val getProfilesUseCase: GetProfilesUseCase,
    private val createProfileUseCase: CreateProfileUseCase,
    private val deleteProfileUseCase: DeleteProfileUseCase,
    private val reconcileRouterProfilesUseCase: ReconcileRouterProfilesUseCase,
    private val getProfileCategoriesUseCase: GetProfileCategoriesUseCase,
    private val getProfileClassificationsUseCase: GetProfileClassificationsUseCase,
    private val saveProfileCategoryUseCase: SaveProfileCategoryUseCase,
    private val saveProfileClassificationUseCase: SaveProfileClassificationUseCase,
    private val observeUsernameSequencesUseCase: ObserveUsernameSequencesUseCase,
    private val resetUsernameSequenceUseCase: ResetUsernameSequenceUseCase,
    private val getRouterProfileOptionsUseCase: GetRouterProfileOptionsUseCase,
    private val getRouterLimitationNamesUseCase: GetRouterLimitationNamesUseCase,
    private val setProfileStatusUseCase: SetProfileStatusUseCase,
    private val setCategoryStatusUseCase: SetCategoryStatusUseCase,
    private val deleteProfileCategoryUseCase: DeleteProfileCategoryUseCase,
    private val getOwnersSummaryUseCase: ps.mgtik.hotspot.domain.usecase.GetOwnersSummaryUseCase,
    private val getCategoryPricingRulesUseCase: GetCategoryPricingRulesUseCase,
    private val saveCategoryOwnerPriceUseCase: SaveCategoryOwnerPriceUseCase,
    private val setPricingRuleActiveUseCase: SetPricingRuleActiveUseCase,
    private val deletePricingRuleUseCase: DeletePricingRuleUseCase,
    private val setClassificationStatusUseCase: SetClassificationStatusUseCase,
    private val previewClassificationApplyUseCase: PreviewClassificationApplyUseCase,
    private val applyClassificationUseCase: ApplyClassificationUseCase,
    private val previewReferenceChangeUseCase: PreviewReferenceChangeUseCase,
    private val migrateProfileRenameUseCase: MigrateProfileRenameUseCase,
    private val updateProfileUseCase: UpdateProfileUseCase,
    private val resolveUnitPriceUseCase: ResolveUnitPriceUseCase,
    private val getProfileUsageUseCase: GetProfileUsageUseCase,
    private val getCategoryHistoryUseCase: GetCategoryHistoryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfilesUiState())
    val uiState: StateFlow<ProfilesUiState> = _uiState.asStateFlow()

    init {
        observeProfiles()
        observeCategories()
        observeClassifications()
        observeSequences()
        observeOwners()
    }

    private fun observeOwners() {
        getSessionStateUseCase.gate { getOwnersSummaryUseCase() }
            .onEach { list -> _uiState.update { it.copy(owners = list) } }
            .catch { /* الأسعار الخاصة تتعطل فقط؛ لا تمنع الشاشة */ }
            .launchIn(viewModelScope)
    }

    private fun observeProfiles() {
        _uiState.update { it.copy(isLoading = true) }
        getSessionStateUseCase.gate(onSignedOut = ::resetForSignOut) { getProfilesUseCase(includeArchived = true) }
            .onEach { list ->
                val uiModels = list.map { p -> ProfileUiModel(profile = p, isSynced = p.status != ProfileStatus.MISSING) }
                _uiState.update { it.copy(profiles = uiModels, isLoading = false) }
            }
            .catch { _uiState.update { it.copy(isLoading = false, errorMessageResId = R.string.ERR_UNKNOWN) } }
            .launchIn(viewModelScope)
    }

    /** الـ ViewModel يعيش مع الـ Activity: عند الخروج يُفرَّغ كل ما يخص المستخدم السابق (مالكون، استخدام، أسعار، اختيارات، نوافذ). */
    private fun resetForSignOut() {
        categoryRulesJob?.cancel()
        categoryHistoryJob?.cancel()
        detailRulesJob?.cancel()
        usageJob?.cancel()
        quotesJob?.cancel()
        _uiState.value = ProfilesUiState(isLoading = true)
    }

    private fun observeCategories() {
        getSessionStateUseCase.gate { getProfileCategoriesUseCase() }
            .onEach { list -> _uiState.update { it.copy(categories = list) } }
            .catch { /* الفئات اختيارية للعرض؛ فشلها لا يمنع الشاشة */ }
            .launchIn(viewModelScope)
    }

    private fun observeClassifications() {
        getSessionStateUseCase.gate { getProfileClassificationsUseCase() }
            .onEach { list -> _uiState.update { it.copy(classifications = list) } }
            .catch { /* كما أعلاه */ }
            .launchIn(viewModelScope)
    }

    private fun observeSequences() {
        getSessionStateUseCase.gate(onSignedOut = { _uiState.update { it.copy(sequences = emptyMap()) } }) { observeUsernameSequencesUseCase() }
            .onEach { map -> _uiState.update { it.copy(sequences = map) } }
            .catch { /* العدّاد للعرض فقط؛ فشل القراءة لا يمنع الشاشة (يُعرض 0 والتوليد يقرأ من السحابة مباشرة) */ }
            .launchIn(viewModelScope)
    }

    fun requestSequenceReset(profile: Profile) { _uiState.update { it.copy(profilePendingSequenceReset = profile) } }
    fun dismissSequenceReset() { if (!_uiState.value.isResettingSequence) _uiState.update { it.copy(profilePendingSequenceReset = null) } }

    /** تصفير العدّاد بعد تأكيد المستخدم؛ يُحفظ فعلياً في السحابة (مصدر البيانات المعتمد) ويرجع للواجهة عبر المراقبة. */
    fun confirmSequenceReset() {
        val profile = _uiState.value.profilePendingSequenceReset ?: return
        if (_uiState.value.isResettingSequence) return
        viewModelScope.launch {
            _uiState.update { it.copy(isResettingSequence = true) }
            val res = guard { resetUsernameSequenceUseCase(profile.id) }
            _uiState.update {
                when (res) {
                    is AppResult.Success -> it.copy(isResettingSequence = false, profilePendingSequenceReset = null, successMessageResId = R.string.profile_sequence_reset_done)
                    is AppResult.Error -> it.copy(isResettingSequence = false, profilePendingSequenceReset = null, errorMessageResId = res.error.toMessageRes())
                    else -> it.copy(isResettingSequence = false)
                }
            }
        }
    }

    fun syncWithRouter() {
        if (_uiState.value.isSyncingRouter) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncingRouter = true) }
            val res = guard { reconcileRouterProfilesUseCase() }
            _uiState.update {
                when (res) {
                    is AppResult.Success -> it.copy(
                        isSyncingRouter = false,
                        lastSyncTime = System.currentTimeMillis(),
                        successMessageResId = R.string.profile_success_sync
                    )
                    is AppResult.Error -> it.copy(isSyncingRouter = false, errorMessageResId = res.error.toMessageRes())
                    else -> it.copy(isSyncingRouter = false)
                }
            }
        }
    }

    /**
     * @param categoryId فئة اختيارية؛ null = بلا فئة (مسموح).
     * @param classificationKey تصنيف اختياري؛ null = بلا تصنيف، وقاعدة التسعير الافتراضية تستخدم hotspot_standard.
     */
    fun createProfile(
        routerName: String,
        displayNameAr: String,
        rateLimitText: String,
        categoryId: String? = null,
        classificationKey: String? = null,
        limitUptimeText: String = "",
        limitUptimeUnit: UptimeUnit = UptimeUnit.HOURS,
        limitTransferText: String = "",
        limitTransferUnit: TransferUnit = TransferUnit.GB,
        linkExistingName: String? = null,
        ops: PackageOps = PackageOps(),
        limitationNames: List<String> = emptyList(),
        limitationsReference: Boolean = false,
        limitExtras: ps.mgtik.hotspot.domain.model.PackageLimitExtras? = null
    ) {
        if (_uiState.value.isSubmitting) return

        // وضع الربط: الاسم والسرعة وعدد الأجهزة تؤخذ من بروفايل الراوتر المختار ولا تُنشأ كتابة عليه
        val linked = linkExistingName?.let { n -> _uiState.value.linkableRouterProfiles.firstOrNull { it.name == n } }
        val linking = linkExistingName != null
        val name = (if (linking) linkExistingName else routerName).orEmpty().trim()
        val arName = displayNameAr.trim()
        // التصنيف الاختياري: يجب أن يكون نشطاً؛ الحقل الفارغ في الباقة يعني «وراثة» إن عرّف التصنيف ذلك الإعداد
        val chosenClassification = classificationKey?.let { k -> _uiState.value.classifications.firstOrNull { it.key == k } }
        if (chosenClassification != null && ClassificationRules.checkAssignable(chosenClassification) != null) {
            _uiState.update { it.copy(errorMessageResId = R.string.class_err_inactive) }
            return
        }
        val rateBlank = rateLimitText.isBlank()
        val rateInherited = !linking && rateBlank && chosenClassification?.defines(OperationalSetting.SPEED) == true
        val rateLimit = if (linking) null else ProfileInputValidator.parseRateLimit(rateLimitText)
        // فارغ = غير مفروض (null)؛ صفر/سالب/فوق السقف = خطأ (لا يُحوَّل إلى «بلا حد» بصمت)
        val uptimeParsed = UsageLimits.parseUptime(limitUptimeText, limitUptimeUnit)
        val transferParsed = UsageLimits.parseTransfer(limitTransferText, limitTransferUnit)

        // في وضع الربط تُؤخذ القيم من الراوتر ولا تُكتب عليه: لا تُقرأ الحقول التشغيلية الإضافية
        val validityHoursParsed = if (linking) null else PackageOpsParser.validityHours(ops)
        val sharedParsed = if (linking) null else PackageOpsParser.sharedUsers(ops)

        val errors = mutableMapOf<String, Int>()
        if (validityHoursParsed == -1) errors[ProfileField.VALIDITY] = R.string.profile_error_validity
        if (sharedParsed == -1) errors[ProfileField.SHARED_USERS] = R.string.profile_error_shared_users
        when {
            linking && linked == null -> errors[ProfileField.LINK_PROFILE] = R.string.profile_error_link_required
            linking -> Unit // الاسم قادم من الراوتر نفسه والقائمة تستثني المربوط مسبقاً
            !ProfileInputValidator.isRouterNameValid(name) -> errors[ProfileField.ROUTER_NAME] = R.string.profile_error_router_name
            _uiState.value.profiles.any { it.profile.linkedProfileName.equals(name, ignoreCase = true) } ->
                errors[ProfileField.ROUTER_NAME] = R.string.profile_error_router_name_exists
        }
        if (arName.isBlank()) errors[ProfileField.DISPLAY_NAME_AR] = R.string.profile_error_name_required
        if (!linking && rateLimit == null) errors[ProfileField.RATE_LIMIT] = R.string.profile_error_rate_invalid
        // في وضع الربط حقلا الحدود مخفيان وقيمهما من الراوتر: لا يُتحقق مما كُتب فيهما سابقاً
        if (!linking && uptimeParsed is UsageLimits.Parsed.Invalid) errors[ProfileField.LIMIT_UPTIME] = R.string.profile_error_limit_uptime
        if (!linking && transferParsed is UsageLimits.Parsed.Invalid) errors[ProfileField.LIMIT_TRANSFER] = R.string.profile_error_limit_transfer

        // اختيار قديم لفئة/تصنيف حُذف أثناء فتح النافذة يُعامل كأنه «بدون»
        val validCategoryId = categoryId?.takeIf { id -> _uiState.value.categories.any { it.id == id } }
        val validClassificationKey = classificationKey?.takeIf { key -> _uiState.value.classifications.any { it.key == key } }

        // الفئة إلزامية ومصنّفة نظامياً (ترث الباقة تصنيفها منها)
        val category = validCategoryId?.let { id -> _uiState.value.categories.firstOrNull { it.id == id } }
        SystemClassRules.checkCategoryForNewProfile(validCategoryId != null, category)?.let { code ->
            _uiState.update {
                it.copy(
                    errorMessageResId = if (code == SystemClassRules.ERR_PROFILE_CATEGORY_REQUIRED) R.string.sysclass_err_category_required
                    else R.string.sysclass_err_category_unclassified
                )
            }
            return
        }

        // السعر مصدره الفئة: فئة بلا أي سعر (ولا سعر قديم) لا تُباع بها باقة جديدة
        if (!PricingPolicy.profilePriceReady(category, 0L)) {
            _uiState.update { it.copy(errorMessageResId = R.string.profile_error_category_unpriced) }
            return
        }

        if (errors.isNotEmpty() || (!linking && rateLimit == null)) {
            _uiState.update { it.copy(validationErrors = errors) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, validationErrors = emptyMap()) }
            val key = IdempotencyKeyProvider.generateKey()
            val id = "PRF-${UUID.randomUUID().toString().take(6)}"

            val built = Profile(
                id = id,
                linkedProfileName = name,
                displayNameAr = arName,
                displayNameEn = name, // الافتراضي هو اسم الراوتر
                priceMinor = 0L, // السعر في الفئة (حقل البروفايل قديم ولا يُكتب بعد الآن)
                sharedUsers = sharedParsed?.takeIf { it > 0 } ?: 1,
                sharedUnlimited = !linking && ops.sharedUnlimited,
                limitationNames = if (linking) emptyList() else limitationNames,
                limitationsReference = !linking && limitationsReference && limitationNames.isNotEmpty(),
                // Burst وحدّا التنزيل/الرفع يخصّان Limitation جديدة نكتبها؛ لا معنى لها مع مكوّن موجود/مشترك (لا يُكتب عليه) أو مع الربط
                limitExtras = limitExtras.takeIf { !linking && !limitationsReference },
                validityHours = validityHoursParsed?.takeIf { it > 0 } ?: 0,
                validityUnlimited = !linking && ops.validityUnlimited,
                validityStart = if (!linking && ops.startAssigned) "assigned" else "first_auth",
                rateLimitRxKbps = rateLimit?.rxKbps ?: 0,
                rateLimitTxKbps = rateLimit?.txKbps ?: 0,
                categoryId = validCategoryId,
                classificationKey = validClassificationKey,
                status = ProfileStatus.ACTIVE,
                limitUptimeSeconds = (uptimeParsed as? UsageLimits.Parsed.Value)?.amount?.takeIf { !linking },
                limitTransferBytes = (transferParsed as? UsageLimits.Parsed.Value)?.amount?.takeIf { !linking },
                // ما يكتبه المستخدم صراحةً يخصّ الباقة وله الأولوية؛ الحقل الفارغ يرثه الراوتر والتوليد من التصنيف (يُثبَّت في المستودع)
                ownSettings = buildSet {
                    if (!rateInherited) add(OperationalSetting.SPEED)
                    if (sharedParsed != null || ops.sharedUnlimited) add(OperationalSetting.SHARED_USERS)
                    if (uptimeParsed is UsageLimits.Parsed.Value) add(OperationalSetting.LIMIT_UPTIME)
                    if (transferParsed is UsageLimits.Parsed.Value) add(OperationalSetting.LIMIT_TRANSFER)
                }
            )
            // وضع الربط: كل الإعدادات التشغيلية (سرعة، أجهزة بما فيه غير المحدود، صلاحية، سياسة بداية، حدّا الوقت/الحجم) تُستورد من
            // الراوتر، ولا تُحفظ قيمة شكلية تخالفه. المستودع يعيد القراءة من الراوتر لحظة الحفظ ويكتب فوق هذه القيم (المرجع الأخير).
            val profile = if (linking && linked != null) {
                when (val r = RouterProfileImport.import(built, linked)) {
                    is RouterProfileImport.Result.Ok -> r.profile
                    is RouterProfileImport.Result.Rejected -> {
                        _uiState.update {
                            it.copy(
                                isSubmitting = false,
                                errorMessageResId = if (r.code == RouterProfileImport.ERR_VALIDITY_NOT_REPRESENTABLE) R.string.profile_error_router_validity_unsupported
                                else R.string.profile_error_router_limit_unsupported
                            )
                        }
                        return@launch
                    }
                }
            } else built

            // skipQueue = true: الشاشة تحتاج نتيجة الراوتر/السحابة فوراً (رفض الراوتر، تكرار الاسم...)
            when (val res = guard { createProfileUseCase(profile, key, skipQueue = true, linkExisting = linking) }) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(isSubmitting = false, showCreateDialog = false, successMessageResId = R.string.profile_success_create)
                    }
                }
                is AppResult.Error -> _uiState.update {
                    it.copy(isSubmitting = false, errorMessageResId = res.error.toMessageRes())
                }
                else -> _uiState.update { it.copy(isSubmitting = false) }
            }
        }
    }

    fun requestDelete(profile: Profile) { _uiState.update { it.copy(profilePendingDelete = profile) } }
    fun dismissDelete() { if (!_uiState.value.isDeleting) _uiState.update { it.copy(profilePendingDelete = null) } }

    fun confirmDelete() {
        val profile = _uiState.value.profilePendingDelete ?: return
        if (_uiState.value.isDeleting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true) }
            val res = guard { deleteProfileUseCase(profile.id, alsoDeleteFromRouter = true, skipQueue = true) }
            _uiState.update {
                when (res) {
                    is AppResult.Success -> it.copy(isDeleting = false, profilePendingDelete = null, successMessageResId = R.string.profile_success_delete)
                    is AppResult.Error -> it.copy(isDeleting = false, profilePendingDelete = null, errorMessageResId = res.error.toMessageRes())
                    else -> it.copy(isDeleting = false)
                }
            }
        }
    }

    fun openCreateDialog() {
        _uiState.update { it.copy(showCreateDialog = true, validationErrors = emptyMap()) }
        loadRouterProfiles()
        viewModelScope.launch {
            val res = guard { getRouterLimitationNamesUseCase() }
            if (res is AppResult.Success) _uiState.update { it.copy(routerLimitationNames = res.data) }
        }
    }

    /** يقرأ بروفايلات الراوتر الفعلية ويستثني ما رُبط بباقة موجودة (الاسم التقني مفتاح الربط). */
    fun loadRouterProfiles() {
        if (_uiState.value.routerProfilesLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(routerProfilesLoading = true, routerProfilesErrorResId = null) }
            val res = guard { getRouterProfileOptionsUseCase() }
            _uiState.update { st ->
                when (res) {
                    is AppResult.Success -> {
                        val taken = st.profiles.map { it.profile.linkedProfileName.lowercase() }.toSet()
                        st.copy(routerProfilesLoading = false, linkableRouterProfiles = res.data.filter { it.name.lowercase() !in taken })
                    }
                    is AppResult.Error -> st.copy(routerProfilesLoading = false, linkableRouterProfiles = emptyList(), routerProfilesErrorResId = res.error.toMessageRes())
                    else -> st.copy(routerProfilesLoading = false)
                }
            }
        }
    }
    fun closeCreateDialog() { if (!_uiState.value.isSubmitting) _uiState.update { it.copy(showCreateDialog = false, validationErrors = emptyMap()) } }
    fun clearFieldError(field: String) { _uiState.update { it.copy(validationErrors = it.validationErrors - field) } }
    fun clearMessages() { _uiState.update { it.copy(successMessageResId = null, errorMessageResId = null) } }

    fun openCreateCategoryDialog() { _uiState.update { it.copy(showCreateCategoryDialog = true) } }
    fun closeCreateCategoryDialog() { if (!_uiState.value.isSubmitting) _uiState.update { it.copy(showCreateCategoryDialog = false) } }

    fun createCategory(nameAr: String, nameEn: String, systemClass: SystemClass, currency: String, retailText: String, wholesaleText: String) =
        saveCategory(null, nameAr, nameEn, null, systemClass, currency, retailText, wholesaleText)

    /**
     * category = null ⇒ إنشاء. نوع النظام والعملة يُحدَّدان عند الإنشاء ويثبتان (المستودع والقواعد ترفضان تغييرهما).
     * الأسعار: فارغ = غير محدد؛ تعديلها لا يمسّ إعدادات MikroTik ولا أي دفعة/اشتراك سابق.
     */
    fun saveCategory(
        existing: ProfileCategory?, nameAr: String, nameEn: String, description: String?, systemClass: SystemClass?,
        currency: String, retailText: String, wholesaleText: String
    ) {
        if (_uiState.value.isSubmitting) return
        if (nameAr.isBlank()) {
            _uiState.update { it.copy(errorMessageResId = R.string.profile_error_name_required) }
            return
        }
        // إلزامي للفئة الجديدة؛ الفئة المصنّفة سلفاً تحتفظ بتصنيفها (ثابت)
        if (existing?.systemClass == null && systemClass == null) {
            _uiState.update { it.copy(errorMessageResId = R.string.sysclass_err_class_required) }
            return
        }
        val retail = retailText.trim().takeIf { it.isNotEmpty() }?.let { ProfileInputValidator.parsePriceMinor(it) ?: -1L }
        val wholesale = wholesaleText.trim().takeIf { it.isNotEmpty() }?.let { ProfileInputValidator.parsePriceMinor(it) ?: -1L }
        if ((retail != null && !PricingPolicy.isPriceValid(retail)) || (wholesale != null && !PricingPolicy.isPriceValid(wholesale))) {
            _uiState.update { it.copy(errorMessageResId = R.string.profile_error_price_invalid) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true) }
            val category = (existing ?: ProfileCategory(id = "CAT-${UUID.randomUUID().toString().take(6)}")).copy(
                nameAr = nameAr.trim(),
                nameEn = nameEn.trim().ifBlank { nameAr.trim() },
                description = description?.trim()?.ifBlank { null },
                systemClass = existing?.systemClass ?: systemClass,
                currency = existing?.currency?.takeIf { it.isNotBlank() } ?: currency.trim().ifBlank { null },
                retailPriceMinor = retail,
                wholesalePriceMinor = wholesale
            )
            when (val res = guard { saveProfileCategoryUseCase(category) }) {
                is AppResult.Success -> _uiState.update {
                    it.copy(isSubmitting = false, showCreateCategoryDialog = false, successMessageResId = R.string.profile_success_create)
                }
                is AppResult.Error -> _uiState.update {
                    it.copy(isSubmitting = false, errorMessageResId = res.error.toMessageRes())
                }
                else -> _uiState.update { it.copy(isSubmitting = false) }
            }
        }
    }

    // ------------------------------------------------------------------ صفحة تفاصيل الفئة + أسعار المالكين

    private var categoryRulesJob: kotlinx.coroutines.Job? = null

    private var categoryHistoryJob: kotlinx.coroutines.Job? = null

    fun openCategoryDetail(categoryId: String) {
        _uiState.update { it.copy(selectedCategoryId = categoryId, categoryRules = emptyList(), categoryHistory = emptyList()) }
        categoryHistoryJob?.cancel()
        categoryHistoryJob = getCategoryHistoryUseCase(categoryId)
            .onEach { h -> _uiState.update { it.copy(categoryHistory = h) } }
            .catch { _uiState.update { it.copy(categoryHistory = emptyList()) } }
            .launchIn(viewModelScope)
        categoryRulesJob?.cancel()
        categoryRulesJob = getCategoryPricingRulesUseCase(categoryId)
            .onEach { rules -> _uiState.update { it.copy(categoryRules = rules) } }
            .catch { }
            .launchIn(viewModelScope)
    }

    fun closeCategoryDetail() {
        if (_uiState.value.isSavingPrice) return
        categoryRulesJob?.cancel()
        categoryHistoryJob?.cancel()
        _uiState.update { it.copy(selectedCategoryId = null, categoryRules = emptyList(), categoryHistory = emptyList()) }
    }

    fun saveCategoryOwnerPrice(ownerId: String, context: SaleContext, priceText: String) {
        val categoryId = _uiState.value.selectedCategoryId ?: return
        val minor = ProfileInputValidator.parsePriceMinor(priceText)
        if (ownerId.isBlank() || minor == null || !PricingPolicy.isPriceValid(minor)) {
            _uiState.update { it.copy(errorMessageResId = R.string.profile_error_price_invalid) }
            return
        }
        runPriceOp { saveCategoryOwnerPriceUseCase(categoryId, ownerId, context, minor) }
    }

    fun setRuleActive(rule: PricingRule, active: Boolean) = runPriceOp { setPricingRuleActiveUseCase(rule.id, active) }
    fun deleteRule(rule: PricingRule) = runPriceOp { deletePricingRuleUseCase(rule.id) }

    fun openCreateClassificationDialog() { _uiState.update { it.copy(showCreateClassificationDialog = true) } }
    fun closeCreateClassificationDialog() { if (!_uiState.value.isSubmitting) _uiState.update { it.copy(showCreateClassificationDialog = false) } }

    /** سرعة اتجاه واحد من نص مثل 5M أو 512k؛ فارغ = غير محدد (null)، غير صالح = -1 (يرفضه التحقق). */
    private fun parseSpeedKbps(text: String): Int? {
        if (text.isBlank()) return null
        val r = ProfileInputValidator.parseRateLimit(text.trim()) ?: return -1
        return if (text.contains('/')) -1 else r.rxKbps
    }

    /**
     * existing = null ⇒ إنشاء (المعرّف يُولَّد). الحقل الفارغ = إعداد غير معرَّف في التصنيف (يبقى لدى الباقة).
     * الحفظ لا يغيّر أي باقة أو مستخدم؛ التطبيق على الموجودين من صفحة التفاصيل بخطوة صريحة.
     */
    fun saveClassification(
        existing: ProfileClassification?, nameAr: String, nameEn: String, description: String?,
        downloadText: String, uploadText: String, sharedUsersText: String,
        uptimeText: String, uptimeUnit: UptimeUnit, transferText: String, transferUnit: TransferUnit
    ) {
        if (_uiState.value.isSubmitting) return
        val uptime = UsageLimits.parseUptime(uptimeText, uptimeUnit)
        val transfer = UsageLimits.parseTransfer(transferText, transferUnit)
        val shared = sharedUsersText.trim().takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: -1 }
        val classification = (existing ?: ProfileClassification(key = "CLS-${UUID.randomUUID().toString().take(8)}")).copy(
            nameAr = nameAr.trim(),
            nameEn = nameEn.trim().ifBlank { nameAr.trim() },
            description = description?.trim()?.ifBlank { null },
            downloadKbps = parseSpeedKbps(downloadText),
            uploadKbps = parseSpeedKbps(uploadText),
            sharedUsers = shared,
            limitUptimeSeconds = (uptime as? UsageLimits.Parsed.Value)?.amount,
            limitTransferBytes = (transfer as? UsageLimits.Parsed.Value)?.amount
        )
        val errors = ClassificationRules.validate(classification)
        val invalidInput = uptime is UsageLimits.Parsed.Invalid || transfer is UsageLimits.Parsed.Invalid
        if (errors.isNotEmpty() || invalidInput) {
            val first = errors.keys.firstOrNull()
            _uiState.update {
                it.copy(errorMessageResId = when {
                    first == "name" -> R.string.profile_error_name_required
                    first == "speed" -> R.string.profile_error_rate_invalid
                    first == "sharedUsers" -> R.string.class_err_shared_users
                    first == "uptime" || uptime is UsageLimits.Parsed.Invalid -> R.string.profile_error_limit_uptime
                    else -> R.string.profile_error_limit_transfer
                })
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true) }
            when (val res = guard { saveProfileClassificationUseCase(classification) }) {
                is AppResult.Success -> _uiState.update {
                    it.copy(isSubmitting = false, showCreateClassificationDialog = false, successMessageResId = R.string.profile_success_create)
                }
                is AppResult.Error -> _uiState.update { it.copy(isSubmitting = false, errorMessageResId = res.error.toMessageRes()) }
                else -> _uiState.update { it.copy(isSubmitting = false) }
            }
        }
    }

    fun setClassificationStatus(classification: ProfileClassification, status: CategoryStatus) {
        viewModelScope.launch {
            val res = guard { setClassificationStatusUseCase(classification.key, status) }
            _uiState.update {
                if (res is AppResult.Error) it.copy(errorMessageResId = res.error.toMessageRes())
                else it.copy(successMessageResId = R.string.profile_success_status)
            }
        }
    }

    // ------------------------------------------------------------------ صفحة تفاصيل التصنيف + التطبيق الصريح

    fun openClassificationDetail(key: String) {
        _uiState.update { it.copy(selectedClassificationKey = key, applyPlan = null, applyResult = null) }
    }

    fun closeClassificationDetail() {
        if (_uiState.value.isApplying) return
        _uiState.update { it.copy(selectedClassificationKey = null, applyPlan = null, applyResult = null) }
    }

    /** يحسب ما سيتغير (باقات + عدد المستخدمين) دون تطبيق أي شيء. */
    fun previewApply() {
        val key = _uiState.value.selectedClassificationKey ?: return
        if (_uiState.value.isPreviewingApply || _uiState.value.isApplying) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPreviewingApply = true, applyPlan = null, applyResult = null) }
            when (val res = guard { previewClassificationApplyUseCase(key) }) {
                is AppResult.Success -> _uiState.update { it.copy(isPreviewingApply = false, applyPlan = res.data) }
                is AppResult.Error -> _uiState.update { it.copy(isPreviewingApply = false, errorMessageResId = res.error.toMessageRes()) }
                else -> _uiState.update { it.copy(isPreviewingApply = false) }
            }
        }
    }

    fun dismissApplyPlan() { if (!_uiState.value.isApplying) _uiState.update { it.copy(applyPlan = null) } }
    fun dismissApplyResult() { _uiState.update { it.copy(applyResult = null) } }

    /** التنفيذ بعد تأكيد المستخدم للمعاينة المعروضة؛ النتيجة (نجاح/إخفاقات) تُعرض وتُسجَّل في سجل التدقيق. */
    fun confirmApply() {
        val key = _uiState.value.selectedClassificationKey ?: return
        if (_uiState.value.isApplying || _uiState.value.applyPlan == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(isApplying = true) }
            when (val res = guard { applyClassificationUseCase(key) }) {
                is AppResult.Success -> _uiState.update { it.copy(isApplying = false, applyPlan = null, applyResult = res.data) }
                is AppResult.Error -> _uiState.update { it.copy(isApplying = false, applyPlan = null, errorMessageResId = res.error.toMessageRes()) }
                else -> _uiState.update { it.copy(isApplying = false) }
            }
        }
    }

    /** استثناء غير متوقع من الخدمة يصبح AppResult.Error (لا انهيار ولا حالة إرسال عالقة). */
    // ------------------------------------------------------------------ الحالة (تعطيل/أرشفة/استعادة)

    fun openEdit(profile: Profile) { _uiState.update { it.copy(editProfile = profile) } }
    fun closeEdit() { _uiState.update { it.copy(editProfile = null) } }

    /** تعديل الاسم العربي والفئة والتصنيف فقط (السعر والمرجع التقني لهما نوافذهما، والسرعة تمس الراوتر). */
    fun saveEdit(profile: Profile, displayNameAr: String, categoryId: String?, classificationKey: String?) {
        if (_uiState.value.isSavingEdit) return
        val name = displayNameAr.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(errorMessageResId = R.string.profile_error_name_required) }
            return
        }
        val fields = buildMap<String, Any?> {
            if (name != profile.displayNameAr) put("display_name_ar", name)
            if (categoryId != profile.categoryId) put("category_id", categoryId)
            if (classificationKey != profile.classificationKey) {
                put("classification_key", classificationKey)
                val target = classificationKey?.let { k -> _uiState.value.classifications.firstOrNull { it.key == k } }
                val own = ProfileDetailRules.ownSettingsAfterReassign(profile, target)
                put("own_settings", ClassificationRules.ownSettingsToCsv(own))
            }
        }
        if (fields.isEmpty()) { closeEdit(); return }
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingEdit = true) }
            val res = guard { updateProfileUseCase(profile.id, fields) }
            _uiState.update {
                if (res is AppResult.Error) it.copy(isSavingEdit = false, errorMessageResId = res.error.toMessageRes())
                else it.copy(isSavingEdit = false, editProfile = null, successMessageResId = R.string.profile_success_updated)
            }
        }
    }

    // ------------------------------------------------------------------ صفحة تفاصيل الباقة (تعديل إداري / تشغيلي / تجاري)

    private var detailRulesJob: kotlinx.coroutines.Job? = null

    /** يتابع أسعار مالكي/وكلاء فئة الباقة المعروضة (للعرض فقط). */
    fun observeDetailCategory(categoryId: String?) {
        detailRulesJob?.cancel()
        if (categoryId.isNullOrBlank()) { _uiState.update { it.copy(detailRules = emptyList()) }; return }
        detailRulesJob = getCategoryPricingRulesUseCase(categoryId)
            .onEach { rules -> _uiState.update { it.copy(detailRules = rules) } }
            .catch { _uiState.update { it.copy(detailRules = emptyList()) } }
            .launchIn(viewModelScope)
    }

    private var usageJob: kotlinx.coroutines.Job? = null

    /** يعدّ من الخادم المستخدمين/الاشتراكات/الدفعات لكل بروفايل. عند الفشل لا تبقى أرقام قديمة معروضة كأنها حالية. */
    fun refreshUsage() {
        val ids = _uiState.value.profiles.map { it.profile.id }
        if (ids.isEmpty()) return
        usageJob?.cancel()
        usageJob = viewModelScope.launch {
            _uiState.update { it.copy(usageLoading = true) }
            when (val res = guard { getProfileUsageUseCase(ids) }) {
                is AppResult.Success -> _uiState.update { it.copy(usage = res.data, usageLoading = false, usageFailed = false) }
                else -> _uiState.update { it.copy(usage = emptyMap(), usageLoading = false, usageFailed = true) }
            }
        }
    }

    private var quotesJob: kotlinx.coroutines.Job? = null

    /** يحسب السعر النهائي ومصدره بسياسة التسعير الفعلية؛ يُستدعى عند فتح الصفحة وبعد تغيّر الأسعار. */
    fun loadDetailQuotes(profileId: String) {
        quotesJob?.cancel()
        quotesJob = viewModelScope.launch {
            val out = SaleContext.entries.associateWith { ctx ->
                (guard { resolveUnitPriceUseCase(profileId, null, ctx) } as? AppResult.Success)?.data
            }
            _uiState.update { it.copy(detailQuotes = out) }
        }
    }

    fun openSection(section: ProfileSection) {
        _uiState.update { it.copy(detailEditor = section, detailErrors = emptyMap(), pendingOperationalPlan = null) }
    }

    fun closeSection() {
        if (_uiState.value.isSavingSection) return
        _uiState.update { it.copy(detailEditor = null, detailErrors = emptyMap(), pendingOperationalPlan = null) }
    }

    /** يعيد المدير من خطوة التأكيد إلى النموذج دون فقد مدخلاته (النموذج يبقى مفتوحاً تحتها). */
    fun cancelOperationalConfirm() { _uiState.update { it.copy(pendingOperationalPlan = null) } }

    private fun detailErrorRes(code: String): Int = when (code) {
        ProfileDetailRules.ERR_NAME_REQUIRED -> R.string.profile_error_name_required
        ProfileDetailRules.ERR_SPEED_INVALID -> R.string.profile_error_speed_one_way
        ProfileDetailRules.ERR_SHARED_USERS_INVALID -> R.string.profile_error_shared_users
        ProfileDetailRules.ERR_UPTIME_INVALID -> R.string.profile_error_limit_uptime
        ProfileDetailRules.ERR_TRANSFER_INVALID -> R.string.profile_error_limit_transfer
        ProfileDetailRules.ERR_VALIDITY_INVALID -> R.string.profile_error_validity
        else -> R.string.profile_error_price_invalid
    }

    private fun runSectionSave(block: suspend () -> AppResult<Unit>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingSection = true) }
            val res = guard { block() }
            _uiState.update {
                if (res is AppResult.Error) it.copy(isSavingSection = false, pendingOperationalPlan = null, errorMessageResId = res.error.toMessageRes())
                else it.copy(isSavingSection = false, detailEditor = null, pendingOperationalPlan = null, detailErrors = emptyMap(), successMessageResId = R.string.profile_success_updated)
            }
        }
    }

    /** تعديل إداري: الأسماء والوصف والفئة والتصنيف. */
    fun saveAdminSection(profile: Profile, input: ProfileDetailRules.AdminInput) {
        if (_uiState.value.isSavingSection) return
        when (val plan = ProfileDetailRules.planAdmin(profile, input, _uiState.value.classifications)) {
            is ProfileDetailRules.AdminPlan.Invalid ->
                _uiState.update { it.copy(detailErrors = plan.errors.mapValues { (_, c) -> detailErrorRes(c) }) }
            is ProfileDetailRules.AdminPlan.Ok ->
                if (plan.isEmpty) closeSection() else runSectionSave { updateProfileUseCase(profile.id, plan.fields) }
        }
    }

    /**
     * تعديل تشغيلي، الخطوة 1: تحقق ثم خطة الأثر للمراجعة. لا يُكتب شيء قبل [confirmOperationalSection].
     */
    fun reviewOperationalSection(profile: Profile, input: ProfileDetailRules.OperationalInput) {
        if (_uiState.value.isSavingSection) return
        val classification = profile.classificationKey?.let { k -> _uiState.value.classifications.firstOrNull { it.key == k } }
        when (val out = ProfileDetailRules.planOperational(profile, classification, input)) {
            is ProfileDetailRules.OperationalOutcome.Invalid ->
                _uiState.update { it.copy(detailErrors = out.errors.mapValues { (_, c) -> detailErrorRes(c) }) }
            is ProfileDetailRules.OperationalOutcome.Ok ->
                if (out.plan.isEmpty) closeSection()
                else _uiState.update { it.copy(detailErrors = emptyMap(), pendingOperationalPlan = out.plan) }
        }
    }

    /** الخطوة 2: التنفيذ بعد تأكيد المدير لأثر التعديل. المستخدمون القائمون لا يُعدَّلون هنا (انظر [ProfileDetailRules.OperationalPlan]). */
    fun confirmOperationalSection() {
        val plan = _uiState.value.pendingOperationalPlan ?: return
        if (_uiState.value.isSavingSection) return
        runSectionSave { updateProfileUseCase(plan.profileId, plan.fields) }
    }

    /** تعديل تجاري: سعر الفئة (يسري على كل باقاتها، للعمليات الجديدة) أو سعر الباقة القديم إن لم تكن لها فئة. */
    fun saveCommercialSection(profile: Profile, category: ProfileCategory?, input: ProfileDetailRules.CommercialInput) {
        if (_uiState.value.isSavingSection) return
        when (val out = ProfileDetailRules.planCommercial(profile, category, input)) {
            is ProfileDetailRules.CommercialOutcome.Invalid ->
                _uiState.update { it.copy(detailErrors = out.errors.mapValues { (_, c) -> detailErrorRes(c) }) }
            is ProfileDetailRules.CommercialOutcome.Ok -> when {
                out.noChange -> closeSection()
                out.target == ProfileDetailRules.PriceTarget.CATEGORY -> runSectionSave { saveProfileCategoryUseCase(out.category!!) }
                else -> runSectionSave { updateProfileUseCase(profile.id, out.profileFields) }
            }
        }
    }

    fun setProfileStatus(profile: Profile, status: ProfileStatus) {
        viewModelScope.launch {
            val res = guard { setProfileStatusUseCase(profile.id, status) }
            _uiState.update {
                if (res is AppResult.Error) it.copy(errorMessageResId = res.error.toMessageRes())
                else it.copy(successMessageResId = R.string.profile_success_status)
            }
        }
    }

    fun setCategoryStatus(category: ProfileCategory, status: CategoryStatus) {
        viewModelScope.launch {
            val res = guard { setCategoryStatusUseCase(category.id, status) }
            _uiState.update {
                if (res is AppResult.Error) it.copy(errorMessageResId = res.error.toMessageRes())
                else it.copy(successMessageResId = R.string.profile_success_status)
            }
        }
    }

    fun requestDeleteCategory(category: ProfileCategory) { _uiState.update { it.copy(categoryPendingDelete = category) } }
    fun dismissDeleteCategory() { _uiState.update { it.copy(categoryPendingDelete = null) } }
    fun confirmDeleteCategory() {
        val cat = _uiState.value.categoryPendingDelete ?: return
        viewModelScope.launch {
            val res = guard { deleteProfileCategoryUseCase(cat.id) }
            _uiState.update {
                if (res is AppResult.Error) it.copy(categoryPendingDelete = null, errorMessageResId = res.error.toMessageRes())
                else it.copy(categoryPendingDelete = null, successMessageResId = R.string.profile_success_delete)
            }
        }
    }

    // ------------------------------------------------------------------ تغيير المرجع التقني (بروفايل MikroTik)

    fun openReferenceChange(profile: Profile) {
        _uiState.update {
            it.copy(referenceProfile = profile, referenceOptions = emptyList(), referenceImpact = null,
                referenceErrorResId = null)
        }
        viewModelScope.launch {
            // خيارات الراوتر للاختيار منها؛ تعذّرها لا يمنع كتابة اسم جديد يدوياً (والمعاينة تتحقق من الراوتر وتفشل بوضوح)
            val res = guard { getRouterProfileOptionsUseCase() }
            if (res is AppResult.Success) _uiState.update { it.copy(referenceOptions = res.data) }
        }
    }

    fun closeReferenceChange() {
        if (_uiState.value.isApplyingReference) return
        _uiState.update { it.copy(referenceProfile = null, referenceImpact = null, referenceErrorResId = null, referenceOptions = emptyList()) }
    }

    /** أي تعديل على الاسم المكتوب يُبطل المعاينة السابقة؛ لا يُعتمد إلا أثر محسوب للاسم الحالي نفسه. */
    fun onReferenceInputChanged() {
        _uiState.update { it.copy(referenceImpact = null, referenceErrorResId = null) }
    }

    fun previewReference(newName: String) {
        val profile = _uiState.value.referenceProfile ?: return
        if (_uiState.value.isCheckingReference) return
        _uiState.update { it.copy(isCheckingReference = true, referenceImpact = null, referenceErrorResId = null) }
        viewModelScope.launch {
            when (val res = guard { previewReferenceChangeUseCase(profile.id, newName) }) {
                is AppResult.Success -> _uiState.update { it.copy(isCheckingReference = false, referenceImpact = res.data) }
                is AppResult.Error -> _uiState.update { it.copy(isCheckingReference = false, referenceErrorResId = res.error.toMessageRes()) }
                else -> _uiState.update { it.copy(isCheckingReference = false) }
            }
        }
    }

    fun confirmReferenceChange() {
        val st = _uiState.value
        val impact = st.referenceImpact ?: return
        if (st.isApplyingReference || impact.action == ReferenceChangeAction.BLOCKED) return
        _uiState.update { it.copy(isApplyingReference = true, referenceErrorResId = null) }
        viewModelScope.launch {
            // مباشر (skipQueue) لأن الاعتماد مبني على فحص حيّ للراوتر؛ لا نُدرجه في طابور بلا تحقق
            val res = guard {
                migrateProfileRenameUseCase(
                    impact.profileId, impact.newName, skipQueue = true,
                    // User Manager: المستخدمون مرتبطون بالباقة نفسها؛ إسنادهم لباقة أخرى تجديد ولا يُنفَّذ (المستودع يرفضه)
                    repointUsers = false
                )
            }
            _uiState.update {
                if (res is AppResult.Error) it.copy(isApplyingReference = false, referenceErrorResId = res.error.toMessageRes())
                else it.copy(isApplyingReference = false, referenceProfile = null, referenceImpact = null, successMessageResId = R.string.profile_success_reference)
            }
        }
    }

    // ------------------------------------------------------------------ عمليات الأسعار

    private fun runPriceOp(block: suspend () -> AppResult<Unit>) {
        if (_uiState.value.isSavingPrice) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingPrice = true) }
            val res = guard(block)
            _uiState.update {
                if (res is AppResult.Error) it.copy(isSavingPrice = false, errorMessageResId = res.error.toMessageRes())
                else it.copy(isSavingPrice = false, successMessageResId = R.string.profile_success_price)
            }
        }
    }

    private suspend fun <T> guard(block: suspend () -> AppResult<T>): AppResult<T> = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        AppResult.Error(ApiError.mapThrowable(e))
    }

    @StringRes
    private fun ApiError.toMessageRes(): Int = when {
        code == "NO_CONFIG" -> R.string.profile_error_no_router
        code == "NOT_FOUND" -> R.string.profile_error_not_found
        code == ApiError.CODE_NETWORK_UNAVAILABLE -> R.string.router_error_unreachable
        code == ApiError.CODE_TIMEOUT -> R.string.router_error_timeout
        code == "ERR_REFERENCE_NEW_NOT_ON_ROUTER" -> R.string.profile_ref_blocked_not_on_router
        code == "ERR_REFERENCE_USED_BY_OTHER_PROFILE" -> R.string.profile_ref_blocked_used
        code == "ERR_REFERENCE_SAME_NAME" -> R.string.profile_ref_blocked_same
        code == "ERR_REFERENCE_INVALID_NAME" -> R.string.profile_ref_blocked_invalid
        code == "ERR_INTEGRITY_NOT_READY" -> R.string.profile_error_integrity
        code == "ERR_PROFILE_IN_USE" -> R.string.profile_error_in_use
        code == RouterProfileImport.ERR_VALIDITY_NOT_REPRESENTABLE -> R.string.profile_error_router_validity_unsupported
        code == RouterProfileImport.ERR_LIMIT_NOT_REPRESENTABLE -> R.string.profile_error_router_limit_unsupported
        code == "ERR_ROUTER_VERIFY_MISMATCH" -> R.string.profile_error_router_verify_mismatch
        code == "ERR_PROFILE_HAS_ROUTER_USERS" -> R.string.profile_error_has_router_users
        code == "ERR_CATEGORY_NAME_EXISTS" -> R.string.profile_error_category_exists
        code == "ERR_CATEGORY_NOT_EMPTY" -> R.string.profile_error_category_not_empty
        code == "ERR_CATEGORY_INACTIVE" -> R.string.profile_error_category_inactive
        code == "ERR_CATEGORY_CLASS_REQUIRED" -> R.string.sysclass_err_class_required
        code == "ERR_CATEGORY_CLASS_IMMUTABLE" -> R.string.sysclass_err_class_immutable
        code == "ERR_PROFILE_CATEGORY_REQUIRED" -> R.string.sysclass_err_category_required
        code == "ERR_CATEGORY_UNCLASSIFIED" -> R.string.sysclass_err_category_unclassified
        code == "ERR_PROFILE_MOVE_CLASS_MISMATCH" -> R.string.sysclass_err_move_mismatch
        code == PricingPolicy.ERR_PRICE_UNAVAILABLE -> R.string.profile_error_price_unavailable
        code == CategoryRules.ERR_CURRENCY_IMMUTABLE -> R.string.cat_err_currency_immutable
        code == CategoryRules.ERR_CURRENCY_REQUIRED -> R.string.cat_err_currency_required
        code == "ERR_CATEGORY_HAS_RULES" -> R.string.cat_err_has_rules
        code == ClassificationRules.ERR_INACTIVE -> R.string.class_err_inactive
        code == ClassificationRules.ERR_NAME_EXISTS -> R.string.class_err_name_exists
        code == ApiError.CODE_VALIDATION_FAILED -> R.string.ERR_VALIDATION_FAILED
        code == ApiError.CODE_ROUTER_AUTH_FAILED -> R.string.router_error_auth_failed
        code == ApiError.CODE_ROUTER_REST_UNAVAILABLE -> R.string.router_error_rest_unavailable
        code == ApiError.CODE_ROUTER_SSL_UNTRUSTED -> R.string.router_error_ssl_untrusted
        code.startsWith(ApiError.CODE_HTTP_ERROR) -> R.string.profile_error_router_rejected
        else -> ErrorTranslator.resIdOrNull(code) ?: R.string.ERR_UNKNOWN
    }

}

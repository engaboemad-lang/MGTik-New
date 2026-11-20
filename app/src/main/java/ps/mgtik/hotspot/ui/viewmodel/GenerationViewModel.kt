package ps.mgtik.hotspot.ui.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.IdempotencyKeyProvider
import ps.mgtik.hotspot.data.local.prefs.AppPreferences
import ps.mgtik.hotspot.domain.generation.GenerationDefaults
import ps.mgtik.hotspot.domain.generation.GenerationRules
import ps.mgtik.hotspot.domain.generation.SagaError
import ps.mgtik.hotspot.domain.model.Execution
import ps.mgtik.hotspot.domain.model.FailureReason
import ps.mgtik.hotspot.domain.model.GenerationCommandRequest
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.VoucherBatch
import ps.mgtik.hotspot.domain.model.ProfileStatus
import ps.mgtik.hotspot.domain.usecase.CancelExecutionUseCase
import ps.mgtik.hotspot.domain.usecase.GetProfilesUseCase
import ps.mgtik.hotspot.domain.usecase.ObserveBatchFailureReasonsUseCase
import ps.mgtik.hotspot.domain.usecase.ObserveUsernameSequencesUseCase
import ps.mgtik.hotspot.domain.usecase.GetSessionStateUseCase
import ps.mgtik.hotspot.domain.usecase.GetExecutionStateUseCase
import ps.mgtik.hotspot.domain.usecase.ObserveGeneratedBatchUseCase
import ps.mgtik.hotspot.domain.usecase.RetryFailedExecutionUseCase
import ps.mgtik.hotspot.domain.usecase.StartNewOrderGenerationUseCase
import ps.mgtik.hotspot.ui.util.ErrorTranslator
import javax.inject.Inject

/** رسائل أخطاء التوليد المترجمة (مصدر واحد للـ VM والشاشات). */
object GenerationMessages {
    @StringRes
    fun forCode(code: String?): Int = when (code) {
        GenerationRules.ERR_OWNER_REQUIRED -> R.string.gen_error_owner_required
        GenerationRules.ERR_PROFILE_REQUIRED -> R.string.gen_error_profile_required
        GenerationRules.ERR_COUNT_INVALID -> R.string.gen_error_count_invalid
        GenerationRules.ERR_COUNT_LIMIT_EXCEEDED -> R.string.gen_error_count_limit
        GenerationRules.ERR_LENGTH_INVALID -> R.string.gen_error_length_invalid
        GenerationRules.ERR_PREFIX_INVALID -> R.string.gen_error_prefix_invalid
        GenerationRules.ERR_FORMAT_INVALID -> R.string.gen_error_format_invalid
        GenerationRules.ERR_KEYSPACE_TOO_SMALL -> R.string.gen_error_keyspace
        GenerationRules.ERR_MODE_INVALID -> R.string.gen_error_mode_invalid
        GenerationRules.ERR_SEQUENTIAL_NUMERIC_ONLY -> R.string.gen_error_sequential_numeric
        GenerationRules.ERR_PASSWORD_TYPE_INVALID -> R.string.gen_error_password_type
        GenerationRules.ERR_PASSWORD_LENGTH_INVALID -> R.string.gen_error_password_length
        GenerationRules.ERR_COMMENT_INVALID -> R.string.gen_error_comment_invalid
        SagaError.SEQUENCE_EXHAUSTED -> R.string.gen_error_sequence_exhausted
        SagaError.KEYSPACE_EXHAUSTED -> R.string.gen_error_keyspace_exhausted
        SagaError.ALL_FAILED -> R.string.gen_error_all_failed
        SagaError.ATTEMPTS_EXHAUSTED -> R.string.gen_error_attempts
        SagaError.CANCEL_AFTER_BILLING -> R.string.gen_error_cancel_after_billing
        "ERR_GEN_NOT_RETRYABLE" -> R.string.gen_error_not_retryable
        "ERR_GEN_OWNER_UNAVAILABLE" -> R.string.gen_error_owner_unavailable
        "ERR_GEN_PROFILE_UNAVAILABLE" -> R.string.gen_error_profile_unavailable
        "ERR_PROFILE_CLASS_MISMATCH" -> R.string.sysclass_err_profile_class_mismatch
        "ERR_PRICE_UNAVAILABLE" -> R.string.profile_error_price_unavailable
        "ERR_GEN_PROFILE_LIMIT_INVALID" -> R.string.gen_error_profile_limit_invalid
        "NO_CONFIG" -> R.string.gen_error_no_router
        ApiError.CODE_ROUTER_AUTH_FAILED -> R.string.router_error_auth_failed
        ApiError.CODE_ROUTER_REST_UNAVAILABLE -> R.string.router_error_rest_unavailable
        ApiError.CODE_ROUTER_SSL_UNTRUSTED -> R.string.router_error_ssl_untrusted
        ApiError.CODE_NETWORK_UNAVAILABLE -> R.string.ERR_NETWORK_UNAVAILABLE
        ApiError.CODE_TIMEOUT -> R.string.ERR_TIMEOUT
        else -> ErrorTranslator.resIdOrNull(code) ?: R.string.gen_error_unknown
    }
}

data class GenerationUiState(
    /** الباقات الفعّالة فقط (المفقودة على الراوتر والمؤرشفة لا تصلح للتوليد). */
    val profiles: List<Profile> = emptyList(),
    val activeExecution: Execution? = null,
    val activeBatch: VoucherBatch? = null,
    val isSubmitting: Boolean = false,
    /** وصلت أول قراءة من Room للتنفيذ/الدفعة المطلوبة (null بعد الوصول = غير موجود، لا تحميل أبدي). */
    val executionResolved: Boolean = false,
    val batchResolved: Boolean = false,
    /** حقل ← مورد رسالة (أخطاء التحقق في S4). */
    val fieldErrors: Map<String, Int> = emptyMap(),
    @StringRes val errorMessageResId: Int? = null,
    /** العدّاد التسلسلي الدائم لكل بروفايل (معرّف البروفايل ← آخر رقم مستخدم). */
    val sequences: Map<String, Long> = emptyMap(),
    /** أسباب فشل حسابات الدفعة الحالية على الراوتر (S6). */
    val failureReasons: List<FailureReason> = emptyList(),
    /** قواعد التسعير (الأسعار الخاصة) للباقة المختارة في S4؛ تُستخدم مع PricingPolicy لتقدير السعر بنفس منطق المحرك. */
    val pricingRules: List<ps.mgtik.hotspot.domain.model.PricingRule> = emptyList(),
    /** فئات الباقات: مصدر الأسعار (جملة/تجزئة) والعملة لتقدير سعر التوليد. */
    val categories: List<ps.mgtik.hotspot.domain.model.ProfileCategory> = emptyList()
)

/** مدخلات نموذج التوليد (S4) غير الباقة. */
data class GenerationInput(
    val ownerId: String?,
    val count: Int,
    val prefix: String,
    val formatName: String,
    val codeLength: Int,
    val mode: String,
    val passwordType: String,
    val passwordLength: Int,
    val comment: String
)

sealed interface GenerationEvent {
    /** سُجِّل التنفيذ محلياً (بمعرّف حقيقي) ⇒ انتقل لـ S5. */
    data class Started(val executionId: String) : GenerationEvent
}

/**
 * GenerationViewModel - يقود S4/S5/S6 فوق ساغا التوليد (تعمل في WorkManager وتنجو من إغلاق التطبيق).
 * الحالة تُقرأ من Room عبر المستودع؛ لا شيء هنا ينتظر الشبكة.
 */
@HiltViewModel
class GenerationViewModel @Inject constructor(
    private val getSessionStateUseCase: GetSessionStateUseCase,
    private val startNewOrderGenerationUseCase: StartNewOrderGenerationUseCase,
    private val getExecutionStateUseCase: GetExecutionStateUseCase,
    private val observeGeneratedBatchUseCase: ObserveGeneratedBatchUseCase,
    private val retryFailedExecutionUseCase: RetryFailedExecutionUseCase,
    private val cancelExecutionUseCase: CancelExecutionUseCase,
    private val getProfilesUseCase: GetProfilesUseCase,
    private val observeUsernameSequencesUseCase: ObserveUsernameSequencesUseCase,
    private val observeBatchFailureReasonsUseCase: ObserveBatchFailureReasonsUseCase,
    private val getPricingRulesUseCase: ps.mgtik.hotspot.domain.usecase.GetPricingRulesUseCase,
    private val getCategoryPricingRulesUseCase: ps.mgtik.hotspot.domain.usecase.GetCategoryPricingRulesUseCase,
    private val getProfileCategoriesUseCase: ps.mgtik.hotspot.domain.usecase.GetProfileCategoriesUseCase,
    private val preferences: AppPreferences
) : ViewModel() {

    /**
     * إعدادات التوليد الافتراضية: آخر ما استُعمل لهذه الباقة، وإلا آخر ما استُعمل عموماً، وإلا القيم المصنعية.
     * محلية فقط (لا تعدّل الباقة)؛ قيمة تالفة أو لم تعد صالحة تُتجاهل.
     */
    fun defaultsFor(profileId: String?): GenerationDefaults =
        profileId?.let { GenerationDefaults.decode(preferences.generationDefaults(it)) }
            ?: GenerationDefaults.decode(preferences.generationDefaults(null))
            ?: GenerationDefaults.FACTORY

    private fun rememberDefaults(profileId: String, input: GenerationInput) {
        val d = GenerationDefaults(input.prefix, input.codeLength, input.formatName, input.mode, input.passwordType, input.passwordLength)
        preferences.setGenerationDefaults(profileId, d.encode())
        preferences.setGenerationDefaults(null, d.encode())
    }

    private var pricingJob: kotlinx.coroutines.Job? = null

    /** تُستدعى من S4 عند تغيّر الباقة المختارة؛ التقدير فقط — القرار النهائي للسعر في المحرك السحابي. */
    fun observePricing(profileId: String?) {
        pricingJob?.cancel()
        if (profileId == null) { _uiState.update { it.copy(pricingRules = emptyList()) }; return }
        val categoryId = _uiState.value.profiles.firstOrNull { it.id == profileId }?.categoryId.orEmpty()
        val categoryRules = if (categoryId.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList()) else getCategoryPricingRulesUseCase(categoryId)
        // أسعار الفئة الخاصة + أسعار البروفايل القديمة (ما زالت تُحترم للبيانات القديمة)
        pricingJob = kotlinx.coroutines.flow.combine(categoryRules, getPricingRulesUseCase(profileId)) { a, b -> a + b }
            .onEach { rules -> _uiState.update { it.copy(pricingRules = rules) } }
            .catch { _uiState.update { st -> st.copy(pricingRules = emptyList()) } }
            .launchIn(viewModelScope)
    }

    private val _uiState = MutableStateFlow(GenerationUiState())
    val uiState: StateFlow<GenerationUiState> = _uiState.asStateFlow()

    private val _events = Channel<GenerationEvent>(Channel.BUFFERED)
    val events: Flow<GenerationEvent> = _events.receiveAsFlow()

    init {
        getSessionStateUseCase.gate(onSignedOut = { _uiState.update { it.copy(profiles = emptyList()) } }) { getProfilesUseCase() }
            .onEach { list -> _uiState.update { it.copy(profiles = list.filter { p -> p.status == ProfileStatus.ACTIVE && p.linkedProfileName.isNotBlank() }) } }
            .catch { /* القائمة تبقى فارغة؛ S4 تعرض حالة «لا باقات» */ }
            .launchIn(viewModelScope)

        getSessionStateUseCase.gate(onSignedOut = { _uiState.update { it.copy(sequences = emptyMap()) } }) { observeUsernameSequencesUseCase() }
            .onEach { map -> _uiState.update { it.copy(sequences = map) } }
            .catch { /* للمعاينة فقط؛ الحجز الفعلي يقرأ من السحابة وقت التوليد */ }
            .launchIn(viewModelScope)

        getSessionStateUseCase.gate(onSignedOut = { _uiState.update { it.copy(categories = emptyList()) } }) { getProfileCategoriesUseCase() }
            .onEach { list -> _uiState.update { it.copy(categories = list) } }
            .catch { /* التقدير فقط */ }
            .launchIn(viewModelScope)

    }

    private var executionJob: Job? = null
    private var batchJob: Job? = null
    private var reasonsJob: Job? = null

    /**
     * مفتاح التفرّد لجلسة النموذج الحالية: يبقى ثابتاً عبر المحاولات، ويتغيّر فقط بعد تسجيل ناجح.
     * كل المعرّفات (تنفيذ/طلب/دفعة) مشتقة منه ⇒ النقرة المزدوجة وإعادة الإرسال لا تُنتج دفعتين.
     */
    private var formKey: String? = null

    fun startGeneration(profile: Profile?, input: GenerationInput) {
        if (_uiState.value.isSubmitting) return

        val errors = GenerationRules.validate(
            profile?.id.orEmpty(), input.ownerId, input.count, input.formatName, input.prefix, input.codeLength,
            input.mode, input.passwordType, input.passwordLength, input.comment
        ).toMutableMap()
        if (errors.isNotEmpty() || profile == null) {
            _uiState.update { it.copy(fieldErrors = errors.mapValues { (_, code) -> GenerationMessages.forCode(code) }) }
            return
        }

        val key = formKey ?: IdempotencyKeyProvider.generateKey().also { formKey = it }
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, fieldErrors = emptyMap(), errorMessageResId = null) }
            val request = GenerationCommandRequest(
                profileId = profile.id,
                profileName = profile.linkedProfileName,
                ownerId = input.ownerId,
                count = input.count,
                format = input.formatName,
                prefix = input.prefix,
                codeLength = input.codeLength,
                mode = input.mode,
                passwordType = input.passwordType,
                passwordLength = input.passwordLength,
                comment = input.comment.trim()
            )
            val startResult = try {
                startNewOrderGenerationUseCase(request, key)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.Error(ApiError.mapThrowable(e))
            }
            when (val res = startResult) {
                is AppResult.Success -> {
                    formKey = null // الجلسة القادمة مفتاح جديد
                    rememberDefaults(profile.id, input)
                    _uiState.update { it.copy(isSubmitting = false) }
                    _events.send(GenerationEvent.Started(res.data.executionId))
                }
                is AppResult.Error -> _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        fieldErrors = res.error.fieldErrors?.mapValues { (_, code) -> GenerationMessages.forCode(code) } ?: emptyMap(),
                        errorMessageResId = GenerationMessages.forCode(res.error.code)
                    )
                }
                else -> _uiState.update { it.copy(isSubmitting = false) }
            }
        }
    }

    /** مراقبة تنفيذ بعينه (S5/S6)؛ ينجو من إعادة التشغيل لأن المصدر Room. */
    fun observeExecution(executionId: String) {
        if (_uiState.value.activeExecution?.executionId == executionId && executionJob?.isActive == true) return
        executionJob?.cancel()
        batchJob?.cancel()
        _uiState.update { it.copy(activeExecution = null, activeBatch = null, executionResolved = false, batchResolved = false) }

        executionJob = getExecutionStateUseCase(executionId)
            .onEach { execution ->
                _uiState.update { it.copy(activeExecution = execution, executionResolved = true) }
                val batchId = execution?.contextId
                if (!batchId.isNullOrBlank() && batchJob?.isActive != true) observeBatch(batchId)
            }
            .catch { _uiState.update { s -> s.copy(errorMessageResId = R.string.gen_error_unknown, executionResolved = true) } }
            .launchIn(viewModelScope)
    }

    /** مراقبة الدفعة مباشرة (S6 من أي مدخل). */
    fun observeBatch(batchId: String) {
        batchJob?.cancel()
        reasonsJob?.cancel()
        _uiState.update { it.copy(batchResolved = false, failureReasons = emptyList()) }
        reasonsJob = observeBatchFailureReasonsUseCase(batchId)
            .onEach { list -> _uiState.update { it.copy(failureReasons = list) } }
            .catch { /* الأسباب تفصيل إضافي؛ غيابها لا يمنع النتيجة */ }
            .launchIn(viewModelScope)
        batchJob = observeGeneratedBatchUseCase(batchId)
            .onEach { batch -> _uiState.update { it.copy(activeBatch = batch, batchResolved = true) } }
            .catch { _uiState.update { s -> s.copy(errorMessageResId = R.string.gen_error_unknown, batchResolved = true) } }
            .launchIn(viewModelScope)
    }

    /** عملية تحكم واحدة (إعادة/إلغاء) في كل مرة: النقر المزدوج يُتجاهل بدل تكرار الطلب. */
    private var controlJob: Job? = null

    private fun control(block: suspend () -> AppResult<*>) {
        if (controlJob?.isActive == true) return
        controlJob = viewModelScope.launch {
            val res = try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.Error(ApiError.mapThrowable(e))
            }
            if (res is AppResult.Error) {
                _uiState.update { it.copy(errorMessageResId = GenerationMessages.forCode(res.error.code)) }
            }
        }
    }

    fun retry(executionId: String) =
        control { retryFailedExecutionUseCase(executionId, IdempotencyKeyProvider.generateKey()) }

    fun cancel(executionId: String) = control { cancelExecutionUseCase(executionId) }

    /** مالك يُختار مسبقاً عند فتح معالج التوليد من ملفه؛ يُستهلك مرة واحدة فلا يثبت على فتحات لاحقة. */
    private var preselectedOwnerId: String? = null
    fun preselectOwner(ownerId: String) { preselectedOwnerId = ownerId }
    fun consumePreselectedOwner(): String? = preselectedOwnerId.also { preselectedOwnerId = null }

    fun clearFieldError(field: String) { _uiState.update { it.copy(fieldErrors = it.fieldErrors - field) } }
    fun consumeError() { _uiState.update { it.copy(errorMessageResId = null) } }

    fun resetState() {
        executionJob?.cancel()
        batchJob?.cancel()
        reasonsJob?.cancel()
        formKey = null
        // العدّاد التسلسلي معلومة مرجعية لا تُمسح مع إعادة تعيين النموذج
        _uiState.update { GenerationUiState(profiles = it.profiles, sequences = it.sequences) }
    }
}

package ps.mgtik.hotspot.ui.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.core.network.RouterConfig
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.ConfigLoadState
import ps.mgtik.hotspot.domain.model.RouterInfo
import ps.mgtik.hotspot.domain.model.RouterSaveOutcome
import ps.mgtik.hotspot.domain.usecase.ObserveRouterStatusUseCase
import ps.mgtik.hotspot.domain.usecase.RetryRouterConnectionUseCase
import ps.mgtik.hotspot.domain.usecase.SaveRouterConfigUseCase
import ps.mgtik.hotspot.domain.usecase.SkipRouterConnectionUseCase
import ps.mgtik.hotspot.domain.usecase.TestRouterConnectionUseCase
import ps.mgtik.hotspot.ui.util.ErrorTranslator
import ps.mgtik.hotspot.ui.util.RouterErrorTexts
import javax.inject.Inject

/** خطوة فحص معروضة في S2؛ العنوان مورد نصي (لا نص مضمَّن) ليدعم تعدد اللغات. */
data class RouterTestStep(
    @StringRes val titleRes: Int,
    val status: TestStepStatus = TestStepStatus.IDLE
)

enum class TestStepStatus { IDLE, LOADING, SUCCESS, ERROR }

/**
 * حالة شاشة الإعداد S2. مستقلة تماماً عن حالة الاتصال: تحميل الإعداد المحفوظ لا يعطّل الحقول، وفشل الاتصال
 * لا يمنع التعديل أو الحفظ.
 */
data class RouterUiState(
    /** الإعداد المحفوظ كما وصل من المدير المركزي (host فارغ = لم يُحفظ شيء). */
    val savedConfig: RouterConfig = RouterConfig(host = ""),
    /** تحميل الإعداد المحفوظ: مؤشر خفيف غير مانع؛ ينتهي دائماً بـ LOADED أو UNAVAILABLE (مهلة). */
    val configLoad: ConfigLoadState = ConfigLoadState.LOADING,
    val isTesting: Boolean = false,
    val isSaving: Boolean = false,
    val testResult: AppResult<RouterInfo>? = null,
    @StringRes val testErrorRes: Int? = null,
    /** تلميح علاج عملي للسبب (إن وُجد)؛ يُعرض تحت رسالة الفشل مع إمكانية إعادة الاختبار. */
    @StringRes val testHintRes: Int? = null,
    val testSteps: List<RouterTestStep> = emptyList(),
    /** حُفظ الإعداد بنجاح لكن الاتصال به فشل: تُعرض خيارات المتابعة (إعادة/تخطي). */
    val savedButNotConnected: Boolean = false,
    /** إعداد ينتظر قرار المستخدم لأنه لم يُختبر (أو تغيّر بعد آخر اختبار ناجح)؛ null = لا حوار. */
    val pendingUntestedConfig: RouterConfig? = null
) {
    val canSave: Boolean get() = !isTesting && !isSaving
}

sealed interface RouterSetupEvent {
    /** حُفظ الإعداد ونجح الاتصال به. */
    data object Saved : RouterSetupEvent
    /** حُفظ الإعداد لكن الاتصال فشل؛ تبقى الشاشة مفتوحة لقرار المستخدم. */
    data class SavedNotConnected(@StringRes val messageRes: Int) : RouterSetupEvent
    /** لم يُحفظ شيء (الإعداد السابق سليم). */
    data class SaveFailed(@StringRes val messageRes: Int) : RouterSetupEvent
    /** اختار المستخدم المتابعة دون اتصال. */
    data object SkippedConnection : RouterSetupEvent
}

@HiltViewModel
class RouterViewModel @Inject constructor(
    private val observeRouterStatus: ObserveRouterStatusUseCase,
    private val testRouterConnectionUseCase: TestRouterConnectionUseCase,
    private val saveRouterConfigUseCase: SaveRouterConfigUseCase,
    private val skipRouterConnection: SkipRouterConnectionUseCase,
    private val retryRouterConnection: RetryRouterConnectionUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(RouterUiState())
    val uiState: StateFlow<RouterUiState> = _uiState.asStateFlow()

    /** نسخة الإعدادات التي نجح اختبارها آخر مرة (في الذاكرة فقط؛ لا تُسجَّل ولا تُحفظ). */
    private var lastTestedConfig: RouterConfig? = null

    private val _events = Channel<RouterSetupEvent>(Channel.BUFFERED)
    val events: Flow<RouterSetupEvent> = _events.receiveAsFlow()

    init {
        // مصدر واحد: المدير المركزي (لا مراقبة Firestore ثانية هنا). بلا بوابة جلسة: المدير يتولاها ويصفّر عند الخروج.
        observeRouterStatus()
            .onEach { st -> _uiState.update { it.copy(savedConfig = st.config, configLoad = st.configLoad) } }
            .catch { _uiState.update { it.copy(configLoad = ConfigLoadState.UNAVAILABLE) } }
            .launchIn(viewModelScope)
    }

    /** الـ ViewModel مرتبط بالـ Activity: نصفّر أثر أي فحص سابق عند فتح الشاشة من جديد. */
    fun onScreenEntered() {
        _uiState.update {
            if (it.isTesting || it.isSaving) it else it.copy(
                testResult = null,
                testErrorRes = null,
                testSteps = emptyList(),
                savedButNotConnected = false
            )
        }
    }

    /** أي تعديل على الحقول يُبطل نتيجة الفحص السابقة. */
    fun onDraftChanged() {
        _uiState.update {
            if (it.testResult == null && !it.savedButNotConnected) it else it.copy(
                testResult = null,
                testErrorRes = null,
                testSteps = emptyList(),
                savedButNotConnected = false
            )
        }
    }

    fun testConnection(config: RouterConfig) {
        if (_uiState.value.isTesting || _uiState.value.isSaving) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isTesting = true,
                    testResult = null,
                    testErrorRes = null,
                    savedButNotConnected = false,
                    testSteps = buildSteps(TestStepStatus.LOADING, TestStepStatus.IDLE, TestStepStatus.IDLE)
                )
            }
            val result: AppResult<RouterInfo> = try {
                testRouterConnectionUseCase(config)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.Error(ApiError.mapThrowable(e))
            }
            if (result is AppResult.Success) lastTestedConfig = config
            // مهما كانت النتيجة ينتهي التحميل؛ الفحص لا يمنع الحفظ لاحقاً
            _uiState.update { applyTestResult(it.copy(isTesting = false), result) }
        }
    }

    /**
     * يحفظ الإعداد (الحقول الحالية) ثم يختبر الاتصال به عبر المدير المركزي.
     * لا يُشترط نجاح فحص سابق: الراوتر قد يكون مطفأً الآن ويريد المستخدم حفظ بياناته. ونجاح الحفظ لا يُعلَن اتصالاً.
     */
    fun save(config: RouterConfig) {
        if (_uiState.value.isSaving || _uiState.value.isTesting) return
        if (RouterSaveGuard.needsConfirmation(lastTestedConfig, config)) {
            // إعداد لم يُختبر أو تغيّر بعد الاختبار: حوار تأكيد، ولا حفظ قبل الموافقة
            _uiState.update { it.copy(pendingUntestedConfig = config) }
            return
        }
        performSave(config)
    }

    /** «حفظ على أي حال»: يستمر السلوك الحالي (حفظ ثم اتصال). */
    fun confirmSaveAnyway() {
        val pending = _uiState.value.pendingUntestedConfig ?: return
        _uiState.update { it.copy(pendingUntestedConfig = null) }
        performSave(pending)
    }

    /** «إلغاء والعودة للاختبار»: لا يُحفظ شيء. */
    fun cancelUntestedSave() {
        _uiState.update { it.copy(pendingUntestedConfig = null) }
    }

    private fun performSave(config: RouterConfig) {
        if (_uiState.value.isSaving || _uiState.value.isTesting) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSaving = true,
                    savedButNotConnected = false,
                    testResult = null,
                    testErrorRes = null,
                    testSteps = buildSteps(TestStepStatus.LOADING, TestStepStatus.IDLE, TestStepStatus.IDLE)
                )
            }
            val outcome: RouterSaveOutcome = try {
                saveRouterConfigUseCase(
                    config.copy(autoSyncIntervalSeconds = _uiState.value.savedConfig.autoSyncIntervalSeconds)
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                RouterSaveOutcome.SaveFailed(ApiError.mapThrowable(e))
            }
            when (outcome) {
                is RouterSaveOutcome.SaveFailed -> {
                    _uiState.update { it.copy(isSaving = false, testSteps = emptyList()) }
                    _events.send(RouterSetupEvent.SaveFailed(saveFailureRes(outcome.error)))
                }
                is RouterSaveOutcome.SavedConnected -> {
                    lastTestedConfig = config // اتصال ناجح بهذه الإعدادات = اختبار ناجح
                    _uiState.update {
                        applyTestResult(it.copy(isSaving = false), AppResult.Success(outcome.info))
                    }
                    _events.send(RouterSetupEvent.Saved)
                }
                is RouterSaveOutcome.SavedNotConnected -> {
                    _uiState.update {
                        applyTestResult(it.copy(isSaving = false), AppResult.Error(outcome.error))
                            .copy(savedButNotConnected = true)
                    }
                    _events.send(RouterSetupEvent.SavedNotConnected(RouterErrorTexts.reasonRes(outcome.error.code)))
                }
            }
        }
    }

    /** إعادة محاولة الاتصال بالإعداد المحفوظ للتو (بعد SavedNotConnected). */
    fun retrySavedConnection(config: RouterConfig) = performSave(config)

    /** إعادة محاولة الاتصال بالإعداد المحفوظ (من تنبيه فشل الإقلاع في S2). */
    fun retryConnection() = retryRouterConnection()

    /** متابعة دون اتصال بعد فشل الاتصال بالإعداد المحفوظ. */
    fun skipConnection() {
        skipRouterConnection()
        viewModelScope.launch { _events.send(RouterSetupEvent.SkippedConnection) }
    }

    private fun applyTestResult(state: RouterUiState, result: AppResult<RouterInfo>): RouterUiState = when (result) {
        is AppResult.Success -> state.copy(
            testResult = result,
            testErrorRes = null,
            testHintRes = null,
            testSteps = buildSteps(TestStepStatus.SUCCESS, TestStepStatus.SUCCESS, TestStepStatus.SUCCESS)
        )
        is AppResult.Error -> state.copy(
            testResult = result,
            testErrorRes = RouterErrorTexts.reasonRes(result.error.code),
            testHintRes = RouterErrorTexts.hintRes(result.error.code),
            testSteps = stepsForFailure(result.error)
        )
        AppResult.Loading -> state
    }

    @StringRes
    private fun saveFailureRes(error: ApiError): Int =
        ErrorTranslator.resIdOrNull(error.code)
            ?.takeIf { error.code != ApiError.CODE_UNKNOWN && error.code != ApiError.CODE_UNEXPECTED_CLIENT }
            ?: R.string.router_save_failed

    private fun buildSteps(s1: TestStepStatus, s2: TestStepStatus, s3: TestStepStatus) = listOf(
        RouterTestStep(R.string.router_step_reachability, s1),
        RouterTestStep(R.string.router_step_auth, s2),
        RouterTestStep(R.string.router_step_version, s3)
    )

    /** الفشل يُحدَّد بمرحلته: الوصول ← المصادقة/خدمة REST ← الإصدار. */
    private fun stepsForFailure(error: ApiError): List<RouterTestStep> {
        val st = RouterTestFailureRules.statuses(error.code)
        return buildSteps(st[0], st[1], st[2])
    }
}

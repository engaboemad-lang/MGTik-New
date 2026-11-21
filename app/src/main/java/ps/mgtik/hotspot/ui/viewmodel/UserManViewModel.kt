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
import ps.mgtik.hotspot.core.network.RouterConfig
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.data.userman.UmAction
import ps.mgtik.hotspot.data.userman.UmProvisionStep
import ps.mgtik.hotspot.data.userman.UmStatus
import ps.mgtik.hotspot.data.userman.UmStep
import ps.mgtik.hotspot.data.userman.UserManPlanner
import ps.mgtik.hotspot.data.userman.UserManSource
import ps.mgtik.hotspot.domain.model.RouterStatus
import ps.mgtik.hotspot.domain.model.UserManConfig
import ps.mgtik.hotspot.domain.repository.IRouterStateManager
import ps.mgtik.hotspot.domain.repository.ISettingsRepository
import ps.mgtik.hotspot.domain.usecase.GetSessionStateUseCase
import ps.mgtik.hotspot.domain.usecase.UserManConfigValidator
import ps.mgtik.hotspot.domain.usecase.UserManField
import ps.mgtik.hotspot.domain.usecase.UserManFieldError
import ps.mgtik.hotspot.ui.util.RouterErrorTexts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class UserManUiState(
    /** الإعداد المحفوظ كما وصل من السحابة (الشاشة تبدأ مسودتها منه). */
    val saved: UserManConfig = UserManConfig(),
    val configLoaded: Boolean = false,
    val isSaving: Boolean = false,
    val isChecking: Boolean = false,
    val isProvisioning: Boolean = false,
    /** نسخ احتياطي/تحسين/فحص أو تنظيف بقايا: عملية صيانة جارية. */
    val isMaintaining: Boolean = false,
    val status: UmStatus? = null,
    val statusCheckedAtMillis: Long = 0L,
    /** فشل آخر فحص حالة (نص مترجم)؛ يُصفَّر عند نجاح فحص لاحق. */
    @StringRes val statusErrorRes: Int? = null,
    val fieldErrors: Map<UserManField, UserManFieldError> = emptyMap(),
    /** نتيجة آخر تهيئة، خطوة بخطوة. */
    val provisionSteps: List<UmProvisionStep> = emptyList(),
    /** null = لم يُفحص بعد. */
    val orphans: List<String>? = null,
    val lastCleanedCount: Int? = null,
    val lastBackupName: String? = null,
    /** true = آخر تحسين نُفّذ، false = غير مدعوم في هذا الجيل، null = لم يُجرَّ. */
    val lastOptimizeSupported: Boolean? = null,
    @StringRes val successMessageResId: Int? = null,
    @StringRes val errorMessageResId: Int? = null,
    /** null = لم تُفحص القراءات بعد. */
    val readChecks: List<ps.mgtik.hotspot.data.userman.UmReadCheck>? = null,
    val isCheckingReads: Boolean = false
) {
    val isBusy: Boolean get() = isSaving || isChecking || isProvisioning || isMaintaining || isCheckingReads
}

/**
 * UserManViewModel - شاشة إعداد واتصال User Manager وبطاقة صيانته في الإعدادات.
 *
 * - الإعداد (جيل/عميل v6/سجل RADIUS) يُحفظ في السحابة ويُعتمد في [UserManSource] فوراً.
 * - كل عملية على الراوتر تمرّ بالمدير المركزي للاتصال (لا اتصال مستقل) فتُرفض فوراً إن كان الراوتر غير متاح.
 * - التهيئة تكتب على الراوتر (تفعيل/NAS/RADIUS/use-radius) ولا تمس المستخدمين ولا الباقات.
 */
@HiltViewModel
class UserManViewModel @Inject constructor(
    private val userMan: UserManSource,
    private val settingsRepository: ISettingsRepository,
    private val routerState: IRouterStateManager,
    private val getSessionStateUseCase: GetSessionStateUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(UserManUiState())
    val uiState: StateFlow<UserManUiState> = _uiState.asStateFlow()

    val routerStatus: StateFlow<RouterStatus> = routerState.status

    init {
        getSessionStateUseCase.gate(onSignedOut = { userMan.invalidate(); _uiState.update { UserManUiState() } }) { settingsRepository.getUserManConfig() }
            .onEach { cfg ->
                userMan.applyConfig(cfg)
                _uiState.update { it.copy(saved = cfg, configLoaded = true) }
            }
            .catch { }
            .launchIn(viewModelScope)
    }

    /** الراوتر المحفوظ؛ null = لم يُهيَّأ (تُعرض رسالة ولا يُجرى اتصال). */
    private fun routerConfigOrNull(): RouterConfig? =
        routerState.status.value.config.takeIf { it.host.isNotBlank() }

    // ───────────── الفحص ─────────────

    /** يقرأ حالة User Manager من الراوتر. [redetect] يعيد قراءة إصدار RouterOS ويعيد اختيار اللهجة. */
    fun refresh(redetect: Boolean = false) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch { checkInternal(redetect) }
    }

    private suspend fun checkInternal(redetect: Boolean = false) {
        val router = routerConfigOrNull() ?: run {
            _uiState.update { it.copy(statusErrorRes = R.string.settings_router_not_configured) }
            return
        }
        _uiState.update { it.copy(isChecking = true, statusErrorRes = null) }
        val res = userMan.status(router, refreshVersion = redetect)
        _uiState.update {
            when (res) {
                is AppResult.Success -> it.copy(isChecking = false, status = res.data, statusCheckedAtMillis = System.currentTimeMillis(), statusErrorRes = null)
                is AppResult.Error -> it.copy(isChecking = false, statusErrorRes = RouterErrorTexts.reasonRes(res.error.code))
                is AppResult.Loading -> it.copy(isChecking = false)
            }
        }
    }

    /** فحص كل قراءة على حدة (باقات/حدود/مستخدمون/جلسات) لتحديد أيها فشل وسببه. */
    fun checkReads() {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            val router = routerConfigOrNull() ?: run {
                _uiState.update { it.copy(statusErrorRes = R.string.settings_router_not_configured) }
                return@launch
            }
            _uiState.update { it.copy(isCheckingReads = true) }
            val checks = userMan.readChecks(router)
            _uiState.update { it.copy(isCheckingReads = false, readChecks = checks) }
        }
    }

    // ───────────── الإعداد ─────────────

    fun clearFieldError(field: UserManField) { _uiState.update { it.copy(fieldErrors = it.fieldErrors - field) } }

    fun save(draft: UserManConfig) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch { saveInternal(draft, requireSecret = false) }
    }

    private suspend fun saveInternal(draft: UserManConfig, requireSecret: Boolean): Boolean {
        val cfg = UserManConfigValidator.normalize(draft)
        val errors = UserManConfigValidator.validate(cfg, requireSecret)
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(fieldErrors = errors) }
            return false
        }
        _uiState.update { it.copy(isSaving = true, fieldErrors = emptyMap(), successMessageResId = null, errorMessageResId = null) }
        val res = try {
            settingsRepository.updateUserManConfig(cfg)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Error(ApiError.mapThrowable(e))
        }
        return when (res) {
            is AppResult.Success -> {
                userMan.applyConfig(cfg)
                _uiState.update { it.copy(isSaving = false, saved = cfg, successMessageResId = R.string.um_saved) }
                true
            }
            is AppResult.Error -> {
                _uiState.update { it.copy(isSaving = false, errorMessageResId = ps.mgtik.hotspot.ui.util.ErrorTranslator.resIdOf(res.error)) }
                false
            }
            is AppResult.Loading -> { _uiState.update { it.copy(isSaving = false) }; false }
        }
    }

    // ───────────── التهيئة ─────────────

    /** الخطوات التي ستُنفَّذ بحسب آخر فحص (تُعرض في حوار التأكيد). فارغة = لا حاجة لتهيئة. */
    fun plannedSteps(): Set<UmStep> = _uiState.value.status?.let { UserManPlanner.stepsFor(it.checks) }.orEmpty()

    /** يحفظ [draft] (بسرّ إلزامي) ثم يهيّئ الراوتر به ثم يعيد الفحص. */
    fun provision(draft: UserManConfig) {
        if (_uiState.value.isBusy) return
        val router = routerConfigOrNull() ?: run {
            _uiState.update { it.copy(errorMessageResId = R.string.settings_router_not_configured) }
            return
        }
        viewModelScope.launch {
            if (!saveInternal(draft, requireSecret = true)) return@launch
            val cfg = UserManConfigValidator.normalize(draft)
            _uiState.update { it.copy(isProvisioning = true, provisionSteps = emptyList(), successMessageResId = null, errorMessageResId = null) }
            val res = userMan.provision(router, cfg)
            when (res) {
                is AppResult.Success -> {
                    val anyFailed = res.data.any { it.action == UmAction.FAILED }
                    _uiState.update {
                        it.copy(
                            isProvisioning = false, provisionSteps = res.data,
                            successMessageResId = if (anyFailed) null else R.string.um_provision_done,
                            errorMessageResId = if (anyFailed) R.string.um_provision_partial else null
                        )
                    }
                    checkInternal()
                }
                is AppResult.Error -> _uiState.update { it.copy(isProvisioning = false, errorMessageResId = RouterErrorTexts.reasonRes(res.error.code)) }
                is AppResult.Loading -> _uiState.update { it.copy(isProvisioning = false) }
            }
        }
    }

    // ───────────── الصيانة ─────────────

    /** نسخة احتياطية من قاعدة User Manager على الراوتر نفسه (ملف .umb في Files). */
    fun backup() = maintain { router ->
        val name = "mgtik-um-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        when (val res = userMan.backupDatabase(router, name)) {
            is AppResult.Success -> _uiState.update { it.copy(isMaintaining = false, lastBackupName = name, successMessageResId = R.string.um_backup_done) }
            is AppResult.Error -> fail(res.error)
            is AppResult.Loading -> _uiState.update { it.copy(isMaintaining = false) }
        }
    }

    fun optimize() = maintain { router ->
        when (val res = userMan.optimizeDatabase(router)) {
            is AppResult.Success -> _uiState.update {
                it.copy(isMaintaining = false, lastOptimizeSupported = res.data,
                    successMessageResId = if (res.data) R.string.um_optimize_done else R.string.um_optimize_unsupported)
            }
            is AppResult.Error -> fail(res.error)
            is AppResult.Loading -> _uiState.update { it.copy(isMaintaining = false) }
        }
    }

    fun scanOrphans() = maintain { router ->
        when (val res = userMan.findOrphanLimitations(router)) {
            is AppResult.Success -> _uiState.update { it.copy(isMaintaining = false, orphans = res.data, lastCleanedCount = null) }
            is AppResult.Error -> fail(res.error)
            is AppResult.Loading -> _uiState.update { it.copy(isMaintaining = false) }
        }
    }

    /** يحذف البقايا التي عُرضت للمستخدم فقط (القائمة المفحوصة)، لا ما قد يظهر بعدها. */
    fun cleanOrphans() {
        val names = _uiState.value.orphans.orEmpty()
        if (names.isEmpty()) return
        maintain { router ->
            when (val res = userMan.removeOrphanLimitations(router, names)) {
                is AppResult.Success -> _uiState.update { it.copy(isMaintaining = false, orphans = emptyList(), lastCleanedCount = res.data, successMessageResId = R.string.um_orphans_cleaned) }
                is AppResult.Error -> fail(res.error)
                is AppResult.Loading -> _uiState.update { it.copy(isMaintaining = false) }
            }
        }
    }

    private fun maintain(block: suspend (RouterConfig) -> Unit) {
        if (_uiState.value.isBusy) return
        val router = routerConfigOrNull() ?: run {
            _uiState.update { it.copy(errorMessageResId = R.string.settings_router_not_configured) }
            return
        }
        _uiState.update { it.copy(isMaintaining = true, successMessageResId = null, errorMessageResId = null) }
        viewModelScope.launch {
            try {
                block(router)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(ApiError.mapThrowable(e))
            }
        }
    }

    private fun fail(error: ApiError) {
        _uiState.update { it.copy(isMaintaining = false, errorMessageResId = RouterErrorTexts.reasonRes(error.code)) }
    }

    fun clearMessages() { _uiState.update { it.copy(successMessageResId = null, errorMessageResId = null) } }
}

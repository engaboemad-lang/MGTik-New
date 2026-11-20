package ps.mgtik.hotspot.ui.viewmodel

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.data.local.db.MgtikDatabase
import ps.mgtik.hotspot.data.local.prefs.AppLanguage
import ps.mgtik.hotspot.data.local.prefs.AppPreferences
import ps.mgtik.hotspot.data.local.prefs.ThemeMode
import ps.mgtik.hotspot.domain.model.AuditLog
import ps.mgtik.hotspot.domain.model.ConfigLoadState
import ps.mgtik.hotspot.domain.model.LogSeverity
import ps.mgtik.hotspot.domain.model.NetworkCompanyInfo
import ps.mgtik.hotspot.domain.model.OutputSettings
import ps.mgtik.hotspot.domain.model.RouterConnectionState
import ps.mgtik.hotspot.domain.model.RouterStatus
import ps.mgtik.hotspot.domain.repository.IAuditRepository
import ps.mgtik.hotspot.domain.repository.IOfflineCommandRepository
import ps.mgtik.hotspot.domain.repository.IRouterStateManager
import ps.mgtik.hotspot.domain.repository.ISettingsRepository
import ps.mgtik.hotspot.domain.usecase.CompanyInfoValidator
import ps.mgtik.hotspot.domain.usecase.GetSessionStateUseCase
import ps.mgtik.hotspot.domain.usecase.GetSettingsUseCase
import ps.mgtik.hotspot.domain.usecase.UpdateCompanyInfoUseCase
import ps.mgtik.hotspot.ui.util.ErrorTranslator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** لقطة تشخيص من بيانات حقيقية فقط (لا قيم افتراضية تُعرض كنتائج). لا تحوي كلمة مرور الراوتر أبداً. */
data class DiagnosticsInfo(
    val appVersionName: String = "",
    val appVersionCode: Long = 0,
    val dbVersion: Int = 0,
    val isDebugBuild: Boolean = false,
    val androidApi: Int = 0,
    val device: String = "",
    val routerState: RouterConnectionState = RouterConnectionState.DISCONNECTED,
    val routerHost: String = "",
    val routerErrorCode: String? = null,
    val routerLastCheckMillis: Long = 0L,
    val configLoad: ConfigLoadState = ConfigLoadState.LOADING,
    val pendingCommands: Int = 0,
    val recentErrors: List<AuditLog> = emptyList(),
    val tempFilesBytes: Long = 0L
)

data class SettingsUiState(
    val companyInfo: NetworkCompanyInfo = NetworkCompanyInfo(),
    /** حقل ← رسالة خطأ (التحقق من بيانات الشركة). */
    val companyErrors: Map<String, Int> = emptyMap(),
    val isSaving: Boolean = false,
    val diagnostics: DiagnosticsInfo = DiagnosticsInfo(),
    val isDiagnosticsLoading: Boolean = false,
    /** نتيجة آخر اختبار حقيقي لوصول الخادم: زمن الاستجابة بالمللي ثانية، أو رمز الخطأ. */
    val cloudPingMs: Long? = null,
    val cloudPingError: String? = null,
    val isTestingCloud: Boolean = false,
    val isTestingRouter: Boolean = false,
    /** نص نتيجة آخر اختبار راوتر (من النتيجة الفعلية). */
    val routerTestOk: Boolean? = null,
    val routerTestDetail: String? = null,
    val isCleaning: Boolean = false,
    @StringRes val successMessageResId: Int? = null,
    @StringRes val errorMessageResId: Int? = null
)

/**
 * SettingsViewModel - واجهة إدارة وتكوين: بيانات الشركة (Firestore)، تفضيلات الجهاز (AppPreferences)،
 * وتشخيص حقيقي. لا يملك أي منطق اتصال بالراوتر: الحالة والاختبار عبر IRouterStateManager المركزي.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val getSettingsUseCase: GetSettingsUseCase,
    private val updateCompanyInfoUseCase: UpdateCompanyInfoUseCase,
    private val getSessionStateUseCase: GetSessionStateUseCase,
    private val appPreferences: AppPreferences,
    private val routerState: IRouterStateManager,
    private val settingsRepository: ISettingsRepository,
    private val offlineRepo: IOfflineCommandRepository,
    private val auditRepo: IAuditRepository,
    private val database: MgtikDatabase
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // تفضيلات الجهاز: مصدرها AppPreferences (تُقرأ منها الشاشات والوظائف نفسها)
    val themeMode: StateFlow<ThemeMode> = appPreferences.themeMode
    val language: StateFlow<AppLanguage> = appPreferences.language
    val outputSettings: StateFlow<OutputSettings> = appPreferences.outputSettings
    val notifyBilling: StateFlow<Boolean> = appPreferences.notifyBilling
    val notifyGeneration: StateFlow<Boolean> = appPreferences.notifyGeneration
    val connectionNotification: StateFlow<Boolean> = appPreferences.connectionNotification

    /** الحالة المركزية للراوتر (مصدر وحيد؛ لا فحص مستقل هنا). */
    val routerStatus: StateFlow<RouterStatus> = routerState.status

    init {
        getSessionStateUseCase.gate(onSignedOut = { _uiState.update { SettingsUiState() } }) { getSettingsUseCase.getCompanyInfo() }
            .onEach { info -> _uiState.update { it.copy(companyInfo = info) } }
            .catch { }
            .launchIn(viewModelScope)
    }

    fun setThemeMode(mode: ThemeMode) = appPreferences.setThemeMode(mode)
    fun setLanguage(language: AppLanguage) = appPreferences.setLanguage(language)
    fun setNotifyBilling(enabled: Boolean) = appPreferences.setNotifyBilling(enabled)
    fun setNotifyGeneration(enabled: Boolean) = appPreferences.setNotifyGeneration(enabled)
    fun setConnectionNotification(enabled: Boolean) = appPreferences.setConnectionNotification(enabled)

    fun saveOutputSettings(settings: OutputSettings) {
        val res = appPreferences.saveOutputSettings(settings)
        _uiState.update {
            if (res is AppResult.Success) it.copy(successMessageResId = R.string.settings_success_save)
            else it.copy(errorMessageResId = R.string.settings_error_invalid_values)
        }
    }

    /** استعادة الافتراضيات المحلية فقط (مظهر/لغة/إشعارات/طباعة)؛ لا تمس بيانات الشركة ولا الراوتر. */
    fun resetLocalDefaults() {
        appPreferences.resetToDefaults()
        _uiState.update { it.copy(successMessageResId = R.string.settings_success_reset) }
    }

    /** يحفظ بيانات الشركة كاملة (الحقول غير المعدلة تُرسل بقيمها الحالية فلا يُصفَّر شيء). */
    fun saveCompanyInfo(updated: NetworkCompanyInfo) {
        if (_uiState.value.isSaving) return
        val info = updated.trim()
        val errors = mutableMapOf<String, Int>()
        if (info.companyName.isBlank()) errors["company"] = R.string.settings_error_company_required
        if (!CompanyInfoValidator.isEmailValid(info.email)) errors[CompanyInfoValidator.FIELD_EMAIL] = R.string.settings_error_email
        if (!CompanyInfoValidator.isUrlValid(info.website)) errors[CompanyInfoValidator.FIELD_WEBSITE] = R.string.settings_error_url
        if (!CompanyInfoValidator.isUrlValid(info.logoUrl)) errors[CompanyInfoValidator.FIELD_LOGO] = R.string.settings_error_url
        if (!CompanyInfoValidator.isUrlValid(info.loginUrl)) errors[CompanyInfoValidator.FIELD_LOGIN_URL] = R.string.settings_error_url
        if (!CompanyInfoValidator.isCurrencyValid(info.currencySymbol)) errors[CompanyInfoValidator.FIELD_CURRENCY] = R.string.settings_error_currency
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(companyErrors = errors) }
            return
        }
        _uiState.update { it.copy(isSaving = true, companyErrors = emptyMap(), successMessageResId = null, errorMessageResId = null) }
        viewModelScope.launch {
            // شبكة أمان فوق مهل طبقة البيانات: مهما حدث لا تبقى isSaving = true (وإلا حجب كل حفظ لاحق)
            val res = try {
                withTimeoutOrNull(SAVE_COMPANY_TIMEOUT_MS) { updateCompanyInfoUseCase(info) }
                    ?: AppResult.Error(ApiError.timeout())
            } catch (e: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isSaving = false) }
                throw e
            } catch (e: Exception) {
                AppResult.Error(ApiError.mapThrowable(e))
            }
            _uiState.update {
                when (res) {
                    is AppResult.Success -> it.copy(isSaving = false, companyInfo = info, successMessageResId = R.string.settings_success_save)
                    is AppResult.Error -> it.copy(isSaving = false, errorMessageResId = ErrorTranslator.resIdOf(res.error))
                    is AppResult.Loading -> it.copy(isSaving = false)
                }
            }
        }
    }

    fun clearCompanyError(field: String) { _uiState.update { it.copy(companyErrors = it.companyErrors - field) } }

    // ── التشخيص والصيانة (بيانات حقيقية) ─────────────────────────────────────

    fun refreshDiagnostics() {
        if (_uiState.value.isDiagnosticsLoading) return
        _uiState.update { it.copy(isDiagnosticsLoading = true) }
        viewModelScope.launch {
            val info = try {
                collectDiagnostics()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _uiState.update {
                if (info != null) it.copy(isDiagnosticsLoading = false, diagnostics = info)
                else it.copy(isDiagnosticsLoading = false, errorMessageResId = R.string.ERR_UNKNOWN)
            }
        }
    }

    private suspend fun collectDiagnostics(): DiagnosticsInfo = withContext(Dispatchers.IO) {
        val pkg = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val st = routerState.status.value
        val dbVersion = database.openHelper.readableDatabase.version
        val errors = auditRepo.getLogs(LogSeverity.ERROR).first().sortedByDescending { it.timestamp }.take(RECENT_ERRORS)
        DiagnosticsInfo(
            appVersionName = pkg.versionName.orEmpty(),
            appVersionCode = if (android.os.Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else @Suppress("DEPRECATION") pkg.versionCode.toLong(),
            dbVersion = dbVersion,
            isDebugBuild = (appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            androidApi = android.os.Build.VERSION.SDK_INT,
            device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            routerState = st.state,
            routerHost = st.config.host,
            routerErrorCode = st.errorCode,
            routerLastCheckMillis = st.lastCheckedAtMillis,
            configLoad = st.configLoad,
            pendingCommands = offlineRepo.getPendingCommands().first().size,
            recentErrors = errors,
            tempFilesBytes = tempDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }
        )
    }

    /** اختبار حقيقي لوصول التطبيق إلى خدمات السحابة (منفصل تماماً عن اتصال الراوتر). */
    fun testCloud() {
        if (_uiState.value.isTestingCloud) return
        _uiState.update { it.copy(isTestingCloud = true, cloudPingMs = null, cloudPingError = null) }
        viewModelScope.launch {
            val res = settingsRepository.pingCloud()
            _uiState.update {
                when (res) {
                    is AppResult.Success -> it.copy(isTestingCloud = false, cloudPingMs = res.data)
                    is AppResult.Error -> it.copy(isTestingCloud = false, cloudPingError = res.error.code)
                    is AppResult.Loading -> it.copy(isTestingCloud = false)
                }
            }
        }
    }

    /** اختبار اتصال الراوتر بالإعداد المحفوظ عبر المنظومة المركزية (لا اتصال مستقل). */
    fun testRouter() {
        if (_uiState.value.isTestingRouter) return
        val config = routerState.status.value.config
        if (config.host.isBlank()) {
            _uiState.update { it.copy(errorMessageResId = R.string.settings_router_not_configured) }
            return
        }
        _uiState.update { it.copy(isTestingRouter = true, routerTestOk = null, routerTestDetail = null) }
        viewModelScope.launch {
            val res = try {
                routerState.testConfig(config)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.Error(ApiError.mapThrowable(e))
            }
            _uiState.update {
                when (res) {
                    is AppResult.Success -> it.copy(isTestingRouter = false, routerTestOk = true, routerTestDetail = res.data.name)
                    is AppResult.Error -> it.copy(isTestingRouter = false, routerTestOk = false, routerTestDetail = res.error.code)
                    is AppResult.Loading -> it.copy(isTestingRouter = false)
                }
            }
        }
    }

    /** يحذف ملفات التصدير المؤقتة فقط (cache/exports): قابلة لإعادة الإنشاء ولا تمس أي بيانات تشغيلية. */
    fun clearTempFiles() {
        if (_uiState.value.isCleaning) return
        _uiState.update { it.copy(isCleaning = true) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { tempDir().deleteRecursively() }.getOrDefault(false) }
            _uiState.update { it.copy(isCleaning = false, successMessageResId = if (ok) R.string.settings_success_cleaned else null, errorMessageResId = if (ok) null else R.string.ERR_UNKNOWN) }
            if (ok) refreshDiagnostics()
        }
    }

    /** يحذف سجل التدقيق المحلي الأقدم من [LOG_RETENTION_DAYS] يوماً (سجل محلي فقط؛ لا بيانات مالية). */
    fun clearOldLogs() {
        if (_uiState.value.isCleaning) return
        _uiState.update { it.copy(isCleaning = true) }
        viewModelScope.launch {
            val res = auditRepo.clearOldLogs(LOG_RETENTION_DAYS)
            _uiState.update {
                if (res is AppResult.Success) it.copy(isCleaning = false, successMessageResId = R.string.settings_success_cleaned)
                else it.copy(isCleaning = false, errorMessageResId = R.string.ERR_UNKNOWN)
            }
            refreshDiagnostics()
        }
    }

    /** نص تقرير تشخيصي من اللقطة الحالية. لا يتضمن كلمة مرور الراوتر ولا أي بيانات اعتماد. */
    fun buildDiagnosticsReport(): String {
        val s = _uiState.value
        val d = s.diagnostics
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return buildString {
            appendLine("MGTik diagnostics — ${fmt.format(Date())}")
            appendLine("App: ${d.appVersionName} (${d.appVersionCode})${if (d.isDebugBuild) " debug" else ""}")
            appendLine("DB version: ${d.dbVersion}")
            appendLine("Android API: ${d.androidApi}, device: ${d.device}")
            appendLine("Cloud config: ${d.configLoad}")
            s.cloudPingMs?.let { appendLine("Cloud ping: $it ms") }
            s.cloudPingError?.let { appendLine("Cloud ping error: $it") }
            appendLine("Router: ${d.routerState} host=${d.routerHost.ifBlank { "-" }} error=${d.routerErrorCode ?: "-"}")
            if (d.routerLastCheckMillis > 0) appendLine("Router last check: ${fmt.format(Date(d.routerLastCheckMillis))}")
            appendLine("Pending queued commands: ${d.pendingCommands}")
            appendLine("Temp files: ${d.tempFilesBytes} bytes")
            appendLine("Recent errors (${d.recentErrors.size}):")
            d.recentErrors.forEach { appendLine("- ${fmt.format(Date(it.timestamp))} ${it.action}: ${it.detail}") }
        }
    }

    private fun tempDir() = File(appContext.cacheDir, "exports")

    fun clearMessages() {
        _uiState.update { it.copy(successMessageResId = null, errorMessageResId = null) }
    }

    private fun NetworkCompanyInfo.trim() = copy(
        companyName = companyName.trim(), networkName = networkName.trim(), logoUrl = logoUrl.trim(),
        address = address.trim(), phone = phone.trim(), whatsapp = whatsapp.trim(), email = email.trim(),
        website = website.trim(), loginUrl = loginUrl.trim(), footerNotes = footerNotes.trim(),
        currencySymbol = currencySymbol.trim()
    )

    private companion object {
        const val RECENT_ERRORS = 20
        const val LOG_RETENTION_DAYS = 30
        /** أطول من مجموع مهل خطوات الحفظ السحابي (3 × 10 ث) بهامش. */
        const val SAVE_COMPANY_TIMEOUT_MS = 40_000L
    }
}

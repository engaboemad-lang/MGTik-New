package ps.mgtik.hotspot.ui.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.FinancialDashboard
import ps.mgtik.hotspot.domain.report.ReportPeriods
import ps.mgtik.hotspot.domain.report.ReportPreset
import ps.mgtik.hotspot.domain.usecase.FetchFinancialDashboardUseCase
import ps.mgtik.hotspot.domain.usecase.GetBillingReportUseCase
import ps.mgtik.hotspot.ui.util.ErrorTranslator
import java.time.LocalDate
import javax.inject.Inject

data class ReportsUiState(
    val financialDashboard: FinancialDashboard = FinancialDashboard(),
    val reportData: Map<String, Any?> = emptyMap(),
    /** نوع التقرير الذي طُلب آخر مرة (null = لم يُطلب تقرير بعد). */
    val reportType: String? = null,
    val selectedDateRange: Pair<String, String> = ReportPeriods.preset(ReportPreset.LAST_30_DAYS, LocalDate.now()),
    /** الفترة الجاهزة المختارة؛ null = نطاق مخصص. */
    val selectedPreset: ReportPreset? = ReportPreset.LAST_30_DAYS,
    /** نتيجة نفس التقرير للفترة السابقة المباشرة (للمقارنة)؛ فارغة إن لم تُطلب أو فشلت. */
    val previousReportData: Map<String, Any?> = emptyMap(),
    val previousDateRange: Pair<String, String>? = null,
    /** تحميل لوحة الملخص المالي. */
    val isLoading: Boolean = false,
    /** توليد تقرير تفصيلي قيد التنفيذ (لا يُخفي الشاشة). */
    val isGenerating: Boolean = false,
    /** فشل توليد آخر تقرير: يُعرض كخطأ مع إعادة محاولة، لا كـ«لا توجد بيانات» (فشل ≠ تقرير فارغ). */
    val reportFailed: Boolean = false,
    @StringRes val errorMessageResId: Int? = null
)

/**
 * ReportsViewModel - مركز التحليلات والتقارير المالية والتشغيلية (§4.19).
 */
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val fetchFinancialDashboardUseCase: FetchFinancialDashboardUseCase,
    private val getBillingReportUseCase: GetBillingReportUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportsUiState())
    val uiState: StateFlow<ReportsUiState> = _uiState.asStateFlow()

    private var reportJob: Job? = null

    /**
     * مراقبة لوحة الملخص أثناء ظهور الشاشة فقط (تُستدعى من LaunchedEffect فتُلغى بمغادرتها). كانت تبدأ في init
     * قبل الدخول فتموت عند أول خطأ صلاحيات ولا تعود أبداً.
     */
    suspend fun trackDashboard() {
        _uiState.update { it.copy(isLoading = true) }
        fetchFinancialDashboardUseCase()
            .catch { e ->
                _uiState.update { it.copy(errorMessageResId = ErrorTranslator.resIdOf(ApiError.mapThrowable(e)), isLoading = false) }
            }
            .collect { dashboard ->
                _uiState.update { it.copy(financialDashboard = dashboard, isLoading = false) }
            }
        _uiState.update { it.copy(isLoading = false) }
    }

    /** طلب تقرير جديد يلغي السابق الجاري (لا نتائج متسابقة ولا طلبات مكررة). */
    fun generateReport(reportType: String) {
        reportJob?.cancel()
        val range = _uiState.value.selectedDateRange
        // المقارنة بالفترة السابقة لملخص المبيعات فقط (بقية الأنواع لقطة راهنة أو لا معنى لمقارنتها)
        val previousRange = if (reportType == REPORT_SALES_SUMMARY) ReportPeriods.previousRange(range.first, range.second) else null
        _uiState.update {
            it.copy(
                isGenerating = true, reportType = reportType, reportData = emptyMap(), reportFailed = false,
                errorMessageResId = null, previousReportData = emptyMap(), previousDateRange = previousRange
            )
        }
        reportJob = viewModelScope.launch {
            val result = runReport(reportType, range)
            // فشل المقارنة لا يُفشل التقرير الأساسي: تُحذف المقارنة فقط
            val previous = previousRange?.let { (runReport(reportType, it) as? AppResult.Success)?.data }.orEmpty()
            _uiState.update {
                when (result) {
                    is AppResult.Success -> it.copy(reportData = result.data, previousReportData = previous, isGenerating = false)
                    is AppResult.Error -> it.copy(errorMessageResId = ErrorTranslator.resIdOf(result.error), reportFailed = true, isGenerating = false)
                    is AppResult.Loading -> it.copy(isGenerating = false)
                }
            }
        }
    }

    /** إعادة توليد آخر تقرير طُلب (بعد فشل). */
    fun retryReport() { _uiState.value.reportType?.let(::generateReport) }

    private suspend fun runReport(reportType: String, range: Pair<String, String>): AppResult<Map<String, Any?>> = try {
        getBillingReportUseCase(reportType = reportType, dateRange = range)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        AppResult.Error(ApiError.mapThrowable(e))
    }

    /** اختيار فترة جاهزة. */
    fun selectPreset(preset: ReportPreset) = applyRange(ReportPeriods.preset(preset, LocalDate.now()), preset)

    /** نطاق مخصص من محدد التاريخ؛ يُهمل إن كان معكوساً أو غير مقروء. */
    fun updateDateRange(start: String, end: String) {
        if (ReportPeriods.previousRange(start, end) == null) return
        applyRange(start to end, null)
    }

    /** تغيير الفترة يعيد توليد التقرير المعروض تلقائياً (لا يبقى تقرير بفترة قديمة بجوار عنوان فترة جديدة). */
    private fun applyRange(range: Pair<String, String>, preset: ReportPreset?) {
        _uiState.update { it.copy(selectedDateRange = range, selectedPreset = preset) }
        _uiState.value.reportType?.let(::generateReport)
    }

    private companion object {
        const val REPORT_SALES_SUMMARY = "sales_summary"
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessageResId = null) }
    }
}

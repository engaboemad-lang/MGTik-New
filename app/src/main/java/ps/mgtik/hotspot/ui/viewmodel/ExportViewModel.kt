package ps.mgtik.hotspot.ui.viewmodel

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.data.export.BatchExporter
import ps.mgtik.hotspot.data.export.ExportSource
import ps.mgtik.hotspot.domain.export.ExportFormat
import ps.mgtik.hotspot.domain.export.LayoutException
import ps.mgtik.hotspot.domain.model.OutputSettings
import ps.mgtik.hotspot.domain.model.PageSize
import ps.mgtik.hotspot.domain.model.PrintOrientation
import ps.mgtik.hotspot.domain.model.RuntimeTemplate
import ps.mgtik.hotspot.domain.model.VoucherBatch
import ps.mgtik.hotspot.domain.repository.IGenerationRepository
import ps.mgtik.hotspot.domain.repository.IProfileRepository
import ps.mgtik.hotspot.domain.repository.ITemplateRepository
import java.io.File
import javax.inject.Inject

enum class ExportAction { SHARE, SAVE }

sealed interface ExportEvent {
    data class FileReady(val file: File, val format: ExportFormat, val action: ExportAction) : ExportEvent
}

data class ExportUiState(
    val isLoading: Boolean = true,
    val batch: VoucherBatch? = null,
    val voucherCount: Int = 0,
    val templates: List<RuntimeTemplate> = emptyList(),
    /** null = القالب المضمَّن. */
    val selectedTemplateId: String? = null,
    val settings: OutputSettings = ps.mgtik.hotspot.data.local.prefs.AppPreferences.DEFAULT_OUTPUT,
    val format: ExportFormat = ExportFormat.PDF,
    val pageCount: Int = 0,
    val previewPage: Int = 0,
    val preview: Bitmap? = null,
    @StringRes val loadErrorRes: Int? = null,
    @StringRes val layoutErrorRes: Int? = null,
    val isExporting: Boolean = false,
    val progress: Int = 0,
    @StringRes val messageRes: Int? = null
)

/**
 * ExportViewModel - تصدير دفعة كاملة (PDF/TXT). المعاينة تُرسم بمحرك PDF نفسه.
 * نظام قوالب واحد: TemplateDefinition (المزامَن) أو المضمَّن عند غيابه. لا فلترة ولا علامة «طُبع».
 */
@HiltViewModel
class ExportViewModel @Inject constructor(
    private val exporter: BatchExporter,
    private val generationRepository: IGenerationRepository,
    private val templateRepository: ITemplateRepository,
    private val profileRepository: IProfileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExportUiState())
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    init {
        // إعدادات الطباعة تُعدَّل أيضاً من شاشة الإعدادات: أي تغيير ينعكس فوراً على المعاينة والتصدير
        viewModelScope.launch {
            templateRepository.getOutputSettings().collect { s ->
                if (s != _uiState.value.settings) {
                    _uiState.update { it.copy(settings = s, previewPage = 0) }
                    if (source != null) refreshPreview()
                }
            }
        }
    }

    private val _events = Channel<ExportEvent>(Channel.BUFFERED)
    val events: Flow<ExportEvent> = _events.receiveAsFlow()

    private var loadedBatchId: String? = null
    private var source: ExportSource? = null
    private var profileDisplayAr: String = ""
    private var previewJob: Job? = null
    private var exportJob: Job? = null

    fun load(batchId: String) {
        if (loadedBatchId == batchId) return
        loadedBatchId = batchId
        viewModelScope.launch {
            _uiState.update { ExportUiState(isLoading = true) }
            try {
                val batch = withTimeoutOrNull(3_000) { generationRepository.observeBatch(batchId).first { it != null } }
                val templates = templateRepository.getLocalTemplates().first()
                val defaultId = templateRepository.getDefaultTemplateId().first()?.takeIf { id -> templates.any { it.templateId == id } }
                val settings = templateRepository.getOutputSettings().first()
                profileDisplayAr = batch?.let { b -> profileRepository.getProfiles(true, null).first().firstOrNull { it.id == b.cloudProfileId }?.displayNameAr }.orEmpty()

                _uiState.update { it.copy(batch = batch, templates = templates, selectedTemplateId = defaultId, settings = settings) }
                reloadSource()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // فشل التحميل لا يُسقط الشاشة، ويُسمح بإعادة المحاولة عند فتحها مجدداً (كان المعرّف يبقى «محمّلاً»)
                loadedBatchId = null
                _uiState.update { it.copy(isLoading = false, loadErrorRes = R.string.export_error) }
            }
        }
    }

    private suspend fun reloadSource() {
        val batchId = loadedBatchId ?: return
        _uiState.update { it.copy(isLoading = true, loadErrorRes = null) }
        val template = _uiState.value.selectedTemplateId?.let { id -> _uiState.value.templates.firstOrNull { it.templateId == id } }
        when (val res = exporter.loadSource(batchId, template, profileDisplayAr)) {
            is AppResult.Success -> {
                source = res.data
                _uiState.update { it.copy(isLoading = false, voucherCount = res.data.rows.size) }
                refreshPreview()
            }
            is AppResult.Error -> {
                source = null
                _uiState.update { it.copy(isLoading = false, preview = null, loadErrorRes = errorRes(res.error.code)) }
            }
            else -> _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun refreshPreview() {
        val src = source ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val st = _uiState.value
            try {
                val (plan, bitmap) = withContext(Dispatchers.Default) {
                    val plan = exporter.planFor(src, st.settings)
                    val page = st.previewPage.coerceIn(0, (plan.pageCount - 1).coerceAtLeast(0))
                    plan to exporter.renderPreview(src, st.settings, page)
                }
                _uiState.update {
                    it.copy(
                        pageCount = plan.pageCount,
                        previewPage = it.previewPage.coerceIn(0, (plan.pageCount - 1).coerceAtLeast(0)),
                        preview = bitmap,
                        layoutErrorRes = null
                    )
                }
            } catch (e: LayoutException) {
                _uiState.update { it.copy(preview = null, pageCount = 0, layoutErrorRes = errorRes(e.code)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(preview = null, pageCount = 0, layoutErrorRes = R.string.export_error) }
            }
        }
    }

    fun setFormat(format: ExportFormat) { _uiState.update { it.copy(format = format) } }

    fun setTemplate(templateId: String?) {
        if (_uiState.value.selectedTemplateId == templateId) return
        _uiState.update { it.copy(selectedTemplateId = templateId, previewPage = 0) }
        viewModelScope.launch { reloadSource() }
    }

    fun updateSettings(transform: (OutputSettings) -> OutputSettings) {
        val next = transform(_uiState.value.settings)
        _uiState.update { it.copy(settings = next, previewPage = 0) }
        viewModelScope.launch { runCatching { templateRepository.saveOutputSettings(next) } }
        refreshPreview()
    }

    fun setPage(page: PageSize) = updateSettings { it.copy(page = page) }
    fun setOrientation(o: PrintOrientation) = updateSettings { it.copy(orientation = o) }
    fun setColumns(columns: Int) = updateSettings { it.copy(columns = columns.coerceIn(1, ps.mgtik.hotspot.domain.export.LayoutPlanner.MAX_COLUMNS)) }

    fun nextPage() { movePage(+1) }
    fun previousPage() { movePage(-1) }
    private fun movePage(delta: Int) {
        val st = _uiState.value
        val target = (st.previewPage + delta).coerceIn(0, (st.pageCount - 1).coerceAtLeast(0))
        if (target == st.previewPage) return
        _uiState.update { it.copy(previewPage = target) }
        refreshPreview()
    }

    fun export(action: ExportAction, txtHeader: String, txtColumns: String) {
        val src = source ?: return
        if (_uiState.value.isExporting) return
        val st = _uiState.value
        exportJob = viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, progress = 0, messageRes = null) }
            val res = try {
                exporter.export(src, st.format, st.settings, st.batch?.batchNumber.orEmpty(), txtHeader, txtColumns) { p ->
                    _uiState.update { it.copy(progress = p) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.Error(ps.mgtik.hotspot.core.result.ApiError.mapThrowable(e))
            }
            when (res) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isExporting = false, progress = 100) }
                    _events.send(ExportEvent.FileReady(res.data, st.format, action))
                }
                is AppResult.Error -> _uiState.update { it.copy(isExporting = false, messageRes = errorRes(res.error.code)) }
                else -> _uiState.update { it.copy(isExporting = false) }
            }
        }
    }

    fun onSaved(success: Boolean) { _uiState.update { it.copy(messageRes = if (success) R.string.export_saved else R.string.export_error) } }
    fun consumeMessage() { _uiState.update { it.copy(messageRes = null) } }

    @StringRes
    private fun errorRes(code: String): Int = when (code) {
        BatchExporter.ERR_NO_VOUCHERS -> R.string.export_no_vouchers
        BatchExporter.ERR_TEMPLATE_UNAVAILABLE -> R.string.export_error_template
        "ERR_GEN_BATCH_CANCELLED" -> R.string.export_error_batch_cancelled
        "ERR_GEN_BATCH_NOT_READY" -> R.string.export_error_batch_not_ready
        LayoutException.INVALID_COLUMNS -> R.string.export_error_columns
        LayoutException.INVALID_MARGINS -> R.string.export_error_margins
        LayoutException.CARD_TOO_SMALL -> R.string.export_error_card_small
        LayoutException.CARD_TALLER_THAN_PAGE -> R.string.export_error_card_tall
        else -> R.string.export_error
    }
}

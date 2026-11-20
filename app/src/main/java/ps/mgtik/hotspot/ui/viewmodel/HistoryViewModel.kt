package ps.mgtik.hotspot.ui.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.HistoricalBatch
import ps.mgtik.hotspot.domain.model.HistoryFilter
import ps.mgtik.hotspot.domain.usecase.ArchiveBatchUseCase
import ps.mgtik.hotspot.domain.usecase.ExportHistoryToCsvUseCase
import ps.mgtik.hotspot.domain.usecase.GetHistoricalBatchDetailUseCase
import ps.mgtik.hotspot.domain.usecase.GetHistoricalBatchesUseCase
import ps.mgtik.hotspot.domain.usecase.GetSessionStateUseCase
import ps.mgtik.hotspot.ui.util.ErrorTranslator
import javax.inject.Inject

data class HistoryUiState(
    val batches: List<HistoricalBatch> = emptyList(),
    val selectedBatch: HistoricalBatch? = null,
    val filter: HistoryFilter = HistoryFilter(),
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    @StringRes val successMessageResId: Int? = null,
    @StringRes val errorMessageResId: Int? = null
)

/** حدث لمرة واحدة: نص CSV جاهز لتسليمه للمستخدم (حفظ/مشاركة) من طبقة الواجهة التي تملك Context. */
sealed interface HistoryEvent {
    data class ExportReady(val csv: String) : HistoryEvent
}

/**
 * HistoryViewModel - إدارة سجل الأرشيف والدفعات التاريخية (§21).
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val getSessionStateUseCase: GetSessionStateUseCase,
    private val getHistoricalBatchesUseCase: GetHistoricalBatchesUseCase,
    private val getHistoricalBatchDetailUseCase: GetHistoricalBatchDetailUseCase,
    private val archiveBatchUseCase: ArchiveBatchUseCase,
    private val exportHistoryToCsvUseCase: ExportHistoryToCsvUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private val _events = Channel<HistoryEvent>(Channel.BUFFERED)
    val events: Flow<HistoryEvent> = _events.receiveAsFlow()

    private var historyJob: Job? = null
    private var detailJob: Job? = null

    init {
        loadHistory()
    }

    /** مراقبة واحدة فقط: كل بحث جديد يلغي السابق (كان يتراكم مُجمِّع لكل حرف وتتسابق النتائج القديمة). */
    private fun loadHistory() {
        historyJob?.cancel()
        _uiState.update { it.copy(isLoading = true) }
        val filter = _uiState.value.filter
        historyJob = getSessionStateUseCase.gate(onSignedOut = { _uiState.update { it.copy(batches = emptyList(), selectedBatch = null) } }) { getHistoricalBatchesUseCase(filter) }
            .onEach { list -> _uiState.update { it.copy(batches = list, isLoading = false) } }
            .catch { _uiState.update { it.copy(isLoading = false, errorMessageResId = R.string.ERR_UNKNOWN) } }
            .launchIn(viewModelScope)
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(filter = it.filter.copy(query = query)) }
        loadHistory()
    }

    fun selectBatch(batchId: String) {
        detailJob?.cancel()
        detailJob = getHistoricalBatchDetailUseCase(batchId)
            .onEach { batch -> _uiState.update { it.copy(selectedBatch = batch) } }
            .catch { }
            .launchIn(viewModelScope)
    }

    fun archiveBatch(batchId: String) {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val res = safe { archiveBatchUseCase(batchId) }
            _uiState.update {
                when (res) {
                    is AppResult.Success -> it.copy(isLoading = false, successMessageResId = R.string.msg_batch_archived)
                    is AppResult.Error -> it.copy(isLoading = false, errorMessageResId = ErrorTranslator.resIdOf(res.error))
                    is AppResult.Loading -> it.copy(isLoading = false)
                }
            }
        }
    }

    fun exportToCsv() {
        if (_uiState.value.isExporting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true) }
            val res = safe { exportHistoryToCsvUseCase(_uiState.value.filter) }
            when (res) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isExporting = false) }
                    // نجاح التجهيز لا يُعلَن هنا: الواجهة تعلنه بعد كتابة الملف فعلاً (حدث لمرة واحدة)
                    _events.send(HistoryEvent.ExportReady(res.data as String))
                }
                is AppResult.Error -> _uiState.update { it.copy(isExporting = false, errorMessageResId = ErrorTranslator.resIdOf(res.error)) }
                is AppResult.Loading -> _uiState.update { it.copy(isExporting = false) }
            }
        }
    }

    /** تُستدعى من الواجهة بعد نجاح/فشل كتابة ملف التصدير. */
    fun onExportFileResult(success: Boolean) {
        _uiState.update {
            if (success) it.copy(successMessageResId = R.string.msg_history_exported)
            else it.copy(errorMessageResId = R.string.ERR_UNKNOWN)
        }
    }

    private suspend fun safe(block: suspend () -> AppResult<*>): AppResult<*> = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        AppResult.Error(ApiError.mapThrowable(e))
    }

    fun clearMessages() {
        _uiState.update { it.copy(successMessageResId = null, errorMessageResId = null) }
    }
}

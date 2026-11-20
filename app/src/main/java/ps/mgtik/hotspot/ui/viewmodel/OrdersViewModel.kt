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
import ps.mgtik.hotspot.domain.model.Batch
import ps.mgtik.hotspot.domain.model.Order
import ps.mgtik.hotspot.domain.repository.IOrderRepository
import ps.mgtik.hotspot.domain.usecase.*
import ps.mgtik.hotspot.ui.util.ErrorTranslator
import javax.inject.Inject

data class OrdersUiState(
    val selectedOrder: Order? = null,
    val selectedOrderBatches: List<Batch> = emptyList(),
    /** تحميل تفاصيل الطلب المحدَّد. */
    val isLoading: Boolean = false,
    /** وصل التدفق ولا يوجد طلب بهذا المعرّف (يُعرض كحالة «غير موجود» بدل شاشة فارغة). */
    val selectedOrderNotFound: Boolean = false,
    /** عملية كتابة (إنشاء/إغلاق) قيد التنفيذ؛ تمنع الضغط المتكرر. */
    val isProcessing: Boolean = false,
    @StringRes val successMessageResId: Int? = null,
    @StringRes val errorMessageResId: Int? = null
)

/**
 * OrdersViewModel - إدارة طلبات التوليد والدفعات (§8)
 */
@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val getOrderDetailUseCase: GetOrderDetailUseCase,
    private val getOrderBatchesUseCase: GetOrderBatchesUseCase,
    private val createOrderUseCase: CreateOrderUseCase,
    private val closeOrderUseCase: CloseOrderUseCase,
    private val reopenOrderUseCase: ReopenOrderUseCase,
    private val cancelDraftOrderUseCase: CancelDraftOrderUseCase,
    private val correctOrderOwnerUseCase: CorrectOrderOwnerUseCase,
    private val retryMissedBillingUseCase: RetryMissedBillingUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(OrdersUiState())
    val uiState: StateFlow<OrdersUiState> = _uiState.asStateFlow()

    private var selectionJob: Job? = null
    private var selectedId: String? = null

    // قائمة الطلبات مصدرها الوحيد MgtikViewModel (مُقيَّد بالجلسة)؛ هنا تفاصيل الطلب المحدَّد وعملياته فقط.

    fun selectOrder(orderId: String) {
        if (orderId.isBlank()) {
            _uiState.update { it.copy(selectedOrder = null, selectedOrderBatches = emptyList(), isLoading = false, selectedOrderNotFound = true) }
            return
        }
        // نفس الطلب ومراقبته فعّالة: لا نعيد التحميل (لا وميض ولا طلبات مكررة)
        if (selectedId == orderId && selectionJob?.isActive == true) return
        selectedId = orderId
        selectionJob?.cancel()
        // تفريغ الطلب السابق فوراً كي لا تُعرض بيانات طلب قديم تحت معرّف جديد
        _uiState.update { it.copy(selectedOrder = null, selectedOrderBatches = emptyList(), isLoading = true, selectedOrderNotFound = false) }

        selectionJob = combine(
            getOrderDetailUseCase(orderId),
            getOrderBatchesUseCase(orderId)
        ) { order, batches -> order to batches }
            .onEach { (order, batches) ->
                _uiState.update {
                    it.copy(
                        selectedOrder = order,
                        selectedOrderBatches = batches,
                        isLoading = false,
                        selectedOrderNotFound = order == null
                    )
                }
            }
            .catch { _uiState.update { it.copy(isLoading = false, errorMessageResId = R.string.ERR_UNKNOWN) } }
            .launchIn(viewModelScope)
    }

    fun createOrder(ownerId: String) = runOperation(R.string.msg_order_created) { createOrderUseCase(ownerId) }

    fun closeOrder(orderId: String) = runOperation(R.string.msg_order_closed) { closeOrderUseCase(orderId) }

    fun reopenOrder(orderId: String) = runOperation(R.string.msg_order_reopened) { reopenOrderUseCase(orderId) }

    fun cancelDraftOrder(orderId: String) = runOperation(R.string.msg_order_canceled) { cancelDraftOrderUseCase(orderId) }

    fun correctOwner(orderId: String, newOwnerId: String) {
        if (newOwnerId.isBlank()) return
        runOperation(R.string.msg_order_owner_corrected) { correctOrderOwnerUseCase(orderId, newOwnerId) }
    }

    /** طلب مغلق بلا فاتورة: يعيد تحفيز الإصدار السحابي (العقد الحالي في retryMissedBilling). */
    fun retryBilling(orderId: String) = runOperation(R.string.msg_order_billing_retried) { retryMissedBillingUseCase(orderId) }

    private fun runOperation(@StringRes successResId: Int, block: suspend () -> AppResult<*>) {
        if (_uiState.value.isProcessing) return
        _uiState.update { it.copy(isProcessing = true, successMessageResId = null, errorMessageResId = null) }
        viewModelScope.launch {
            val res = try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.Error(ApiError.mapThrowable(e))
            }
            _uiState.update {
                when (res) {
                    is AppResult.Success -> it.copy(isProcessing = false, successMessageResId = successResId)
                    is AppResult.Error -> it.copy(isProcessing = false, errorMessageResId = ErrorTranslator.resIdOf(res.error))
                    is AppResult.Loading -> it.copy(isProcessing = false)
                }
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(successMessageResId = null, errorMessageResId = null) }
    }
}

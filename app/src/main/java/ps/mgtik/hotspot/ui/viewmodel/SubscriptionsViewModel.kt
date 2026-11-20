package ps.mgtik.hotspot.ui.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
import ps.mgtik.hotspot.domain.model.NewSubscription
import ps.mgtik.hotspot.domain.model.Subscription
import ps.mgtik.hotspot.domain.model.SubscriptionExecution
import ps.mgtik.hotspot.domain.model.SubscriptionInvoice
import ps.mgtik.hotspot.domain.model.SubscriptionOperation
import ps.mgtik.hotspot.domain.model.SubscriptionPayment
import ps.mgtik.hotspot.domain.usecase.CancelSubscriptionUseCase
import ps.mgtik.hotspot.domain.usecase.CreateSubscriptionUseCase
import ps.mgtik.hotspot.domain.usecase.GetSubscriptionDetailsUseCase
import ps.mgtik.hotspot.domain.usecase.RecordSubscriptionPaymentUseCase
import ps.mgtik.hotspot.domain.usecase.RefreshSubscriptionFromRouterUseCase
import ps.mgtik.hotspot.domain.usecase.RenewSubscriptionUseCase
import ps.mgtik.hotspot.domain.usecase.ResumeSubscriptionUseCase
import ps.mgtik.hotspot.domain.usecase.RetrySubscriptionSyncUseCase
import ps.mgtik.hotspot.domain.usecase.SuspendSubscriptionUseCase
import ps.mgtik.hotspot.domain.usecase.VoidSubscriptionPaymentUseCase
import ps.mgtik.hotspot.ui.util.ErrorTranslator
import javax.inject.Inject

data class SubscriptionsUiState(
    /** معرّف الاشتراك الذي تُنفَّذ عليه عملية الآن (يعطّل أزراره فقط). */
    val processingId: String? = null,
    /** إنشاء اشتراك قيد التنفيذ. */
    val isCreating: Boolean = false,
    /** معرّف اشتراك أُنشئ للتو (الواجهة تفتح تفاصيله ثم تستدعي consumeCreated). */
    val createdId: String? = null,
    /** خطأ الإنشاء يُعرض داخل النموذج نفسه ليبقى المستخدم أمام بياناته. */
    @StringRes val createErrorResId: Int? = null,
    @StringRes val successMessageResId: Int? = null,
    /** نجاح إداري مع أمر راوتر لم يكتمل بعد (ينفَّذ تلقائياً لاحقاً). */
    @StringRes val warningMessageResId: Int? = null,
    @StringRes val errorMessageResId: Int? = null,
    val selectedId: String? = null,
    val invoices: List<SubscriptionInvoice> = emptyList(),
    val payments: List<SubscriptionPayment> = emptyList(),
    val operations: List<SubscriptionOperation> = emptyList()
)

/**
 * SubscriptionsViewModel - عمليات الاشتراكات وتفاصيلها (فواتير/دفعات/أوامر الراوتر).
 * القائمة نفسها تأتي من الحالة المركزية (MgtikViewModel.subscriptions)؛ هنا العمليات ونتائجها وتفاصيل الاشتراك المحدد.
 */
@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    private val createUseCase: CreateSubscriptionUseCase,
    private val renewUseCase: RenewSubscriptionUseCase,
    private val suspendUseCase: SuspendSubscriptionUseCase,
    private val resumeUseCase: ResumeSubscriptionUseCase,
    private val cancelUseCase: CancelSubscriptionUseCase,
    private val retrySyncUseCase: RetrySubscriptionSyncUseCase,
    private val refreshUseCase: RefreshSubscriptionFromRouterUseCase,
    private val recordPaymentUseCase: RecordSubscriptionPaymentUseCase,
    private val voidPaymentUseCase: VoidSubscriptionPaymentUseCase,
    private val detailsUseCase: GetSubscriptionDetailsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(SubscriptionsUiState())
    val uiState: StateFlow<SubscriptionsUiState> = _uiState.asStateFlow()

    private val detailJobs = mutableListOf<Job>()

    // ───────────── اختيار الاشتراك وتفاصيله ─────────────

    fun select(subscriptionId: String) {
        if (_uiState.value.selectedId == subscriptionId) return
        detailJobs.forEach { it.cancel() }
        detailJobs.clear()
        _uiState.update { it.copy(selectedId = subscriptionId, invoices = emptyList(), payments = emptyList(), operations = emptyList()) }
        detailJobs += detailsUseCase.invoices(subscriptionId)
            .onEach { list -> _uiState.update { it.copy(invoices = list) } }
            .catch { _uiState.update { s -> s.copy(errorMessageResId = R.string.ERR_UNKNOWN) } }
            .launchIn(viewModelScope)
        detailJobs += detailsUseCase.payments(subscriptionId)
            .onEach { list -> _uiState.update { it.copy(payments = list) } }
            .catch { _uiState.update { s -> s.copy(errorMessageResId = R.string.ERR_UNKNOWN) } }
            .launchIn(viewModelScope)
        detailJobs += detailsUseCase.operations(subscriptionId)
            .onEach { list -> _uiState.update { it.copy(operations = list) } }
            .catch { _uiState.update { s -> s.copy(errorMessageResId = R.string.ERR_UNKNOWN) } }
            .launchIn(viewModelScope)
    }

    fun clearSelection() {
        detailJobs.forEach { it.cancel() }
        detailJobs.clear()
        _uiState.update { it.copy(selectedId = null, invoices = emptyList(), payments = emptyList(), operations = emptyList()) }
    }

    // ───────────── العمليات الإدارية ─────────────

    fun renew(subscriptionId: String) = run(subscriptionId, R.string.msg_subscription_renewed) { renewUseCase(subscriptionId) }
    fun suspend(subscriptionId: String) = run(subscriptionId, R.string.msg_subscription_status_changed) { suspendUseCase(subscriptionId) }
    fun resume(subscriptionId: String) = run(subscriptionId, R.string.msg_subscription_status_changed) { resumeUseCase(subscriptionId) }
    fun cancel(subscriptionId: String) = run(subscriptionId, R.string.msg_subscription_status_changed) { cancelUseCase(subscriptionId) }
    fun retrySync(subscriptionId: String) = run(subscriptionId, R.string.sub_msg_synced) { retrySyncUseCase(subscriptionId) }
    fun refreshFromRouter(subscriptionId: String) = run(subscriptionId, R.string.sub_msg_refreshed) { refreshUseCase(subscriptionId) }

    /**
     * معرّف الاشتراك (UUID يُولَّد مرة واحدة عند فتح النموذج) هو مفتاح idempotency، فإعادة المحاولة بعد فشل شبكة
     * لا تُنشئ اشتراكاً مكرراً.
     */
    fun create(draft: NewSubscription, subscriptionId: String) {
        if (_uiState.value.isCreating) return
        _uiState.update { it.copy(isCreating = true, createErrorResId = null) }
        viewModelScope.launch {
            val res = guarded { createUseCase(draft, subscriptionId) }
            _uiState.update {
                when (res) {
                    is AppResult.Success -> it.copy(
                        isCreating = false,
                        createdId = res.data.id,
                        successMessageResId = if (res.data.execution == SubscriptionExecution.SYNCED) R.string.msg_subscription_created else null,
                        warningMessageResId = if (res.data.execution == SubscriptionExecution.SYNCED) null else R.string.sub_msg_router_pending
                    )
                    is AppResult.Error -> it.copy(isCreating = false, createErrorResId = ErrorTranslator.resIdOf(res.error))
                    is AppResult.Loading -> it.copy(isCreating = false)
                }
            }
        }
    }

    fun consumeCreated() = _uiState.update { it.copy(createdId = null) }

    // ───────────── المحاسبة ─────────────

    fun recordPayment(subscriptionId: String, invoiceId: String, amountMinor: Long, method: String) {
        if (_uiState.value.processingId != null) return
        _uiState.update { it.copy(processingId = subscriptionId) }
        viewModelScope.launch {
            val res = guarded { recordPaymentUseCase(invoiceId, amountMinor, method, IdempotencyKeyProvider.generateKey()) }
            _uiState.update {
                when (res) {
                    is AppResult.Success -> it.copy(processingId = null, successMessageResId = R.string.sub_msg_payment_recorded)
                    is AppResult.Error -> it.copy(processingId = null, errorMessageResId = ErrorTranslator.resIdOf(res.error))
                    is AppResult.Loading -> it.copy(processingId = null)
                }
            }
        }
    }

    fun voidPayment(subscriptionId: String, paymentId: String) {
        if (_uiState.value.processingId != null) return
        _uiState.update { it.copy(processingId = subscriptionId) }
        viewModelScope.launch {
            val res = guarded { voidPaymentUseCase(paymentId) }
            _uiState.update {
                when (res) {
                    is AppResult.Success -> it.copy(processingId = null, successMessageResId = R.string.sub_msg_payment_voided)
                    is AppResult.Error -> it.copy(processingId = null, errorMessageResId = ErrorTranslator.resIdOf(res.error))
                    is AppResult.Loading -> it.copy(processingId = null)
                }
            }
        }
    }

    fun clearMessages() = _uiState.update {
        it.copy(successMessageResId = null, warningMessageResId = null, errorMessageResId = null, createErrorResId = null)
    }

    // ───────────── مساعدات ─────────────

    private fun run(subscriptionId: String, @StringRes successRes: Int, block: suspend () -> AppResult<Subscription>) {
        if (_uiState.value.processingId != null) return
        _uiState.update { it.copy(processingId = subscriptionId) }
        viewModelScope.launch {
            val res = guarded(block)
            _uiState.update {
                when (res) {
                    is AppResult.Success ->
                        // نجاح إداري لكن الراوتر لم يتحقق بعد ⇒ تنبيه لا نجاح كامل
                        if (res.data.execution == SubscriptionExecution.SYNCED) it.copy(processingId = null, successMessageResId = successRes)
                        else it.copy(processingId = null, warningMessageResId = R.string.sub_msg_router_pending)
                    is AppResult.Error -> it.copy(processingId = null, errorMessageResId = ErrorTranslator.resIdOf(res.error))
                    is AppResult.Loading -> it.copy(processingId = null)
                }
            }
        }
    }

    private suspend fun <T> guarded(block: suspend () -> AppResult<T>): AppResult<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppResult.Error(ApiError.mapThrowable(e))
    }
}

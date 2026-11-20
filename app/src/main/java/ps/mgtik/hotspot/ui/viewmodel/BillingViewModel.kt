package ps.mgtik.hotspot.ui.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.PendingKeys
import ps.mgtik.hotspot.domain.model.*
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import ps.mgtik.hotspot.data.pdf.InvoicePdfGenerator
import java.io.File
import ps.mgtik.hotspot.domain.repository.IBillingRepository
import ps.mgtik.hotspot.domain.usecase.*
import ps.mgtik.hotspot.ui.util.ErrorTranslator
import javax.inject.Inject

/**
 * BillingUiState - الحالة المالية الشاملة (§8)
 * الرسائل مراجع موارد (لا نصوصاً خاماً) لتتبع لغة الجهاز ولا تُعرض أخطاء تقنية للمستخدم.
 */
/** حالة توليد PDF للفاتورة المفتوحة: [file] جاهز للمعاينة/الحفظ/المشاركة/الطباعة. */
data class PdfUiState(
    val isGenerating: Boolean = false,
    val file: File? = null,
    @StringRes val errorResId: Int? = null
)

data class BillingUiState(
    val selectedInvoiceEvents: List<InvoiceEventLog> = emptyList(),
    /** دفعات الفاتورة المفتوحة (من السحابة مباشرة، وتتحدث فور أي دفعة/إبطال/استرداد). */
    val invoicePayments: List<Payment> = emptyList(),
    /** دفعات الطلب المرتبط بالفاتورة (لقطة السعر المثبَّتة هي مصدر بنود الفاتورة). */
    val orderBatches: List<Batch> = emptyList(),
    val pdf: PdfUiState = PdfUiState(),
    /** عملية مالية قيد التنفيذ؛ تمنع الضغط المتكرر الذي يكرّر عملية حساسة. */
    val isLoading: Boolean = false,
    @StringRes val successMessageResId: Int? = null,
    @StringRes val errorMessageResId: Int? = null
)

/**
 * BillingViewModel - الإدارة المالية والفوترة (§8)
 */
@HiltViewModel
class BillingViewModel @Inject constructor(
    private val billingRepository: IBillingRepository,
    private val recordPaymentUseCase: RecordPaymentUseCase,
    private val cancelInvoiceUseCase: CancelInvoiceUseCase,
    private val voidPaymentUseCase: VoidPaymentUseCase,
    private val applyInvoiceDiscountUseCase: ApplyInvoiceDiscountUseCase,
    private val applyOwnerCreditUseCase: ApplyOwnerCreditUseCase,
    private val reopenInvoiceUseCase: ReopenCancelledInvoiceUseCase,
    private val refundPaymentUseCase: RefundPaymentUseCase,
    private val updateInvoiceUseCase: UpdateInvoiceUseCase,
    private val getInvoicePaymentsUseCase: GetInvoicePaymentsUseCase,
    private val getOrderBatchesUseCase: GetOrderBatchesUseCase,
    private val getSettingsUseCase: GetSettingsUseCase,
    private val getBillingSettingsUseCase: GetBillingSettingsUseCase,
    private val pdfGenerator: InvoicePdfGenerator,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(BillingUiState())
    val uiState: StateFlow<BillingUiState> = _uiState.asStateFlow()

    private var eventsJob: Job? = null
    private var paymentsJob: Job? = null
    private var batchesJob: Job? = null
    private val pendingKeys = PendingKeys()

    // قوائم الفواتير ولوحة الملخص المالي مصدرها الوحيد MgtikViewModel (مُقيَّد بالجلسة). كانت هنا نسخة مكررة
    // بلا أي قارئ في الواجهة: مستمعا Firestore إضافيان يموتان عند أول خطأ صلاحيات قبل تسجيل الدخول.

    // --- عمليات الفواتير (Invoices) ---

    fun loadInvoiceEvents(invoiceId: String) {
        eventsJob?.cancel()
        _uiState.update { it.copy(selectedInvoiceEvents = emptyList()) }
        eventsJob = billingRepository.listInvoiceEvents(invoiceId)
            .onEach { events -> _uiState.update { it.copy(selectedInvoiceEvents = events) } }
            .catch { _uiState.update { it.copy(selectedInvoiceEvents = emptyList()) } }
            .launchIn(viewModelScope)
    }

    /** يفتح فاتورة: يتابع دفعاتها ودفعات طلبها فتنعكس أي عملية مالية على التفاصيل فوراً. */
    fun observeInvoice(invoice: Invoice) {
        paymentsJob?.cancel(); batchesJob?.cancel()
        _uiState.update { it.copy(invoicePayments = emptyList(), orderBatches = emptyList(), pdf = PdfUiState()) }
        paymentsJob = getInvoicePaymentsUseCase(invoice.id)
            .onEach { list -> _uiState.update { it.copy(invoicePayments = list) } }
            .catch { _uiState.update { it.copy(invoicePayments = emptyList()) } }
            .launchIn(viewModelScope)
        if (invoice.orderId.isNotBlank()) {
            batchesJob = getOrderBatchesUseCase(invoice.orderId)
                .onEach { list -> _uiState.update { it.copy(orderBatches = list) } }
                .catch { _uiState.update { it.copy(orderBatches = emptyList()) } }
                .launchIn(viewModelScope)
        }
    }

    /** تاريخ الاستحقاق (null = إزالته). لا يمسّ أي مبلغ: حقل dueDate من الحقول المسموحة فقط. */
    fun setDueDate(invoiceId: String, millis: Long?) =
        runOperation("due|$invoiceId|${millis ?: "none"}", R.string.msg_due_date_saved) { key ->
            updateInvoiceUseCase(invoiceId, mapOf("dueDate" to millis?.let { java.util.Date(it) }), key)
        }

    // --- PDF ---

    /**
     * يولّد PDF من بيانات الفاتورة المحفوظة (قراءة حيّة من السحابة لحظة الطلب) ولقطة سعر الدفعات؛
     * لا يستعمل سعراً حالياً ولا تقديراً من الواجهة. الملف يُستبدل في كل توليد فلا يُعرض ملف قديم.
     */
    fun preparePdf(invoice: Invoice) {
        if (_uiState.value.pdf.isGenerating) return
        _uiState.update { it.copy(pdf = PdfUiState(isGenerating = true)) }
        viewModelScope.launch {
            val result: PdfUiState = try {
                val company = getSettingsUseCase.getCompanyInfo().first()
                val billing = try { getBillingSettingsUseCase().first() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
                val payments = getInvoicePaymentsUseCase(invoice.id).first()
                val batches = if (invoice.orderId.isBlank()) emptyList() else getOrderBatchesUseCase(invoice.orderId).first()
                when (val built = InvoiceDocumentBuilder.build(invoice, batches, payments, company, billing, java.time.LocalDate.now().toString())) {
                    is InvoiceDocumentBuilder.Result.Error -> PdfUiState(errorResId = R.string.inv_error_inconsistent)
                    is InvoiceDocumentBuilder.Result.Ok -> {
                        val name = (built.document.invoiceNumber.ifBlank { invoice.id }).replace(Regex("[^A-Za-z0-9_-]"), "_")
                        when (val gen = pdfGenerator.generate(built.document, File(File(appContext.cacheDir, "exports"), "invoice-$name.pdf"))) {
                            is AppResult.Success -> PdfUiState(file = gen.data)
                            is AppResult.Error -> PdfUiState(errorResId = ErrorTranslator.resIdOf(gen.error))
                            is AppResult.Loading -> PdfUiState()
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                PdfUiState(errorResId = ErrorTranslator.resIdOf(ps.mgtik.hotspot.core.result.ApiError.mapThrowable(e)))
            }
            _uiState.update { it.copy(pdf = result) }
        }
    }

    fun clearPdf() { _uiState.update { it.copy(pdf = PdfUiState()) } }

    fun recordPayment(invoiceId: String, amountMinor: Long, method: String, reference: String? = null) =
        runOperation("pay|$invoiceId|$amountMinor|$method|${reference.orEmpty()}", R.string.msg_payment_recorded) { key ->
            recordPaymentUseCase(invoiceId, amountMinor, method, reference, idempotencyKey = key)
        }

    fun applyDiscount(invoiceId: String, discountType: String, discountValue: Long) =
        runOperation("discount|$invoiceId|$discountType|$discountValue", R.string.msg_discount_applied) { key ->
            applyInvoiceDiscountUseCase(invoiceId, discountType, discountValue, key)
        }

    fun applyCredit(invoiceId: String, amountMinor: Long) =
        runOperation("credit|$invoiceId|$amountMinor", R.string.msg_credit_applied) { key ->
            applyOwnerCreditUseCase(invoiceId, amountMinor, key)
        }

    fun cancelInvoice(invoiceId: String, force: Boolean, resolution: String?) =
        runOperation("cancel|$invoiceId|$force|${resolution.orEmpty()}", R.string.msg_invoice_canceled) { key ->
            cancelInvoiceUseCase(invoiceId, force, resolution, key)
        }

    fun reopenInvoice(invoiceId: String) =
        runOperation("reopen|$invoiceId", R.string.msg_invoice_reopened) { key ->
            reopenInvoiceUseCase(invoiceId, key)
        }

    // --- عمليات الدفعات (Payments) ---

    fun voidPayment(paymentId: String, reason: String) =
        runOperation("void|$paymentId", R.string.msg_payment_voided) { key -> voidPaymentUseCase(paymentId, reason, key) }

    fun refundPayment(paymentId: String, amountMinor: Long) =
        runOperation("refund|$paymentId|$amountMinor", R.string.msg_payment_refunded) { key ->
            refundPaymentUseCase(paymentId, amountMinor, key)
        }

    /**
     * تنفيذ عملية مالية واحدة في كل مرة: الضغط المتكرر أثناء التنفيذ يُتجاهل. المفتاح مرتبط ببصمة العملية ويبقى بعد
     * الفشل (إعادة المحاولة لا تكرّر عملية نجحت فعلاً وضاع ردّها) ويُمسح بالنجاح. رسالة النجاح لا تظهر إلا بعد نجاح
     * الطبقة المسؤولة.
     */
    private fun runOperation(signature: String, @StringRes successResId: Int, block: suspend (String) -> AppResult<*>) {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, successMessageResId = null, errorMessageResId = null) }
        val key = pendingKeys.keyFor(signature)
        viewModelScope.launch {
            val res = try {
                block(key)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.Error(ps.mgtik.hotspot.core.result.ApiError.mapThrowable(e))
            }
            if (res is AppResult.Success) pendingKeys.clear(signature)
            _uiState.update {
                when (res) {
                    is AppResult.Success -> it.copy(isLoading = false, successMessageResId = successResId)
                    is AppResult.Error -> it.copy(isLoading = false, errorMessageResId = ErrorTranslator.resIdOf(res.error))
                    is AppResult.Loading -> it.copy(isLoading = false)
                }
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(successMessageResId = null, errorMessageResId = null) }
    }
}

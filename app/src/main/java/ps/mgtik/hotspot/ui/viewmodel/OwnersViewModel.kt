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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.map
import ps.mgtik.hotspot.core.result.IdempotencyKeyProvider
import ps.mgtik.hotspot.domain.model.InvoiceStatus
import ps.mgtik.hotspot.domain.model.OwnerAccount
import ps.mgtik.hotspot.domain.model.OwnerStatus
import ps.mgtik.hotspot.domain.model.OwnerWorkspace
import ps.mgtik.hotspot.domain.usecase.DeleteOwnerUseCase
import ps.mgtik.hotspot.domain.usecase.GetInvoicesUseCase
import ps.mgtik.hotspot.domain.usecase.GetOwnerWorkspaceUseCase
import ps.mgtik.hotspot.domain.usecase.GetOwnerPriceBookUseCase
import ps.mgtik.hotspot.domain.usecase.OwnerPriceBook
import ps.mgtik.hotspot.domain.usecase.GetOwnersSummaryUseCase
import ps.mgtik.hotspot.domain.usecase.GetSessionStateUseCase
import ps.mgtik.hotspot.domain.usecase.OwnerInputValidator
import ps.mgtik.hotspot.domain.usecase.RecordOwnerAdvanceUseCase
import ps.mgtik.hotspot.domain.usecase.RefundFromOwnerCreditUseCase
import ps.mgtik.hotspot.domain.usecase.SaveOwnerUseCase
import ps.mgtik.hotspot.domain.usecase.SetOwnerStatusUseCase
import ps.mgtik.hotspot.domain.usecase.SettleOwnerDuesUseCase
import ps.mgtik.hotspot.domain.usecase.UpdateOwnerProfileUseCase
import ps.mgtik.hotspot.domain.usecase.VoidPaymentUseCase
import ps.mgtik.hotspot.domain.usecase.SetOwnerOpeningBalanceUseCase
import ps.mgtik.hotspot.ui.util.ErrorTranslator
import javax.inject.Inject

object OwnerField {
    const val NAME = "name"
    const val PHONE = "phone"
}

/** تصفية دليل الملاك. ARCHIVED وحدها تُظهر المؤرشفين؛ البقية تستثنيهم. */
enum class OwnerFilter { ALL, WITH_DUES, WITH_CREDIT, SUSPENDED, ARCHIVED }

/** ترتيب الدليل: الاسم (افتراضي)، الأعلى مستحقاً، الأعلى رصيداً. */
enum class OwnerSort { NAME, DUES_DESC, CREDIT_DESC }

data class OwnersUiState(
    /** كل الملاك بمن فيهم المؤرشفون (يُستخدم لكشف تكرار الهاتف). */
    val ownersSummary: List<OwnerAccount> = emptyList(),
    /** الملاك الفعّالون المطابقون للبحث (المؤرشفون مخفيون من الدليل). */
    val filteredOwners: List<OwnerAccount> = emptyList(),
    val searchQuery: String = "",
    val filter: OwnerFilter = OwnerFilter.ALL,
    val sort: OwnerSort = OwnerSort.NAME,
    /** مجموع المستحق (الوحدة الصغرى) لكل مالك من فواتيره DUE/PARTIALLY_PAID. */
    val dueByOwner: Map<String, Long> = emptyMap(),
    val selectedOwnerWorkspace: OwnerWorkspace? = null,
    /** دفتر أسعار الوكيل المحدّد؛ null = لم يُحمَّل بعد/فشل (تُعرض حالة تحميل لا أرقام وهمية). */
    val selectedOwnerPriceBook: OwnerPriceBook? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    @StringRes val successMessageResId: Int? = null,
    @StringRes val errorMessageResId: Int? = null,
    val validationErrors: Map<String, Int> = emptyMap()
)

/** أحداث لمرة واحدة تقود الملاحة (لا تُعاد عند إعادة التركيب كما تفعل الرسائل). */
sealed interface OwnersEvent {
    data object OwnerCreated : OwnersEvent
    data object OwnerArchived : OwnersEvent
    data object OwnerUpdated : OwnersEvent
}

@HiltViewModel
class OwnersViewModel @Inject constructor(
    private val getSessionStateUseCase: GetSessionStateUseCase,
    private val getOwnersSummaryUseCase: GetOwnersSummaryUseCase,
    private val getOwnerWorkspaceUseCase: GetOwnerWorkspaceUseCase,
    private val setOpeningBalanceUseCase: SetOwnerOpeningBalanceUseCase,
    private val recordAdvanceUseCase: RecordOwnerAdvanceUseCase,
    private val refundFromCreditUseCase: RefundFromOwnerCreditUseCase,
    private val saveOwnerUseCase: SaveOwnerUseCase,
    private val updateOwnerProfileUseCase: UpdateOwnerProfileUseCase,
    private val setOwnerStatusUseCase: SetOwnerStatusUseCase,
    private val settleOwnerDuesUseCase: SettleOwnerDuesUseCase,
    private val voidPaymentUseCase: VoidPaymentUseCase,
    private val getInvoicesUseCase: GetInvoicesUseCase,
    private val getOwnerPriceBookUseCase: GetOwnerPriceBookUseCase,
    private val archiveOwnerUseCase: DeleteOwnerUseCase // الحذف هنا أرشفة (status = ARCHIVED) حفاظاً على السجلات المالية
) : ViewModel() {

    private val _uiState = MutableStateFlow(OwnersUiState())
    val uiState: StateFlow<OwnersUiState> = _uiState.asStateFlow()

    private val _events = Channel<OwnersEvent>(Channel.BUFFERED)
    val events: Flow<OwnersEvent> = _events.receiveAsFlow()

    private var workspaceJob: Job? = null
    private var priceBookJob: Job? = null

    /**
     * محاولة الاسترجاع الأخيرة (مالك، مبلغ، مفتاح): إعادة المحاولة بعد فشل لا يُعرف مصيره (مهلة/انقطاع) تعيد
     * استخدام المفتاح نفسه، فلا يُخصم المبلغ مرتين إن كانت المحاولة الأولى قد نجحت فعلاً (السحابة تتجاهل المفتاح المكرر).
     */
    private var refundAttempt: Triple<String, Long, String>? = null
    private val pendingKeys = ps.mgtik.hotspot.core.result.PendingKeys()

    init {
        observeOwnersSummary()
    }

    private fun observeOwnersSummary() {
        // الأرصدة + مستحقات كل مالك من فواتيره (تُحدَّث لحظياً مع أي دفعة أو فاتورة)
        getSessionStateUseCase.gate(onSignedOut = { _uiState.value = OwnersUiState() }) {
            combine(getOwnersSummaryUseCase(), getInvoicesUseCase()) { owners, invoices ->
                val dues = invoices
                    .filter { it.status == InvoiceStatus.DUE || it.status == InvoiceStatus.PARTIALLY_PAID }
                    .groupBy { it.ownerId }
                    .mapValues { (_, list) -> list.sumOf { maxOf(it.amountDueMinor, 0L) } }
                owners to dues
            }
        }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onEach { (list, dues) ->
                _uiState.update { state ->
                    state.copy(
                        ownersSummary = list,
                        dueByOwner = dues,
                        filteredOwners = filterOwners(list, state.searchQuery, state.filter, dues, state.sort),
                        isLoading = false
                    )
                }
            }
            .catch { _uiState.update { it.copy(isLoading = false, errorMessageResId = R.string.ERR_UNKNOWN) } }
            .launchIn(viewModelScope)
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { state ->
            state.copy(searchQuery = query, filteredOwners = filterOwners(state.ownersSummary, query, state.filter, state.dueByOwner, state.sort))
        }
    }

    fun setFilter(filter: OwnerFilter) {
        _uiState.update { state ->
            state.copy(filter = filter, filteredOwners = filterOwners(state.ownersSummary, state.searchQuery, filter, state.dueByOwner, state.sort))
        }
    }

    fun setSort(sort: OwnerSort) {
        _uiState.update { state ->
            state.copy(sort = sort, filteredOwners = filterOwners(state.ownersSummary, state.searchQuery, state.filter, state.dueByOwner, sort))
        }
    }

    private fun filterOwners(
        list: List<OwnerAccount>,
        query: String,
        filter: OwnerFilter,
        dues: Map<String, Long>,
        sort: OwnerSort = OwnerSort.NAME
    ): List<OwnerAccount> {
        val byFilter = when (filter) {
            OwnerFilter.ARCHIVED -> list.filter { it.status == OwnerStatus.ARCHIVED }
            OwnerFilter.SUSPENDED -> list.filter { it.status == OwnerStatus.SUSPENDED }
            OwnerFilter.WITH_DUES -> list.filter { it.status != OwnerStatus.ARCHIVED && (dues[it.ownerId] ?: 0L) > 0 }
            OwnerFilter.WITH_CREDIT -> list.filter { it.status != OwnerStatus.ARCHIVED && it.availableCreditMinor > 0 }
            OwnerFilter.ALL -> list.filter { it.status != OwnerStatus.ARCHIVED }
        }
        val sorted = when (sort) {
            OwnerSort.NAME -> byFilter.sortedBy { it.name.lowercase() }
            OwnerSort.DUES_DESC -> byFilter.sortedWith(compareByDescending<OwnerAccount> { dues[it.ownerId] ?: 0L }.thenBy { it.name.lowercase() })
            OwnerSort.CREDIT_DESC -> byFilter.sortedWith(compareByDescending<OwnerAccount> { it.availableCreditMinor }.thenBy { it.name.lowercase() })
        }
        if (query.isBlank()) return sorted
        val normalizedQuery = query.trim().lowercase()
        val phoneDigits = query.filter { it.isDigit() }

        return sorted.filter { owner ->
            val nameMatch = owner.name.lowercase().contains(normalizedQuery)
            val phoneMatch = phoneDigits.isNotEmpty() && owner.phone.filter { it.isDigit() }.contains(phoneDigits)
            nameMatch || phoneMatch
        }
    }

    fun selectOwner(ownerId: String) {
        if (ownerId.isBlank()) return
        // إلغاء أي مراقبة سابقة لمنع تسرّب البيانات
        workspaceJob?.cancel()
        priceBookJob?.cancel()
        _uiState.update { it.copy(selectedOwnerWorkspace = null, selectedOwnerPriceBook = null, isLoading = true) }
        priceBookJob = getOwnerPriceBookUseCase(ownerId)
            .onEach { book -> _uiState.update { it.copy(selectedOwnerPriceBook = book) } }
            .catch { e -> android.util.Log.w("OwnersViewModel", "owner price book failed: ${e.message}") }
            .launchIn(viewModelScope)

        workspaceJob = getOwnerWorkspaceUseCase(ownerId)
            .onEach { workspace -> _uiState.update { it.copy(selectedOwnerWorkspace = workspace, isLoading = false) } }
            .catch { e ->
                android.util.Log.w("OwnersViewModel", "owner workspace failed: ${e.message}")
                _uiState.update { it.copy(isLoading = false, errorMessageResId = R.string.ERR_UNKNOWN) }
            }
            .launchIn(viewModelScope)
    }

    fun createOwner(name: String, phone: String) {
        if (_uiState.value.isSaving) return

        val errors = mutableMapOf<String, Int>()
        if (!OwnerInputValidator.isNameValid(name)) errors[OwnerField.NAME] = R.string.owner_error_name_too_short
        if (!OwnerInputValidator.isPhoneValid(phone)) {
            errors[OwnerField.PHONE] = R.string.owner_error_phone_invalid
        } else if (OwnerInputValidator.isPhoneTaken(phone, _uiState.value.ownersSummary.map { it.ownerId to it.phone })) {
            // فحص فوري من القائمة المحلية؛ والمرجع النهائي فحص السحابة قبل الحفظ (انظر CloudOwnerDataSource.saveOwner)
            errors[OwnerField.PHONE] = R.string.owner_error_phone_duplicate
        }
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(validationErrors = errors) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, validationErrors = emptyMap()) }
            val account = OwnerAccount(
                ownerId = IdempotencyKeyProvider.generateKey(),
                name = name.trim(),
                phone = phone.trim(),
                status = OwnerStatus.ACTIVE
            )
            when (val res = saveOwnerUseCase(account)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isSaving = false, successMessageResId = R.string.owner_success_create) }
                    _events.send(OwnersEvent.OwnerCreated)
                }
                is AppResult.Error -> {
                    val duplicate = res.error.code == OwnerInputValidator.ERR_PHONE_DUPLICATE
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            validationErrors = if (duplicate) mapOf(OwnerField.PHONE to R.string.owner_error_phone_duplicate) else it.validationErrors,
                            errorMessageResId = if (duplicate) null else res.error.toMessageRes()
                        )
                    }
                }
                else -> _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun setOpeningBalance(ownerId: String, amountMinor: Long) {
        val signature = "opening|$ownerId|$amountMinor"
        runOwnerOperation(R.string.owner_success_opening_balance, signature) { setOpeningBalanceUseCase(ownerId, amountMinor, pendingKeys.keyFor(signature)) }
    }

    fun recordAdvance(ownerId: String, amountMinor: Long, notes: String? = null) {
        val signature = "advance|$ownerId|$amountMinor|${notes.orEmpty()}"
        runOwnerOperation(R.string.owner_success_advance, signature) { recordAdvanceUseCase(ownerId, amountMinor, notes, pendingKeys.keyFor(signature)) }
    }

    /** استرجاع من الرصيد: تنفيذ مباشر (skipQueue = true) لأن الواجهة تعرض النتيجة/الخطأ فوراً. */
    fun refundFromCredit(ownerId: String, amountMinor: Long) {
        if (_uiState.value.isSaving) return
        val credit = _uiState.value.selectedOwnerWorkspace?.takeIf { it.ownerId == ownerId }?.account?.availableCreditMinor
        if (amountMinor <= 0 || credit == null || amountMinor > credit) {
            _uiState.update { it.copy(errorMessageResId = R.string.owner_error_refund_exceeds) }
            return
        }
        val key = refundAttempt?.takeIf { it.first == ownerId && it.second == amountMinor }?.third
            ?: IdempotencyKeyProvider.generateKey()
        refundAttempt = Triple(ownerId, amountMinor, key)

        runOwnerOperation(R.string.owner_success_refund) {
            refundFromCreditUseCase(ownerId, amountMinor, key, skipQueue = true)
        }.invokeOnCompletion { if (_uiState.value.errorMessageResId == null) refundAttempt = null }
    }

    /** أرشفة المالك (تنفيذ مباشر)؛ بعد النجاح تعود الواجهة للدليل حيث يختفي من القائمة. */
    fun archiveOwner(ownerId: String) {
        if (_uiState.value.isSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            when (val res = archiveOwnerUseCase(ownerId)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isSaving = false, successMessageResId = R.string.owner_success_archive) }
                    _events.send(OwnersEvent.OwnerArchived)
                }
                is AppResult.Error -> _uiState.update { it.copy(isSaving = false, errorMessageResId = res.error.toMessageRes()) }
                else -> _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    /** تعديل الاسم/الهاتف؛ رقم مستعمل لمالك آخر يُرفض محلياً ثم في السحابة. */
    fun updateOwner(ownerId: String, name: String, phone: String) {
        if (_uiState.value.isSaving) return
        val errors = mutableMapOf<String, Int>()
        if (!OwnerInputValidator.isNameValid(name)) errors[OwnerField.NAME] = R.string.owner_error_name_too_short
        if (!OwnerInputValidator.isPhoneValid(phone)) {
            errors[OwnerField.PHONE] = R.string.owner_error_phone_invalid
        } else if (OwnerInputValidator.isPhoneTaken(phone, _uiState.value.ownersSummary.map { it.ownerId to it.phone }, excludeOwnerId = ownerId)) {
            errors[OwnerField.PHONE] = R.string.owner_error_phone_duplicate
        }
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(validationErrors = errors) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, validationErrors = emptyMap(), errorMessageResId = null) }
            when (val res = updateOwnerProfileUseCase(ownerId, name, phone)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isSaving = false, successMessageResId = R.string.owner_success_update) }
                    _events.send(OwnersEvent.OwnerUpdated)
                }
                is AppResult.Error -> {
                    val duplicate = res.error.code == OwnerInputValidator.ERR_PHONE_DUPLICATE
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            validationErrors = if (duplicate) mapOf(OwnerField.PHONE to R.string.owner_error_phone_duplicate) else it.validationErrors,
                            errorMessageResId = if (duplicate) null else res.error.toMessageRes()
                        )
                    }
                }
                else -> _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    /** إيقاف/تفعيل/استعادة: يبقى المالك في الدليل، لكنه لا يُختار للتوليد (يُفرض في السحابة أيضاً). */
    fun setStatus(ownerId: String, status: OwnerStatus) {
        val message = when (status) {
            OwnerStatus.SUSPENDED -> R.string.owner_success_suspend
            else -> R.string.owner_success_activate
        }
        runOwnerOperation(message) { setOwnerStatusUseCase(ownerId, status) }
    }

    /** تسوية كل مستحقات المالك من رصيده (الأقدم أولاً) بقدر ما يكفي الرصيد. */
    fun settleDuesFromCredit() {
        val ws = _uiState.value.selectedOwnerWorkspace ?: return
        if (_uiState.value.isSaving) return
        runOwnerOperation(R.string.owner_success_settle) {
            settleOwnerDuesUseCase.fromCredit(ws.account.availableCreditMinor, ws.invoices).map { }
        }
    }

    /** تحصيل مبلغ من المالك وتوزيعه على فواتيره المستحقة الأقدم فالأحدث (لا يتجاوز مجموع المستحق). */
    fun collectPayment(amountMinor: Long, method: String) {
        val ws = _uiState.value.selectedOwnerWorkspace ?: return
        if (_uiState.value.isSaving) return
        runOwnerOperation(R.string.owner_success_collect) {
            settleOwnerDuesUseCase.collectCash(amountMinor, method, ws.invoices).map { }
        }
    }

    /** إلغاء دفعة مسجَّلة بالخطأ؛ دفعة «credit» يعود مبلغها للرصيد تلقائياً. */
    fun voidPayment(paymentId: String) {
        if (_uiState.value.isSaving) return
        val signature = "void|$paymentId"
        runOwnerOperation(R.string.owner_success_void_payment, signature) { voidPaymentUseCase(paymentId, "Voided from owner workspace", pendingKeys.keyFor(signature)) }
    }

    /**
     * عملية مالية واحدة في كل مرة (الضغط المتكرر يُتجاهل: كان الإيداع والرصيد الافتتاحي والإبطال بلا حارس فيُسجَّلان مرتين).
     * [signature] يربط مفتاح idempotency بالعملية المنطقية: يبقى بعد الفشل (إعادة المحاولة لا تكرر ما نجح فعلاً) ويُمسح بالنجاح.
     */
    private fun runOwnerOperation(@StringRes successResId: Int, signature: String? = null, block: suspend () -> AppResult<Unit>): Job {
        if (_uiState.value.isSaving) return Job().apply { complete() }
        return viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessageResId = null) }
            val res = try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.Error(ApiError.mapThrowable(e))
            }
            if (signature != null && res is AppResult.Success) pendingKeys.clear(signature)
            _uiState.update {
                when (res) {
                    is AppResult.Success -> it.copy(isSaving = false, successMessageResId = successResId)
                    is AppResult.Error -> it.copy(isSaving = false, errorMessageResId = res.error.toMessageRes())
                    else -> it.copy(isSaving = false)
                }
            }
        }
    }

    fun clearFieldError(field: String) { _uiState.update { it.copy(validationErrors = it.validationErrors - field) } }
    fun clearMessages() { _uiState.update { it.copy(successMessageResId = null, errorMessageResId = null) } }

    @StringRes
    private fun ApiError.toMessageRes(): Int = ErrorTranslator.resIdOf(this)
}

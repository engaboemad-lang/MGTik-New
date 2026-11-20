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
import ps.mgtik.hotspot.core.result.IdempotencyKeyProvider
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.repository.IUserRepository
import ps.mgtik.hotspot.domain.usecase.*
import ps.mgtik.hotspot.ui.util.ErrorTranslator
import javax.inject.Inject

data class UsersUiState(
    val managedUsers: List<LocalUserRecord> = emptyList(),
    val nullUsers: List<DiscoveryRecord> = emptyList(),
    val archivedUsers: List<ArchivedUserSnapshot> = emptyList(),
    /** تحميل أول صفحة من الأرشيف فقط (لا يُخفي قوائم المشتركين في S16). */
    val isLoading: Boolean = false,
    /** تحميل صفحة لاحقة من الأرشيف. */
    val isLoadingMore: Boolean = false,
    /** هل بقيت صفحات في الأرشيف لم تُحمَّل. */
    val hasMoreArchived: Boolean = false,
    /** فشل تحميل الأرشيف (يُعرض في S22 مع إعادة المحاولة؛ منفصل عن رسائل عمليات S16). */
    @StringRes val archiveErrorResId: Int? = null,
    /** لم تصل بعد أول لقطة للمشتركين (S16 يعرض مؤشر تحميل بدل «لا سجلات»). */
    val isUsersLoading: Boolean = true,
    val isSyncing: Boolean = false,
    /** عملية على مشترك (تفعيل/تعطيل/تجديد/أرشفة/ربط) قيد التنفيذ؛ تمنع الضغط المتكرر. */
    val isProcessing: Boolean = false,
    @StringRes val errorMessageResId: Int? = null,
    @StringRes val successMessageResId: Int? = null,
    /** أوامر أرشفة/تعديل لم تكتمل (Pending) أو فشلت، مفهرسة بمعرّف السجل؛ لا تُعرض أبداً كمكتملة. */
    val pendingOps: Map<String, UserPendingOperation> = emptyMap(),
    /** معرّفات السجلات المُدارة الغائبة عن الراوتر (سجل اكتشاف MISSING مفتوح). */
    val missingKeys: Set<String> = emptySet(),
    /** حفظ تعديل مستخدم جارٍ (شاشة التعديل). */
    val isEditSaving: Boolean = false,
    /** خطأ آخر محاولة حفظ تعديل (يُعرض داخل شاشة التعديل لا في S16). */
    @StringRes val editErrorResId: Int? = null,
    /** قُبل التعديل (نُفِّذ أو وُضع في الطابور)؛ تستهلكه شاشة التعديل ثم تعود. */
    val editAccepted: Boolean = false,
    /** نجح الحفظ لكن عملية الراوتر الإلزامية لم تكتمل (PENDING/FAILED): تحذير لا نجاح كامل. */
    @StringRes val warningMessageResId: Int? = null,
    /** إنشاء مستخدم فردي جارٍ (يمنع الضغط المتكرر داخل النموذج). */
    val isCreating: Boolean = false,
    /** خطأ آخر محاولة إنشاء (يُعرض داخل نموذج الإنشاء لا كرسالة عامة). */
    @StringRes val createErrorResId: Int? = null,
    /** قُبل الإنشاء (حُفظ السجل، مكتملاً أو معلّقاً)؛ تستهلكه الواجهة لإغلاق النموذج. */
    val createAccepted: Boolean = false
)

/**
 * UsersViewModel - إدارة دورة حياة المستخدم وأرشيف الحسابات السابقة (§8)
 */
@HiltViewModel
class UsersViewModel @Inject constructor(
    private val getSessionStateUseCase: GetSessionStateUseCase,
    private val userRepository: IUserRepository,
    private val listManagedUsersUseCase: ListManagedUsersUseCase,
    private val listNullUsersUseCase: ListNullUsersUseCase,
    private val enableUserUseCase: EnableUserUseCase,
    private val disableUserUseCase: DisableUserUseCase,
    private val renewUserPackageUseCase: RenewUserPackageUseCase,
    private val archiveUserUseCase: ArchiveUserUseCase,
    private val editUserUseCase: EditUserUseCase,
    private val reconcileUserRegistryUseCase: ReconcileUserRegistryUseCase,
    private val getArchivedUsersUseCase: GetArchivedUsersUseCase,
    private val createNormalUserUseCase: CreateNormalUserUseCase,
    private val createManagedSubscriberUseCase: CreateManagedSubscriberUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(UsersUiState())
    val uiState: StateFlow<UsersUiState> = _uiState.asStateFlow()

    private var archivedJob: Job? = null
    private var archiveCursor: String? = null

    private companion object {
        const val ARCHIVE_PAGE_SIZE = 30
    }

    init {
        observeManagedUsers()
        observeNullUsers()
        observePendingOperations()
        observeMissingUsers()
    }

    private fun observeMissingUsers() {
        getSessionStateUseCase.gate(onSignedOut = { _uiState.update { it.copy(missingKeys = emptySet()) } }) { userRepository.listMissingUsers() }
            .onEach { list -> _uiState.update { it.copy(missingKeys = list.mapTo(HashSet()) { d -> d.key }) } }
            .catch { /* مؤشر عرض فقط */ }
            .launchIn(viewModelScope)
    }

    private fun observePendingOperations() {
        getSessionStateUseCase.gate(onSignedOut = { _uiState.update { it.copy(pendingOps = emptyMap()) } }) { userRepository.observePendingOperations() }
            .onEach { ops -> _uiState.update { it.copy(pendingOps = ops) } }
            .catch { /* الحالة المعلّقة للعرض فقط؛ فشل مراقبتها لا يعطّل القوائم */ }
            .launchIn(viewModelScope)
    }

    private fun observeManagedUsers() {
        getSessionStateUseCase.gate(onSignedOut = { archivedJob?.cancel(); archiveCursor = null; _uiState.update { it.copy(managedUsers = emptyList(), nullUsers = emptyList(), archivedUsers = emptyList(), hasMoreArchived = false) } }) { listManagedUsersUseCase() }
            .onEach { list -> _uiState.update { it.copy(managedUsers = list, isUsersLoading = false) } }
            .catch { e -> _uiState.update { it.copy(isUsersLoading = false, errorMessageResId = ErrorTranslator.resIdOf(ApiError.mapThrowable(e))) } }
            .launchIn(viewModelScope)
    }

    private fun observeNullUsers() {
        getSessionStateUseCase.gate { listNullUsersUseCase() }
            .onEach { list -> _uiState.update { it.copy(nullUsers = list) } }
            .catch { e -> _uiState.update { it.copy(errorMessageResId = ErrorTranslator.resIdOf(ApiError.mapThrowable(e))) } }
            .launchIn(viewModelScope)
    }

    /**
     * يعيد تحميل الأرشيف من أول صفحة (يُستدعى عند فتح S22 وبعد كل أرشفة ناجحة). استدعاء جديد يلغي السابق،
     * فلا تتراكم طلبات ولا تُخلط صفحات جلسة قديمة بجديدة.
     */
    fun loadArchivedUsers() {
        archivedJob?.cancel()
        archiveCursor = null
        _uiState.update { it.copy(isLoading = true, isLoadingMore = false, archiveErrorResId = null) }
        archivedJob = viewModelScope.launch {
            when (val res = fetchArchivePage(null)) {
                is AppResult.Success -> {
                    archiveCursor = res.data.nextCursor
                    _uiState.update {
                        it.copy(
                            archivedUsers = res.data.items,
                            hasMoreArchived = res.data.nextCursor != null,
                            isLoading = false
                        )
                    }
                }
                is AppResult.Error -> _uiState.update {
                    it.copy(isLoading = false, archiveErrorResId = ErrorTranslator.resIdOf(res.error))
                }
                is AppResult.Loading -> _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /** الصفحة التالية من الأرشيف؛ لا شيء إن لم تبقَ صفحات أو كان تحميل جارياً. */
    fun loadMoreArchivedUsers() {
        val cursor = archiveCursor ?: return
        val st = _uiState.value
        if (st.isLoading || st.isLoadingMore || !st.hasMoreArchived) return
        _uiState.update { it.copy(isLoadingMore = true, archiveErrorResId = null) }
        archivedJob = viewModelScope.launch {
            when (val res = fetchArchivePage(cursor)) {
                is AppResult.Success -> {
                    archiveCursor = res.data.nextCursor
                    _uiState.update {
                        // دمج بلا تكرار (معرّف الأرشيف مفتاح القائمة في الواجهة)
                        val merged = (it.archivedUsers + res.data.items).distinctBy { a -> a.archiveId }
                        it.copy(archivedUsers = merged, hasMoreArchived = res.data.nextCursor != null, isLoadingMore = false)
                    }
                }
                is AppResult.Error -> _uiState.update {
                    it.copy(isLoadingMore = false, archiveErrorResId = ErrorTranslator.resIdOf(res.error))
                }
                is AppResult.Loading -> _uiState.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    private suspend fun fetchArchivePage(cursor: String?): AppResult<ArchivedUsersPage> =
        if (!getSessionStateUseCase().first().isAuthenticated) {
            AppResult.Success(ArchivedUsersPage(emptyList(), null))
        } else try {
            getArchivedUsersUseCase(ARCHIVE_PAGE_SIZE, cursor)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Error(ApiError.mapThrowable(e))
        }

    fun syncWithRouter() {
        if (_uiState.value.isSyncing) return
        _uiState.update { it.copy(isSyncing = true, successMessageResId = null, errorMessageResId = null) }
        viewModelScope.launch {
            val res = safe { reconcileUserRegistryUseCase() }
            _uiState.update {
                when (res) {
                    is AppResult.Success -> it.copy(isSyncing = false, successMessageResId = R.string.msg_users_synced)
                    is AppResult.Error -> it.copy(isSyncing = false, errorMessageResId = ErrorTranslator.resIdOf(res.error))
                    is AppResult.Loading -> it.copy(isSyncing = false)
                }
            }
        }
    }

    fun enableUser(recordUuid: String) = runOperation(R.string.msg_user_enabled) { enableUserUseCase(recordUuid) }

    fun disableUser(recordUuid: String) = runOperation(R.string.msg_user_disabled) { disableUserUseCase(recordUuid) }

    fun renewPackage(recordUuid: String) = runOperation(R.string.msg_user_renewed) { renewUserPackageUseCase(recordUuid) }

    /** QUEUED ⇒ رسالة «بانتظار التنفيذ» (لا «تمت»)؛ COMPLETED ⇒ رسالة النجاح. */
    fun archiveUser(recordUuid: String, reason: String) =
        runOperation(R.string.msg_user_archived, queuedResId = R.string.msg_user_archive_queued) { archiveUserUseCase(recordUuid, reason) }

    fun editUser(request: UserEditRequest) {
        if (_uiState.value.isEditSaving) return
        _uiState.update { it.copy(isEditSaving = true, editErrorResId = null, editAccepted = false) }
        viewModelScope.launch {
            val res = safe { editUserUseCase(request) }
            _uiState.update {
                when (res) {
                    is AppResult.Success -> {
                        val queued = res.data == UserOperationOutcome.QUEUED
                        it.copy(
                            isEditSaving = false,
                            editAccepted = true,
                            successMessageResId = if (queued) R.string.msg_user_edit_queued else R.string.msg_user_edited
                        )
                    }
                    is AppResult.Error -> it.copy(isEditSaving = false, editErrorResId = ErrorTranslator.resIdOf(res.error))
                    is AppResult.Loading -> it.copy(isEditSaving = false)
                }
            }
        }
    }

    /** تستهلكه شاشة التعديل بعد العودة، وتمسح خطأ المحاولة السابقة عند فتحها. */
    fun clearEditState() {
        _uiState.update { it.copy(editAccepted = false, editErrorResId = null) }
    }

    /** حذف حساب المستخدم من الراوتر (يبقى سجله السحابي «مفقوداً»). QUEUED ⇒ معلّق لا مكتمل. */
    fun deleteFromRouter(recordUuid: String) =
        runOperation(R.string.msg_user_router_deleted, queuedResId = R.string.msg_user_router_delete_queued) {
            userRepository.deleteUserFromRouter(recordUuid, IdempotencyKeyProvider.generateKey())
        }

    fun reExportToRouter(recordUuid: String) =
        runOperation(R.string.msg_user_reexported, queuedResId = R.string.msg_user_reexport_queued) {
            userRepository.reExportUserToRouter(recordUuid, IdempotencyKeyProvider.generateKey())
        }

    /** غاب الحساب عن الراوتر ⇒ يُعاد إنشاؤه من مصدره المحفوظ (يشمل المستخدم الفردي الذي لم يكتمل إنشاؤه)؛ موجود ⇒ تُعاد مزامنة حالته. */
    fun retryUserSync(recordUuid: String) = runOperation(R.string.msg_user_sync_retried) {
        userRepository.retryCreationFailedUser(recordUuid, IdempotencyKeyProvider.generateKey())
    }

    // ───────────── إنشاء مستخدم فردي (S16) ─────────────

    /**
     * مستخدم عادي فردي. SYNCED ⇒ نجاح؛ PENDING/FAILED ⇒ السجل محفوظ لكن الحساب لم يُنشأ على الراوتر بعد: تحذير صريح
     * (يظهر السجل بشارة المزامنة وزر إعادة المزامنة)، لا «تم الإنشاء». [key] يُولَّد مرة لكل فتح للنموذج (idempotency).
     */
    fun createNormalUser(draft: NewNormalUser, key: String) = runCreate {
        when (val res = safe { createNormalUserUseCase(draft, key) }) {
            is AppResult.Success -> {
                val synced = res.data.syncStatus == UserSyncStatus.SYNCED
                CreateResult.Accepted(
                    success = if (synced) R.string.msg_user_created else null,
                    warning = if (synced) null else
                        if (res.data.syncStatus == UserSyncStatus.FAILED) R.string.msg_user_created_router_failed else R.string.msg_user_created_router_pending
                )
            }
            is AppResult.Error -> CreateResult.Rejected(ErrorTranslator.resIdOf(res.error))
            is AppResult.Loading -> CreateResult.Rejected(null)
        }
    }

    /** مشترك شهري: المسار نفسه الذي تستعمله لوحة الفوترة (CreateSubscriptionUseCase) مع مالك admin؛ التنفيذ المعلّق على الراوتر يظهر تحذيراً. */
    fun createSubscriber(draft: NewSubscription, subscriptionId: String) = runCreate {
        when (val res = safe { createManagedSubscriberUseCase(draft, subscriptionId) }) {
            is AppResult.Success -> {
                val synced = res.data.execution == SubscriptionExecution.SYNCED
                CreateResult.Accepted(
                    success = if (synced) R.string.msg_subscription_created else null,
                    warning = if (synced) null else R.string.sub_msg_router_pending
                )
            }
            is AppResult.Error -> CreateResult.Rejected(ErrorTranslator.resIdOf(res.error))
            is AppResult.Loading -> CreateResult.Rejected(null)
        }
    }

    private sealed interface CreateResult {
        data class Accepted(@StringRes val success: Int?, @StringRes val warning: Int?) : CreateResult
        data class Rejected(@StringRes val errorResId: Int?) : CreateResult
    }

    private fun runCreate(block: suspend () -> CreateResult) {
        if (_uiState.value.isCreating) return
        _uiState.update { it.copy(isCreating = true, createErrorResId = null, createAccepted = false) }
        viewModelScope.launch {
            val res = block()
            _uiState.update {
                when (res) {
                    is CreateResult.Accepted -> it.copy(
                        isCreating = false, createAccepted = true,
                        successMessageResId = res.success, warningMessageResId = res.warning
                    )
                    is CreateResult.Rejected -> it.copy(isCreating = false, createErrorResId = res.errorResId ?: R.string.ERR_UNKNOWN)
                }
            }
        }
    }

    /** تستهلكه الواجهة بعد إغلاق نموذج الإنشاء، وتمسح خطأ المحاولة السابقة عند فتحه. */
    fun clearCreateState() {
        _uiState.update { it.copy(createAccepted = false, createErrorResId = null) }
    }

    fun disableNullUser(routerUsername: String) = runOperation(R.string.msg_null_user_disabled) {
        userRepository.disableNullUser(routerUsername, IdempotencyKeyProvider.generateKey())
    }

    fun deleteNullUser(routerUsername: String) = runOperation(R.string.msg_null_user_deleted) {
        userRepository.deleteNullUser(routerUsername, IdempotencyKeyProvider.generateKey())
    }

    fun adoptNullUser(routerUsername: String, ownerId: String) {
        if (ownerId.isBlank()) return
        runOperation(R.string.msg_user_adopted) {
            userRepository.adoptNullUser(routerUsername, ownerId, IdempotencyKeyProvider.generateKey())
        }
    }

    private suspend fun <T> safe(block: suspend () -> AppResult<T>): AppResult<T> = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        AppResult.Error(ApiError.mapThrowable(e))
    }

    private fun runOperation(
        @StringRes successResId: Int,
        @StringRes queuedResId: Int? = null,
        block: suspend () -> AppResult<*>
    ) {
        if (_uiState.value.isProcessing) return
        _uiState.update { it.copy(isProcessing = true, successMessageResId = null, errorMessageResId = null) }
        viewModelScope.launch {
            val res = safe(block)
            _uiState.update {
                when (res) {
                    is AppResult.Success -> it.copy(
                        isProcessing = false,
                        successMessageResId = if (queuedResId != null && res.data == UserOperationOutcome.QUEUED) queuedResId else successResId
                    )
                    is AppResult.Error -> it.copy(isProcessing = false, errorMessageResId = ErrorTranslator.resIdOf(res.error))
                    is AppResult.Loading -> it.copy(isProcessing = false)
                }
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(successMessageResId = null, errorMessageResId = null, warningMessageResId = null) }
    }
}

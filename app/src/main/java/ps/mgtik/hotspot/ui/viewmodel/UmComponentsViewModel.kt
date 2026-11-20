package ps.mgtik.hotspot.ui.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.data.userman.SharedEditChoice
import ps.mgtik.hotspot.data.userman.UmComponentType
import ps.mgtik.hotspot.data.userman.UmComponentsOverview
import ps.mgtik.hotspot.data.userman.UmLimitationSpec
import ps.mgtik.hotspot.data.userman.UmProfileSpec
import ps.mgtik.hotspot.data.userman.UmSnapshot
import ps.mgtik.hotspot.data.userman.UserManComponentService
import javax.inject.Inject

/** رسالة عابرة: مورد نصي + وسيط اختياري (رمز الخطأ أو وصف الفروق). */
data class UmMessage(@StringRes val resId: Int, val arg: String? = null, val isError: Boolean = false)

data class UmComponentsUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val overview: UmComponentsOverview? = null,
    @StringRes val loadErrorRes: Int? = null,
    val loadErrorArg: String? = null,
    val message: UmMessage? = null,
    /** اسم مكوّن عُدّل خارجياً وينتظر قرار الكتابة فوقه (الشاشة تعيد المحاولة بـ overwrite=true عند التأكيد). */
    val externalChangeFor: String? = null,
    val snapshots: List<UmSnapshot> = emptyList(),
    val snapshotsFor: Pair<UmComponentType, String>? = null,
    /** آخر عملية نجحت وأنتجت اسماً جديداً (نسخة/إعادة تسمية) لتمييزه في القائمة. */
    val lastResultName: String? = null
)

/**
 * ViewModel مشترك لشاشتي Profiles وLimitations. لا يحتوي منطق راوتر: كل شيء عبر [UserManComponentService]
 * الذي يقرأ من الراوتر ويتحقق بعد الكتابة. بعد كل عملية تُعاد قراءة الراوتر (لا نعدّل الحالة محلياً بالتخمين).
 */
@HiltViewModel
class UmComponentsViewModel @Inject constructor(
    private val service: UserManComponentService
) : ViewModel() {

    private val _state = MutableStateFlow(UmComponentsUiState())
    val uiState: StateFlow<UmComponentsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.overview == null, busy = true, loadErrorRes = null, loadErrorArg = null) }
            when (val r = service.load()) {
                is AppResult.Success -> _state.update { it.copy(loading = false, busy = false, overview = r.data) }
                is AppResult.Error -> _state.update { it.copy(loading = false, busy = false, loadErrorRes = errorRes(r.error), loadErrorArg = errorArg(r.error)) }
                else -> _state.update { it.copy(loading = false, busy = false) }
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
    fun dismissExternalChange() = _state.update { it.copy(externalChangeFor = null) }

    private fun errorRes(e: ApiError): Int = when (e.code) {
        UserManComponentService.ERR_NAME_INVALID -> R.string.um_err_name_invalid
        UserManComponentService.ERR_NAME_EXISTS -> R.string.um_err_name_exists
        UserManComponentService.ERR_NOT_FOUND -> R.string.um_err_not_found
        UserManComponentService.ERR_SNAPSHOT_FAILED -> R.string.um_err_snapshot
        UserManComponentService.ERR_VERIFY_MISMATCH -> R.string.um_err_verify
        UserManComponentService.ERR_COPY_NEEDS_TARGET -> R.string.um_err_copy_target
        UserManComponentService.ERR_DELETE_BLOCKED -> when {
            e.message.contains("PROFILE_HAS_USERS") -> R.string.um_delete_blocked_users
            e.message.contains("PROFILE_USED_BY_PACKAGES") -> R.string.um_delete_blocked_packages
            else -> R.string.um_delete_blocked_linked
        }
        "ERR_UM_LIMIT_INVALID" -> R.string.um_err_limit_invalid
        "NO_CONFIG" -> R.string.um_err_no_router
        else -> R.string.um_err_generic
    }

    private fun errorArg(e: ApiError): String = if (e.code == UserManComponentService.ERR_VERIFY_MISMATCH || e.code == "ERR_UM_LIMIT_INVALID") e.message else e.code

    /** ينفّذ عملية خدمة ويُعيد قراءة الراوتر بعدها. [onExternal] يُستدعى حين يمنع التعديل الخارجي الكتابة. */
    private fun <T> execute(successRes: Int = R.string.um_msg_saved, externalName: String? = null, block: suspend () -> AppResult<T>, name: (T) -> String? = { null }) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            when (val r = block()) {
                is AppResult.Success -> {
                    _state.update { it.copy(message = UmMessage(successRes), lastResultName = name(r.data)) }
                    refresh()
                }
                is AppResult.Error -> {
                    if (r.error.code == UserManComponentService.ERR_EXTERNAL_CHANGE && externalName != null) {
                        _state.update { it.copy(busy = false, externalChangeFor = externalName) }
                    } else {
                        _state.update { it.copy(busy = false, message = UmMessage(errorRes(r.error), errorArg(r.error), isError = true)) }
                    }
                    // حتى عند الفشل نعيد القراءة: قد يكون الراوتر تغيّر جزئياً (والسجل يوضح التراجع)
                    if (r.error.code != UserManComponentService.ERR_EXTERNAL_CHANGE) refresh()
                }
                else -> _state.update { it.copy(busy = false) }
            }
        }
    }

    // ───────────── Limitations ─────────────
    fun createLimitation(spec: UmLimitationSpec) = execute(block = { service.createLimitation(spec) }, name = { it.createdName })

    fun editLimitation(spec: UmLimitationSpec, choice: SharedEditChoice, forProfile: String?, overwrite: Boolean) =
        execute(externalName = spec.name, block = { service.editLimitation(spec, choice, forProfile, overwrite) }, name = { it.createdName })

    fun cloneLimitation(source: String, newName: String) = execute(block = { service.cloneLimitation(source, newName) }, name = { it.createdName })
    fun renameLimitation(old: String, new: String) = execute(block = { service.renameLimitation(old, new) }, name = { it.createdName })
    fun deleteLimitation(name: String, detach: Boolean, ack: Boolean) =
        execute(successRes = R.string.um_msg_deleted, block = { service.deleteLimitation(name, detach, ack) })

    // ───────────── Profiles ─────────────
    fun createProfile(spec: UmProfileSpec, limitations: List<String>) = execute(block = { service.createProfile(spec, limitations) }, name = { it.createdName })

    fun editProfile(spec: UmProfileSpec, limitations: List<String>?, overwrite: Boolean) =
        execute(externalName = spec.name, block = { service.editProfile(spec, limitations, overwrite) }, name = { it.createdName })

    fun cloneProfile(source: String, newName: String, overrides: UmProfileSpec? = null) =
        execute(block = { service.cloneProfile(source, newName, overrides) }, name = { it.createdName })
    fun renameProfile(old: String, new: String, syncNameForUsers: Boolean) = execute(block = { service.renameProfile(old, new, syncNameForUsers) }, name = { it.createdName })
    fun deleteProfile(name: String) = execute(successRes = R.string.um_msg_deleted, block = { service.deleteProfile(name) })

    // ───────────── خارجي / استعادة ─────────────
    fun importComponent(type: UmComponentType, name: String) = execute(successRes = R.string.um_msg_imported, block = { service.importComponent(type, name) })

    fun loadSnapshots(type: UmComponentType, name: String) {
        viewModelScope.launch {
            val r = service.snapshots(type, name)
            _state.update { it.copy(snapshots = (r as? AppResult.Success)?.data.orEmpty(), snapshotsFor = type to name) }
        }
    }

    fun closeSnapshots() = _state.update { it.copy(snapshots = emptyList(), snapshotsFor = null) }

    fun restore(snapshotId: String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null, snapshots = emptyList(), snapshotsFor = null) }
            when (val r = service.restore(snapshotId)) {
                is AppResult.Success -> {
                    val problems = r.data
                    _state.update { it.copy(message = if (problems.isEmpty()) UmMessage(R.string.um_msg_restored) else UmMessage(R.string.um_msg_restored_partial, problems.joinToString("; "))) }
                }
                is AppResult.Error -> _state.update { it.copy(message = UmMessage(errorRes(r.error), errorArg(r.error), isError = true)) }
                else -> Unit
            }
            refresh()
        }
    }
}

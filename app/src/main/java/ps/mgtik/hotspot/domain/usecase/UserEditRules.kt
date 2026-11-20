package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.LocalUserRecord
import ps.mgtik.hotspot.domain.model.UserEditRequest
import ps.mgtik.hotspot.domain.model.UserOrigin

/**
 * UserEditRules - قواعد نقية (بلا Android/Firebase) لتعديل سجل مستخدم مُدار.
 *
 * الحقول القابلة للتعديل هي ما في LocalUserRecord فقط (المالك، قيد المدة، قيد البيانات).
 * قاعدة المالك: يُعدَّل لمستخدم ADOPTED فقط؛ مستخدم GENERATED مرتبط بدفعة وطلب وفاتورة باسم مالكه،
 * فتغيير مالكه وحده يُفرّق بين السجل والفاتورة.
 */
object UserEditRules {

    const val ERR_NO_CHANGES = "ERR_USER_EDIT_NO_CHANGES"
    /** الحساب عُطِّل (يمنع الدخول التالي) لكن قطع الجلسة الحيّة على NAS لم يتحقق: العملية غير مكتملة وقابلة لإعادة المحاولة. */
    const val ERR_SESSION_DISCONNECT_FAILED = "ERR_USER_SESSION_DISCONNECT_FAILED"
    const val ERR_OWNER_LOCKED = "ERR_USER_EDIT_OWNER_LOCKED"
    const val ERR_OWNER_INVALID = "ERR_USER_EDIT_OWNER_INVALID"
    const val ERR_CONFLICT = "ERR_USER_EDIT_CONFLICT"
    /** للمستخدم أمر (تعديل/أرشفة) معلّق في الطابور: لا يُقبل أمر ثانٍ عليه حتى يُحسم الأول. */
    const val ERR_OPERATION_PENDING = "ERR_USER_OPERATION_PENDING"
    const val ERR_ARCHIVE_ROUTER_UNVERIFIED = "ERR_USER_ARCHIVE_ROUTER_UNVERIFIED"
    /** بعد أمر الحذف ما زال المستخدم على الراوتر. */
    const val ERR_ROUTER_DELETE_UNVERIFIED = "ERR_USER_ROUTER_DELETE_UNVERIFIED"
    /** بعد أمر التفعيل/التعطيل لم تتطابق الحالة على الراوتر. */
    const val ERR_STATE_UNVERIFIED = "ERR_USER_ROUTER_STATE_UNVERIFIED"
    /** إعادة التصدير مستحيلة: لا كلمة سر/باقة محفوظة للمستخدم (متبنّى أو كرت غير موجود في الدفعة). */
    const val ERR_REEXPORT_SOURCE_MISSING = "ERR_USER_REEXPORT_SOURCE_MISSING"
    /** المستخدم غير موجود على الراوتر: لا شيء لمزامنته (المعالجة: إعادة التصدير). */
    const val ERR_MISSING_ON_ROUTER = "ERR_USER_MISSING_ON_ROUTER"
    /** حالة المستخدم (EXPIRED) لا تحدد تعطيلاً صريحاً على الراوتر، فلا تُفرض عليه. */
    const val ERR_SYNC_NOT_APPLICABLE = "ERR_USER_SYNC_NOT_APPLICABLE"
    /** المستخدم غير المعرّف صار مُداراً (سجله موجود في السحابة): يُرفض حذفه أو تعطيله كأنه مجهول. */
    const val ERR_NULL_USER_NOW_MANAGED = "ERR_USER_NULL_NOW_MANAGED"

    /** ما الذي تغيّر فعلاً بين السجل الحالي والطلب. التعديل إداري (المالك) فقط ولا يمسّ الراوتر. */
    data class Plan(val ownerChanged: Boolean)

    fun plan(current: LocalUserRecord, request: UserEditRequest): AppResult<Plan> {
        if (request.ownerId.isBlank()) return error(ERR_OWNER_INVALID, "Owner is required")
        val ownerChanged = request.ownerId != current.ownerId
        if (ownerChanged && current.origin != UserOrigin.ADOPTED) {
            return error(ERR_OWNER_LOCKED, "Owner of a generated user cannot be changed")
        }
        if (!ownerChanged) return error(ERR_NO_CHANGES, "Nothing to change")
        return AppResult.Success(Plan(ownerChanged))
    }

    /**
     * هل تعارض المدير مع تعديل آخر؟ [expected] فارغ (سجل قديم بلا updatedAt) = لا كشف.
     * التعديل الذي طُبِّق فعلاً (القيم الحالية = القيم المطلوبة) ليس تعارضاً: إعادة تنفيذ من الطابور بعد نجاح ضاع إقراره.
     */
    fun isConflict(current: LocalUserRecord, request: UserEditRequest): Boolean {
        if (request.expectedUpdatedAt.isBlank() || current.updatedAt == request.expectedUpdatedAt) return false
        return !isAlreadyApplied(current, request)
    }

    fun isAlreadyApplied(current: LocalUserRecord, request: UserEditRequest): Boolean =
        current.ownerId == request.ownerId

    private fun error(code: String, message: String) = AppResult.Error(ApiError(code, message))
}

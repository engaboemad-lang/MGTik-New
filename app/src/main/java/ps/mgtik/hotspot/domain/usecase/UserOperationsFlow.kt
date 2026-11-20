package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.DisconnectOutcome
import ps.mgtik.hotspot.domain.model.LocalUserRecord
import ps.mgtik.hotspot.domain.model.LogSeverity
import ps.mgtik.hotspot.domain.model.RouterDisableState
import ps.mgtik.hotspot.domain.model.UserEditRequest
import ps.mgtik.hotspot.domain.model.UserRecordLookup
import ps.mgtik.hotspot.domain.repository.IAuditRepository

/**
 * منافذ التنفيذ الفعلي لعمليات المستخدم. يطبّقها UserRepositoryImpl فوق Firestore وRouterOS والكاش المحلي،
 * وتستبدلها الاختبارات بمزيّفات، فيُختبر ترتيب الخطوات (الراوتر ثم السحابة ثم الكاش) دون أي بنية تحتية.
 */
interface UserOperationPorts {
    /**
     * المصدر الحاكم هو السحابة؛ الكاش المحلي بديل فقط عند تعذّر قراءتها. تعذّر القراءة وغياب الكاش = خطأ (قابل للإعادة دون اتصال)،
     * لا NotFound: غياب السجل لا يُستنتج من انقطاع الاتصال.
     */
    suspend fun lookup(recordUuid: String): AppResult<UserRecordLookup>

    /** تعطيل المستخدم على الراوتر (disabled=yes) ثم قراءته مجدداً للتحقق؛ لا يعيد نجاحاً إلا إن تحقق التعطيل أو غاب المستخدم. */
    suspend fun disableOnRouter(routerUsername: String): AppResult<RouterDisableState>

    /** قطع كل جلسات المستخدم الحيّة على NAS والتحقق من القطع (بعد التعطيل). نتيجته منفصلة عن نتيجة التعطيل. */
    suspend fun disconnectSessions(routerUsername: String): AppResult<DisconnectOutcome>

    /** معاملة واحدة: لقطة الأرشيف + حذف السجل النشط + إغلاق سجل الاكتشاف. */
    suspend fun commitArchive(record: LocalUserRecord, reason: String, archivedBy: String?, routerState: RouterDisableState): AppResult<Unit>

    /** معاملة واحدة تفحص التعارض ثم تكتب القيم الجديدة؛ تعيد السجل بعد التحديث. */
    suspend fun commitEdit(request: UserEditRequest): AppResult<LocalUserRecord>

    /** الراوتر عُطِّل لكن الأرشفة السحابية فشلت: تُسجَّل حالة التعطيل كي لا يبقى السجل «نشطاً» وهو معطَّل فعلياً (جهد أقصى). */
    suspend fun recordDisabledWithoutArchive(record: LocalUserRecord)

    /** بعد نجاح الأرشفة: إزالة السجل من الكاش المحلي. */
    suspend fun onArchived(recordUuid: String)

    /** بعد نجاح التعديل: تحديث الكاش المحلي. */
    suspend fun onEdited(record: LocalUserRecord)

    /** هوية المدير الموثوقة (Firebase Auth uid) أو null. */
    suspend fun currentAdminId(): String?
}

/**
 * UserArchiveFlow - الأرشفة = تعطيل على الراوتر وقطع الجلسات ثم أرشفة في السحابة، بهذا الترتيب الإلزامي:
 *  1. التعطيل على الراوتر والتحقق منه. فشله يوقف كل شيء ولا يُمسّ السجل النشط.
 *  1ب. قطع الجلسات الحيّة على NAS والتحقق؛ فشله يترك الحساب معطَّلاً ويُرجع خطأً قابلاً للإعادة دون أرشفة.
 *  2. معاملة الأرشفة السحابية (لقطة كاملة + حذف السجل النشط + إغلاق الاكتشاف).
 *  3. تنظيف الكاش المحلي.
 * إعادة التنفيذ آمنة: التعطيل idempotent، ومعرّف الأرشيف = معرّف السجل فلا نسخة ثانية، والسجل المؤرشف سابقاً = نجاح.
 */
class UserArchiveFlow(
    private val ports: UserOperationPorts,
    private val audit: IAuditRepository
) {
    suspend fun run(recordUuid: String, reason: String): AppResult<Unit> {
        val cleanReason = reason.trim()
        if (cleanReason.isEmpty()) return AppResult.Error(ApiError.validationError("Archive reason is required"))

        val looked = when (val r = ports.lookup(recordUuid)) {
            is AppResult.Success -> r.data
            is AppResult.Error -> return r
            is AppResult.Loading -> return AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }
        val record = when (looked) {
            is UserRecordLookup.AlreadyArchived -> {
                ports.onArchived(recordUuid)
                return AppResult.Success(Unit)
            }
            is UserRecordLookup.NotFound ->
                return AppResult.Error(ApiError("USER_NOT_FOUND", "User record not found"))
            is UserRecordLookup.Found -> looked.record
        }
        // السجل السحابي هو المصدر الحاكم للنوع: المشترك الشهري لا يُؤرشف من هنا
        SystemClassRules.normalOpsError(record)?.let { return AppResult.Error(ApiError(it, "Subscriber is managed by its subscription")) }

        val routerState = when (val disabled = ports.disableOnRouter(record.routerUsername)) {
            is AppResult.Success -> disabled.data
            is AppResult.Error -> {
                audit.logAction("ARCHIVE_USER_FAILED", "Router disable failed for $recordUuid: ${disabled.error.code}", LogSeverity.ERROR)
                return disabled
            }
            is AppResult.Loading -> return AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }

        // الحساب معطَّل (لا إعادة اتصال)، لكن الجلسة الحيّة تبقى حتى تُقطع على NAS. فشل القطع ليس نجاحاً:
        // لا تُنفَّذ الأرشفة، يُسجَّل التعطيل فقط، وتُعاد العملية كاملة لاحقاً (التعطيل والقطع idempotent)
        val cut = ports.disconnectSessions(record.routerUsername)
        if (cut is AppResult.Error) {
            ports.recordDisabledWithoutArchive(record)
            audit.logAction("ARCHIVE_USER_FAILED", "Account disabled but session disconnect failed for $recordUuid: ${cut.error.code}", LogSeverity.ERROR)
            return AppResult.Error(ApiError(UserEditRules.ERR_SESSION_DISCONNECT_FAILED, "Account disabled but its session could not be disconnected: ${cut.error.message}", retryable = true))
        }

        val committed = ports.commitArchive(record, cleanReason, ports.currentAdminId(), routerState)
        if (committed is AppResult.Error) {
            ports.recordDisabledWithoutArchive(record)
            audit.logAction("ARCHIVE_USER_FAILED", "Cloud archive failed for $recordUuid after router disable: ${committed.error.code}", LogSeverity.ERROR)
            return committed
        }
        ports.onArchived(recordUuid)
        audit.logAction("ARCHIVE_USER_DONE", "User archived: $recordUuid (router: ${routerState.name})", LogSeverity.INFO)
        return AppResult.Success(Unit)
    }
}

/**
 * UserEditFlow - تعديل مستخدم مُدار: فحص التعارض ثم الراوتر (للقيود فقط) ثم السحابة ثم الكاش.
 * الراوتر قبل السحابة كي لا تُسجَّل في السحابة قيود لم تُطبَّق على الراوتر. فشل الراوتر لا يترك أثراً سحابياً،
 * وفشل السحابة بعد نجاح الراوتر يُعاد تنفيذه بأمان (تطبيق القيد نفسه مرتين لا يغيّر شيئاً).
 */
class UserEditFlow(
    private val ports: UserOperationPorts,
    private val audit: IAuditRepository
) {
    suspend fun run(request: UserEditRequest): AppResult<LocalUserRecord> {
        val looked = when (val r = ports.lookup(request.recordUuid)) {
            is AppResult.Success -> r.data
            is AppResult.Error -> return r
            is AppResult.Loading -> return AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }
        val record = (looked as? UserRecordLookup.Found)?.record
            ?: return AppResult.Error(ApiError("USER_NOT_FOUND", "User record not found"))
        SystemClassRules.normalOpsError(record)?.let { return AppResult.Error(ApiError(it, "Subscriber is managed by its subscription")) }

        // طُبِّق من قبل (إعادة تنفيذ من الطابور بعد نجاح ضاع إقراره): نجاح بلا أي كتابة
        if (UserEditRules.isAlreadyApplied(record, request)) {
            ports.onEdited(record)
            return AppResult.Success(record)
        }
        if (UserEditRules.isConflict(record, request)) {
            return AppResult.Error(ApiError(UserEditRules.ERR_CONFLICT, "User record changed since it was opened"))
        }
        val plan = when (val planned = UserEditRules.plan(record, request)) {
            is AppResult.Success -> planned.data
            is AppResult.Error -> return planned
            is AppResult.Loading -> return AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }

        return when (val committed = ports.commitEdit(request)) {
            is AppResult.Success -> {
                ports.onEdited(committed.data)
                audit.logAction("EDIT_USER_DONE", "User edited: ${request.recordUuid} (owner=${plan.ownerChanged})", LogSeverity.INFO)
                committed
            }
            is AppResult.Error -> {
                audit.logAction("EDIT_USER_FAILED", "Cloud update failed for ${request.recordUuid}: ${committed.error.code}", LogSeverity.ERROR)
                committed
            }
            is AppResult.Loading -> AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }
    }
}

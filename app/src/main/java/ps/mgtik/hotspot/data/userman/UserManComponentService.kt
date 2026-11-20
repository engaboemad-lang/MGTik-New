package ps.mgtik.hotspot.data.userman

import kotlinx.coroutines.flow.first
import ps.mgtik.hotspot.core.network.RouterConfig
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.data.cloud.source.CloudComponentDataSource
import ps.mgtik.hotspot.data.cloud.source.CloudProfileDataSource
import ps.mgtik.hotspot.domain.model.LogSeverity
import ps.mgtik.hotspot.domain.repository.IAuditRepository
import ps.mgtik.hotspot.domain.repository.IRouterStateManager
import javax.inject.Inject
import javax.inject.Singleton

/** صورة شاملة لمكوّنات User Manager: الراوتر (المرجع) + علاقات الباقات + المصدر وحالة التزامن. */
data class UmComponentsOverview(
    val graph: UmGraph,
    /** اسم Profile ← أسماء الباقات التجارية (في التطبيق) التي تشير إليه. */
    val packagesByProfile: Map<String, List<String>>,
    val origins: Map<String, ComponentOrigin>,
    val drift: Map<String, DriftState>
) {
    fun origin(type: UmComponentType, name: String) = origins[UmOrigin.key(type, name)] ?: UmOrigin.classify(type, name, emptyMap())
    fun drift(type: UmComponentType, name: String) = drift[UmOrigin.key(type, name)] ?: DriftState.NEVER_RECORDED
    fun limitationUsage(name: String) = UmRelations.usageOfLimitation(graph, name, packagesByProfile)
    fun profileUsage(name: String) = UmRelations.usageOfProfile(graph, name, packagesByProfile)
}

/** نتيجة عملية على مكوّن: السجل التفصيلي للخطوات مع النتيجة. */
data class UmComponentOpResult(val log: UmOpLog, val verification: UmVerification? = null, val createdName: String? = null)

/**
 * إدارة Profiles وLimitations كمكونات مستقلة فوق [UserManSource] (لا نسخة ثانية من منطق الراوتر).
 * كل عملية: الراوتر أولاً ثم قراءة بعد الكتابة للتحقق ثم السجل السحابي (المصدر/البصمة). Firestore وRoom ليسا دليل نجاح.
 */
@Singleton
class UserManComponentService @Inject constructor(
    private val userMan: UserManSource,
    private val routerState: IRouterStateManager,
    private val cloud: CloudComponentDataSource,
    private val profileCloud: CloudProfileDataSource,
    private val audit: IAuditRepository
) {
    companion object {
        const val ERR_NAME_INVALID = "ERR_UM_NAME_INVALID"
        const val ERR_NAME_EXISTS = "ERR_UM_NAME_EXISTS"
        const val ERR_NOT_FOUND = "ERR_UM_NOT_FOUND"
        const val ERR_EXTERNAL_CHANGE = "ERR_UM_EXTERNAL_CHANGE"
        const val ERR_DELETE_BLOCKED = "ERR_UM_DELETE_BLOCKED"
        const val ERR_IMPACT_NOT_ACKNOWLEDGED = "ERR_UM_IMPACT_NOT_ACKNOWLEDGED"
        const val ERR_SNAPSHOT_FAILED = "ERR_UM_SNAPSHOT_FAILED"
        const val ERR_VERIFY_MISMATCH = "ERR_ROUTER_VERIFY_MISMATCH"
        const val ERR_COPY_NEEDS_TARGET = "ERR_UM_COPY_NEEDS_TARGET"
    }

    private fun config(): RouterConfig? = routerState.status.value.config.takeIf { it.host.isNotBlank() }
    private fun noConfig() = AppResult.Error(ApiError("NO_CONFIG", "Router connection is not configured"))
    private fun <T> AppResult<T>.err(): ApiError? = (this as? AppResult.Error)?.error
    private fun unexpected() = ApiError.unexpectedClientError(IllegalStateException("Loading"))

    private suspend fun record(op: String, target: String, log: UmOpLog, ok: Boolean) {
        cloud.appendLog(op, target, ok, log.summary())
        audit.logAction("UM_COMPONENT_${op.uppercase()}", "$target ${log.summary()}".take(900), if (ok) LogSeverity.INFO else LogSeverity.WARNING)
    }

    // ───────────── القراءة ─────────────

    suspend fun load(): AppResult<UmComponentsOverview> {
        val cfg = config() ?: return noConfig()
        val graph = when (val g = userMan.readGraph(cfg)) { is AppResult.Success -> g.data; is AppResult.Error -> return g; else -> return AppResult.Error(unexpected()) }
        val packages = profileCloud.getProfiles(true, null).first().groupBy({ it.linkedProfileName }, { it.displayNameAr.ifBlank { it.linkedProfileName } })
        // السجل السحابي غير متاح ≠ فشل القراءة: نعرض المكونات بمصدر مستنتج وحالة «لم تُسجَّل»، ولا نخفي أن السجل تعذّر
        val reg = (cloud.loadRegistry() as? AppResult.Success)?.data.orEmpty()
        val origins = reg.mapValues { it.value.origin }
        val drift = buildMap {
            graph.limitations.forEach { put(UmOrigin.key(UmComponentType.LIMITATION, it.name), UmDrift.detect(reg[UmOrigin.key(UmComponentType.LIMITATION, it.name)]?.fingerprint, UmFingerprint.of(it))) }
            graph.profiles.forEach { put(UmOrigin.key(UmComponentType.PROFILE, it.name), UmDrift.detect(reg[UmOrigin.key(UmComponentType.PROFILE, it.name)]?.fingerprint, UmFingerprint.of(it))) }
        }
        return AppResult.Success(UmComponentsOverview(graph, packages, origins, drift))
    }

    // ───────────── Limitations ─────────────

    suspend fun createLimitation(spec: UmLimitationSpec): AppResult<UmComponentOpResult> {
        val cfg = config() ?: return noConfig()
        val existing = (userMan.listLimitations(cfg) as? AppResult.Success)?.data.orEmpty().map { it.name }
        UmNameRules.validate(spec.name, existing)?.let { return AppResult.Error(ApiError(if (it == UmNameRules.Problem.EXISTS) ERR_NAME_EXISTS else ERR_NAME_INVALID, "Limitation name: $it")) }
        val log = UmOpLog()
        userMan.createLimitation(cfg, spec).err()?.let { log.add(UmStepAction.FAILED, UmComponentType.LIMITATION, spec.name, it.code); record("create_limitation", spec.name, log, false); return AppResult.Error(it) }
        log.add(UmStepAction.CREATED, UmComponentType.LIMITATION, spec.name)
        return finishLimitation(cfg, spec, ComponentOrigin.MGTIK, "create_limitation", log)
    }

    private suspend fun finishLimitation(cfg: RouterConfig, spec: UmLimitationSpec, origin: ComponentOrigin, op: String, log: UmOpLog): AppResult<UmComponentOpResult> {
        val actual = (userMan.listLimitations(cfg) as? AppResult.Success)?.data?.firstOrNull { it.name == spec.name }
        val v = UmVerifier.verifyLimitation(spec, actual)
        log.add(UmStepAction.VERIFIED, UmComponentType.LIMITATION, spec.name, if (v.ok) "ok" else v.describe())
        if (actual != null) cloud.record(UmComponentType.LIMITATION, spec.name, origin, UmFingerprint.of(actual))
        record(op, spec.name, log, v.ok)
        return if (v.ok) AppResult.Success(UmComponentOpResult(log, v, spec.name))
        else AppResult.Error(ApiError(ERR_VERIFY_MISMATCH, v.describe(), retryable = true))
    }

    /**
     * تعديل Limitation. إن كان مشتركاً فعلى المستدعي عرض [UmComponentsOverview.limitationUsage] ثم تمرير الاختيار:
     * [SharedEditChoice.DIRECT] يعدّل المكوّن نفسه (يتأثر كل مرتبط)، و[SharedEditChoice.INDEPENDENT_COPY] ينشئ نسخة
     * جديدة ويربطها بـ [forProfile] وحده دون المساس ببقية الارتباطات.
     * تعديل خارجي مكتشف على الراوتر لا يُكتب فوقه إلا بـ [overwriteExternalChange]=true. لقطة قبل التعديل شرط للمتابعة.
     */
    suspend fun editLimitation(
        spec: UmLimitationSpec, choice: SharedEditChoice = SharedEditChoice.DIRECT,
        forProfile: String? = null, overwriteExternalChange: Boolean = false
    ): AppResult<UmComponentOpResult> {
        val cfg = config() ?: return noConfig()
        val ov = when (val o = load()) { is AppResult.Success -> o.data; is AppResult.Error -> return o; else -> return AppResult.Error(unexpected()) }
        val current = ov.graph.limitation(spec.name) ?: return AppResult.Error(ApiError(ERR_NOT_FOUND, "Limitation not found"))
        if (UmDrift.writeNeedsWarning(ov.drift(UmComponentType.LIMITATION, spec.name)) && !overwriteExternalChange)
            return AppResult.Error(ApiError(ERR_EXTERNAL_CHANGE, "Limitation changed on the router outside MGTik"))
        val log = UmOpLog()
        val snap = UmSnapshot(java.util.UUID.randomUUID().toString(), UmComponentType.LIMITATION, current.name, System.currentTimeMillis(), "before edit", limitation = current)
        cloud.saveSnapshot(snap).err()?.let { return AppResult.Error(ApiError(ERR_SNAPSHOT_FAILED, "Could not save a restore point: ${it.code}")) }
        if (choice == SharedEditChoice.INDEPENDENT_COPY) {
            val profile = forProfile ?: return AppResult.Error(ApiError(ERR_COPY_NEEDS_TARGET, "Independent copy needs the profile to relink"))
            val copyName = UmSharedEdit.copyName(current.name, ov.graph.limitations.map { it.name })
            val base = UmSharedEdit.cloneSpec(current, copyName)
            val copySpec = base.copy(
                rxKbps = spec.rxKbps ?: base.rxKbps, txKbps = spec.txKbps ?: base.txKbps,
                uptimeSeconds = spec.uptimeSeconds ?: base.uptimeSeconds, transferBytes = spec.transferBytes ?: base.transferBytes
            )
            userMan.createLimitation(cfg, copySpec).err()?.let { log.add(UmStepAction.FAILED, UmComponentType.LIMITATION, copyName, it.code); record("copy_limitation", current.name, log, false); return AppResult.Error(it) }
            log.add(UmStepAction.CREATED, UmComponentType.LIMITATION, copyName)
            val links = (ov.graph.profile(profile)?.limitations.orEmpty() - current.name) + copyName
            val relink = userMan.setProfileLimitations(cfg, profile, links)
            if (relink is AppResult.Error) {
                // لا نترك نسخة يتيمة: النسخة أُنشئت للتو في هذه العملية وحدها
                val undone = userMan.removeLimitation(cfg, copyName)
                log.add(UmStepAction.FAILED, UmComponentType.PROFILE, profile, relink.error.code)
                log.add(if (undone is AppResult.Success) UmStepAction.ROLLED_BACK else UmStepAction.ROLLBACK_FAILED, UmComponentType.LIMITATION, copyName)
                record("copy_limitation", current.name, log, false); return AppResult.Error(relink.error)
            }
            log.add(UmStepAction.UNLINKED, UmComponentType.LIMITATION, current.name, "<- $profile"); log.add(UmStepAction.LINKED, UmComponentType.LIMITATION, copyName, "-> $profile")
            return finishLimitation(cfg, copySpec, ComponentOrigin.MGTIK, "copy_limitation", log)
        }
        val res = userMan.updateLimitation(cfg, spec)
        res.err()?.let { log.add(UmStepAction.FAILED, UmComponentType.LIMITATION, spec.name, it.code); record("edit_limitation", spec.name, log, false); return AppResult.Error(it) }
        log.add(UmStepAction.UPDATED, UmComponentType.LIMITATION, spec.name)
        return finishLimitation(cfg, spec, ov.origin(UmComponentType.LIMITATION, spec.name), "edit_limitation", log)
    }

    suspend fun cloneLimitation(source: String, newName: String): AppResult<UmComponentOpResult> {
        val cfg = config() ?: return noConfig()
        val all = when (val l = userMan.listLimitations(cfg)) { is AppResult.Success -> l.data; is AppResult.Error -> return l; else -> return AppResult.Error(unexpected()) }
        val src = all.firstOrNull { it.name == source } ?: return AppResult.Error(ApiError(ERR_NOT_FOUND, "Limitation not found"))
        return createLimitation(UmSharedEdit.cloneSpec(src, newName))
    }

    suspend fun renameLimitation(old: String, new: String): AppResult<UmComponentOpResult> {
        val cfg = config() ?: return noConfig()
        val all = (userMan.listLimitations(cfg) as? AppResult.Success)?.data.orEmpty().map { it.name }
        UmNameRules.validate(new, all.filter { !it.equals(old, true) })?.let { return AppResult.Error(ApiError(if (it == UmNameRules.Problem.EXISTS) ERR_NAME_EXISTS else ERR_NAME_INVALID, "Limitation name: $it")) }
        val log = UmOpLog()
        when (val r = userMan.renameLimitation(cfg, old, new)) {
            is AppResult.Error -> { log.add(UmStepAction.FAILED, UmComponentType.LIMITATION, old, r.error.code); record("rename_limitation", old, log, false); return AppResult.Error(r.error) }
            is AppResult.Success -> if (!r.data) return AppResult.Error(ApiError(ERR_NOT_FOUND, "Limitation not found"))
            else -> return AppResult.Error(unexpected())
        }
        log.add(UmStepAction.UPDATED, UmComponentType.LIMITATION, new, "renamed from $old")
        val moved = (userMan.listLimitations(cfg) as? AppResult.Success)?.data?.firstOrNull { it.name == new }
        val origin = (cloud.loadRegistry() as? AppResult.Success)?.data?.get(UmOrigin.key(UmComponentType.LIMITATION, old))?.origin ?: UmOrigin.classify(UmComponentType.LIMITATION, old, emptyMap())
        cloud.forget(UmComponentType.LIMITATION, old)
        // الاسم المشتق mgtik-* لم يعد مشتقاً بعد إعادة تسميته يدوياً: يحتفظ بمصدره ويُسجَّل صراحةً
        if (moved != null) cloud.record(UmComponentType.LIMITATION, new, origin, UmFingerprint.of(moved))
        record("rename_limitation", old, log, true)
        return AppResult.Success(UmComponentOpResult(log, createdName = new))
    }

    /**
     * حذف Limitation: يُفحص أثره أولاً ([UmDeleteRules]). المرتبط بـ Profiles لا يُحذف إلا بـ [detachFromProfiles]،
     * ومع تأثير على مستخدمين/باقات يلزم [acknowledged]. لقطة تُحفظ قبل الحذف.
     */
    suspend fun deleteLimitation(name: String, detachFromProfiles: Boolean, acknowledged: Boolean): AppResult<UmComponentOpResult> {
        val cfg = config() ?: return noConfig()
        val ov = when (val o = load()) { is AppResult.Success -> o.data; is AppResult.Error -> return o; else -> return AppResult.Error(unexpected()) }
        val current = ov.graph.limitation(name)
        val verdict = UmDeleteRules.limitation(ov.limitationUsage(name), detachFromProfiles, acknowledged, exists = current != null)
        when (verdict.status) {
            DeleteStatus.BLOCKED -> return AppResult.Error(ApiError(ERR_DELETE_BLOCKED, verdict.blockers.joinToString(",")))
            DeleteStatus.NEEDS_ACKNOWLEDGEMENT -> return AppResult.Error(ApiError(ERR_IMPACT_NOT_ACKNOWLEDGED, "Acknowledge the impact first"))
            DeleteStatus.ALLOWED -> Unit
        }
        val log = UmOpLog()
        cloud.saveSnapshot(UmSnapshot(java.util.UUID.randomUUID().toString(), UmComponentType.LIMITATION, name, System.currentTimeMillis(), "before delete", limitation = current))
            .err()?.let { return AppResult.Error(ApiError(ERR_SNAPSHOT_FAILED, "Could not save a restore point: ${it.code}")) }
        verdict.usage.profiles.forEach { log.add(UmStepAction.UNLINKED, UmComponentType.LIMITATION, name, "<- $it") }
        userMan.removeLimitation(cfg, name).err()?.let { log.add(UmStepAction.FAILED, UmComponentType.LIMITATION, name, it.code); record("delete_limitation", name, log, false); return AppResult.Error(it) }
        log.add(UmStepAction.REMOVED, UmComponentType.LIMITATION, name)
        val still = (userMan.listLimitations(cfg) as? AppResult.Success)?.data?.any { it.name == name }
        if (still == true) { log.add(UmStepAction.FAILED, UmComponentType.LIMITATION, name, "still on router"); record("delete_limitation", name, log, false); return AppResult.Error(ApiError(ERR_VERIFY_MISMATCH, "Limitation still exists on the router", retryable = true)) }
        cloud.forget(UmComponentType.LIMITATION, name)
        record("delete_limitation", name, log, true)
        return AppResult.Success(UmComponentOpResult(log))
    }

    // ───────────── Profiles ─────────────

    suspend fun createProfile(spec: UmProfileSpec, limitations: List<String> = emptyList()): AppResult<UmComponentOpResult> {
        val cfg = config() ?: return noConfig()
        val existing = (userMan.listProfiles(cfg) as? AppResult.Success)?.data.orEmpty().map { it.name }
        UmNameRules.validate(spec.name, existing)?.let { return AppResult.Error(ApiError(if (it == UmNameRules.Problem.EXISTS) ERR_NAME_EXISTS else ERR_NAME_INVALID, "Profile name: $it")) }
        val log = UmOpLog()
        userMan.createProfile(cfg, spec).err()?.let { log.add(UmStepAction.FAILED, UmComponentType.PROFILE, spec.name, it.code); record("create_profile", spec.name, log, false); return AppResult.Error(it) }
        log.add(UmStepAction.CREATED, UmComponentType.PROFILE, spec.name)
        if (limitations.isNotEmpty()) {
            val r = userMan.setProfileLimitations(cfg, spec.name, limitations)
            if (r is AppResult.Error) {
                // Profile جديد في هذه العملية: يُزال كي لا يبقى بلا روابط المطلوبة؛ الـ Limitations الموجودة لا تُمسّ
                val undone = userMan.removeProfile(cfg, spec.name)
                log.add(UmStepAction.FAILED, UmComponentType.PROFILE, spec.name, r.error.code)
                log.add(if (undone is AppResult.Success) UmStepAction.ROLLED_BACK else UmStepAction.ROLLBACK_FAILED, UmComponentType.PROFILE, spec.name)
                record("create_profile", spec.name, log, false); return AppResult.Error(r.error)
            }
            limitations.forEach { log.add(UmStepAction.LINKED, UmComponentType.LIMITATION, it, "-> ${spec.name}") }
        }
        return finishProfile(cfg, spec, limitations.takeIf { it.isNotEmpty() }, ComponentOrigin.MGTIK, "create_profile", log)
    }

    private suspend fun finishProfile(cfg: RouterConfig, spec: UmProfileSpec, links: List<String>?, origin: ComponentOrigin, op: String, log: UmOpLog): AppResult<UmComponentOpResult> {
        val actual = (userMan.listProfiles(cfg) as? AppResult.Success)?.data?.firstOrNull { it.name == spec.name }
        val base = UmVerifier.verifyProfile(spec, actual)
        val linkProblem = if (links != null && actual != null && actual.limitations.toSet() != links.toSet()) listOf(UmMismatch("limitations", links.joinToString(","), actual.limitations.joinToString(","))) else emptyList()
        val v = UmVerification(base.mismatches + linkProblem)
        log.add(UmStepAction.VERIFIED, UmComponentType.PROFILE, spec.name, if (v.ok) "ok" else v.describe())
        if (actual != null) cloud.record(UmComponentType.PROFILE, spec.name, origin, UmFingerprint.of(actual))
        record(op, spec.name, log, v.ok)
        return if (v.ok) AppResult.Success(UmComponentOpResult(log, v, spec.name)) else AppResult.Error(ApiError(ERR_VERIFY_MISMATCH, v.describe(), retryable = true))
    }

    /** تعديل Profile مستقل (دون فتح أي باقة). [limitations] = null لا تغيير في الروابط، وإلا القائمة الدقيقة. */
    suspend fun editProfile(spec: UmProfileSpec, limitations: List<String>? = null, overwriteExternalChange: Boolean = false): AppResult<UmComponentOpResult> {
        val cfg = config() ?: return noConfig()
        val ov = when (val o = load()) { is AppResult.Success -> o.data; is AppResult.Error -> return o; else -> return AppResult.Error(unexpected()) }
        val current = ov.graph.profile(spec.name) ?: return AppResult.Error(ApiError(ERR_NOT_FOUND, "Profile not found"))
        if (UmDrift.writeNeedsWarning(ov.drift(UmComponentType.PROFILE, spec.name)) && !overwriteExternalChange)
            return AppResult.Error(ApiError(ERR_EXTERNAL_CHANGE, "Profile changed on the router outside MGTik"))
        cloud.saveSnapshot(UmSnapshot(java.util.UUID.randomUUID().toString(), UmComponentType.PROFILE, current.name, System.currentTimeMillis(), "before edit", profile = current))
            .err()?.let { return AppResult.Error(ApiError(ERR_SNAPSHOT_FAILED, "Could not save a restore point: ${it.code}")) }
        val log = UmOpLog()
        userMan.updateProfile(cfg, spec).err()?.let { log.add(UmStepAction.FAILED, UmComponentType.PROFILE, spec.name, it.code); record("edit_profile", spec.name, log, false); return AppResult.Error(it) }
        log.add(UmStepAction.UPDATED, UmComponentType.PROFILE, spec.name)
        if (limitations != null) {
            when (val r = userMan.setProfileLimitations(cfg, spec.name, limitations)) {
                is AppResult.Error -> {
                    // رجوع إلى اللقطة التي حُفظت قبل هذا التعديل: لا حالة نصف معدّلة
                    val back = userMan.restoreSnapshot(cfg, UmSnapshot("rollback", UmComponentType.PROFILE, current.name, 0L, "rollback", profile = current))
                    log.add(UmStepAction.FAILED, UmComponentType.PROFILE, spec.name, r.error.code)
                    log.add(if (back is AppResult.Success) UmStepAction.ROLLED_BACK else UmStepAction.ROLLBACK_FAILED, UmComponentType.PROFILE, spec.name)
                    record("edit_profile", spec.name, log, false); return AppResult.Error(r.error)
                }
                is AppResult.Success -> { r.data.added.forEach { log.add(UmStepAction.LINKED, UmComponentType.LIMITATION, it, "-> ${spec.name}") }; r.data.removed.forEach { log.add(UmStepAction.UNLINKED, UmComponentType.LIMITATION, it, "<- ${spec.name}") } }
                else -> Unit
            }
        }
        return finishProfile(cfg, spec, limitations, ov.origin(UmComponentType.PROFILE, spec.name), "edit_profile", log)
    }

    /**
     * نسخة Profile (للنسخ العادي أو للتعديل المستقل لباقة تشارك Profile): نفس الإعدادات والروابط باسم جديد.
     * المستدعي يعيد ربط الباقة بالاسم الجديد عبر مسار مرجع الباقة الحالي؛ المستخدمون القائمون لا يُنقلون (تجديد في UM).
     */
    suspend fun cloneProfile(source: String, newName: String, overrides: UmProfileSpec? = null): AppResult<UmComponentOpResult> {
        val cfg = config() ?: return noConfig()
        val all = when (val p = userMan.listProfiles(cfg)) { is AppResult.Success -> p.data; is AppResult.Error -> return p; else -> return AppResult.Error(unexpected()) }
        val src = all.firstOrNull { it.name == source } ?: return AppResult.Error(ApiError(ERR_NOT_FOUND, "Profile not found"))
        val base = UmSharedEdit.cloneSpec(src, newName)
        val spec = overrides?.let { o ->
            base.copy(
                nameForUsers = o.nameForUsers ?: base.nameForUsers,
                validitySeconds = if (o.validityUnlimited || o.validitySeconds != null) o.validitySeconds else base.validitySeconds,
                validityUnlimited = if (o.validityUnlimited || o.validitySeconds != null) o.validityUnlimited else base.validityUnlimited,
                sharedUsers = if (o.sharedUnlimited || o.sharedUsers != null) o.sharedUsers else base.sharedUsers,
                sharedUnlimited = if (o.sharedUnlimited || o.sharedUsers != null) o.sharedUnlimited else base.sharedUnlimited,
                startMode = o.startMode ?: base.startMode
            )
        } ?: base
        return createProfile(spec, src.limitations)
    }

    suspend fun renameProfile(old: String, new: String, syncNameForUsers: Boolean = false): AppResult<UmComponentOpResult> {
        val cfg = config() ?: return noConfig()
        val ov = when (val o = load()) { is AppResult.Success -> o.data; is AppResult.Error -> return o; else -> return AppResult.Error(unexpected()) }
        UmNameRules.validate(new, ov.graph.profiles.map { it.name }.filter { !it.equals(old, true) })?.let { return AppResult.Error(ApiError(if (it == UmNameRules.Problem.EXISTS) ERR_NAME_EXISTS else ERR_NAME_INVALID, "Profile name: $it")) }
        // الباقات التجارية تشير إلى الاسم: إعادة التسمية من هنا تُبقي الباقات يتيمة، لذا تُحجب حتى تُعالَج (المسار الرسمي: تغيير مرجع الباقة)
        if (ov.profileUsage(old).packages.isNotEmpty()) return AppResult.Error(ApiError(ERR_DELETE_BLOCKED, "PROFILE_USED_BY_PACKAGES"))
        val log = UmOpLog()
        when (val r = userMan.renameProfile(cfg, old, new, syncNameForUsers)) {
            is AppResult.Error -> { log.add(UmStepAction.FAILED, UmComponentType.PROFILE, old, r.error.code); record("rename_profile", old, log, false); return AppResult.Error(r.error) }
            is AppResult.Success -> if (!r.data) return AppResult.Error(ApiError(ERR_NOT_FOUND, "Profile not found"))
            else -> return AppResult.Error(unexpected())
        }
        log.add(UmStepAction.UPDATED, UmComponentType.PROFILE, new, "renamed from $old")
        val origin = ov.origin(UmComponentType.PROFILE, old)
        cloud.forget(UmComponentType.PROFILE, old)
        (userMan.listProfiles(cfg) as? AppResult.Success)?.data?.firstOrNull { it.name == new }?.let { cloud.record(UmComponentType.PROFILE, new, origin, UmFingerprint.of(it)) }
        record("rename_profile", old, log, true)
        return AppResult.Success(UmComponentOpResult(log, createdName = new))
    }

    suspend fun deleteProfile(name: String): AppResult<UmComponentOpResult> {
        val cfg = config() ?: return noConfig()
        val ov = when (val o = load()) { is AppResult.Success -> o.data; is AppResult.Error -> return o; else -> return AppResult.Error(unexpected()) }
        val current = ov.graph.profile(name)
        val verdict = UmDeleteRules.profile(ov.profileUsage(name), exists = current != null)
        if (!verdict.allowed) return AppResult.Error(ApiError(ERR_DELETE_BLOCKED, verdict.blockers.joinToString(",")))
        cloud.saveSnapshot(UmSnapshot(java.util.UUID.randomUUID().toString(), UmComponentType.PROFILE, name, System.currentTimeMillis(), "before delete", profile = current))
            .err()?.let { return AppResult.Error(ApiError(ERR_SNAPSHOT_FAILED, "Could not save a restore point: ${it.code}")) }
        val log = UmOpLog()
        current!!.limitations.forEach { log.add(UmStepAction.UNLINKED, UmComponentType.LIMITATION, it, "<- $name") }
        userMan.removeProfile(cfg, name).err()?.let { log.add(UmStepAction.FAILED, UmComponentType.PROFILE, name, it.code); record("delete_profile", name, log, false); return AppResult.Error(it) }
        log.add(UmStepAction.REMOVED, UmComponentType.PROFILE, name)
        if ((userMan.listProfiles(cfg) as? AppResult.Success)?.data?.any { it.name == name } == true) {
            log.add(UmStepAction.FAILED, UmComponentType.PROFILE, name, "still on router"); record("delete_profile", name, log, false)
            return AppResult.Error(ApiError(ERR_VERIFY_MISMATCH, "Profile still exists on the router", retryable = true))
        }
        cloud.forget(UmComponentType.PROFILE, name)
        record("delete_profile", name, log, true)
        return AppResult.Success(UmComponentOpResult(log))
    }

    // ───────────── مكونات خارجية ─────────────

    /** يستورد مكوّناً موجوداً على الراوتر إلى إدارة MGTik: يُسجَّل مصدره IMPORTED وبصمته الحالية، ولا يُلمس على الراوتر. */
    suspend fun importComponent(type: UmComponentType, name: String): AppResult<Unit> {
        val cfg = config() ?: return noConfig()
        val g = when (val r = userMan.readGraph(cfg)) { is AppResult.Success -> r.data; is AppResult.Error -> return r; else -> return AppResult.Error(unexpected()) }
        val fp = when (type) {
            UmComponentType.LIMITATION -> g.limitation(name)?.let { UmFingerprint.of(it) }
            UmComponentType.PROFILE -> g.profile(name)?.let { UmFingerprint.of(it) }
        } ?: return AppResult.Error(ApiError(ERR_NOT_FOUND, "Component not found on the router"))
        val res = cloud.record(type, name, ComponentOrigin.IMPORTED, fp)
        if (res is AppResult.Success) audit.logAction("UM_COMPONENT_IMPORT", "${type.name.lowercase()}=$name", LogSeverity.INFO)
        return res
    }

    // ───────────── الاستعادة ─────────────

    suspend fun snapshots(type: UmComponentType, name: String): AppResult<List<UmSnapshot>> = cloud.listSnapshots(type, name)

    /** يعيد المكوّن إلى لقطة. تُحفظ لقطة بالحالة الحالية أولاً كي تكون الاستعادة نفسها قابلة للتراجع. */
    suspend fun restore(snapshotId: String): AppResult<List<String>> {
        val cfg = config() ?: return noConfig()
        val snap = when (val s = cloud.getSnapshot(snapshotId)) { is AppResult.Success -> s.data ?: return AppResult.Error(ApiError(ERR_NOT_FOUND, "Snapshot not found")); is AppResult.Error -> return s; else -> return AppResult.Error(unexpected()) }
        val g = (userMan.readGraph(cfg) as? AppResult.Success)?.data
        val now = when (snap.type) {
            UmComponentType.LIMITATION -> g?.limitation(snap.name)?.let { UmSnapshot(java.util.UUID.randomUUID().toString(), snap.type, snap.name, System.currentTimeMillis(), "before restore", limitation = it) }
            UmComponentType.PROFILE -> g?.profile(snap.name)?.let { UmSnapshot(java.util.UUID.randomUUID().toString(), snap.type, snap.name, System.currentTimeMillis(), "before restore", profile = it) }
        }
        now?.let { cloud.saveSnapshot(it).err()?.let { e -> return AppResult.Error(ApiError(ERR_SNAPSHOT_FAILED, "Could not save a restore point: ${e.code}")) } }
        val log = UmOpLog()
        val res = userMan.restoreSnapshot(cfg, snap)
        if (res is AppResult.Error) { log.add(UmStepAction.FAILED, snap.type, snap.name, res.error.code); record("restore", snap.name, log, false); return res }
        log.add(UmStepAction.UPDATED, snap.type, snap.name, "restored ${snap.id}")
        val g2 = (userMan.readGraph(cfg) as? AppResult.Success)?.data
        when (snap.type) {
            UmComponentType.LIMITATION -> g2?.limitation(snap.name)?.let { cloud.record(snap.type, snap.name, ComponentOrigin.MGTIK, UmFingerprint.of(it)) }
            UmComponentType.PROFILE -> g2?.profile(snap.name)?.let { cloud.record(snap.type, snap.name, ComponentOrigin.MGTIK, UmFingerprint.of(it)) }
        }
        record("restore", snap.name, log, true)
        return res
    }
}

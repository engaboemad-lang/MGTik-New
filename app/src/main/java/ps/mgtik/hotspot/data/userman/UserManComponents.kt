package ps.mgtik.hotspot.data.userman

import java.security.MessageDigest

/**
 * مكونات User Manager كيانات مستقلة: Limitation وProfile (الباقة التجارية Package تشير إلى Profile بالاسم فقط).
 * كل ما في هذا الملف نقي (بلا شبكة ولا Android): النماذج، المواصفات، قواعد العلاقات والحذف والتعديل المشترك،
 * التصنيف حسب المصدر، بصمة الإعدادات لاكتشاف التعديل الخارجي، سجل العملية، ولقطات الاستعادة.
 * المرجع التشغيلي دائماً ما يُقرأ من الراوتر؛ لا شيء هنا يُعدّ دليلاً على نجاح التنفيذ.
 */

/** بادئة Limitations التي يشتقها التطبيق من اسم الباقة (توافق مع الباقات القديمة). */
const val UM_LIMITATION_PREFIX = "mgtik-"

enum class UmComponentType { PROFILE, LIMITATION }

/**
 * كيف يتعامل تنفيذ الباقة مع مكوّن:
 * - [MANAGE]: التطبيق ينشئه أو يحدّث قيمه.
 * - [REFERENCE]: مكوّن موجود (مشترك/خارجي) لا يُكتب عليه إطلاقاً؛ يُتحقق من وجوده ويُربط فقط.
 */
enum class UmComponentMode { MANAGE, REFERENCE }

/** مصدر المكوّن: أنشأه MGTik، أو أنشأه المدير مباشرة على RouterOS، أو استوردناه إلى إدارة التطبيق. */
enum class ComponentOrigin { MGTIK, EXTERNAL, IMPORTED }

/** الحقول التي يكتبها تنفيذ الباقة فعلياً. ما ليس فيها يبقى على الراوتر كما هو (لا قيمة افتراضية تُكتب بصمت). */
enum class UmWrite {
    SPEED, SHARED_USERS, VALIDITY, START_MODE, UPTIME_LIMIT, TRANSFER_LIMIT, DOWNLOAD_LIMIT, UPLOAD_LIMIT, BURST,
    PRIORITY, RESET_INTERVAL, SCHEDULE, PRICE;

    companion object {
        val ALL: Set<UmWrite> = entries.toSet()
    }
}

/** Limitation كما قرأناه من الراوتر. null في الوقت/النقل = بلا حد. */
data class UmLimitation(
    val name: String,
    val rxKbps: Int,
    val txKbps: Int,
    val uptimeSeconds: Long?,
    val transferBytes: Long?,
    /** حد تنزيل/رفع إجمالي منفصل (download-limit / upload-limit) بالبايت؛ null = بلا حد. */
    val downloadBytes: Long? = null,
    val uploadBytes: Long? = null,
    /** Burst لكل اتجاه (rx رفع، tx تنزيل)؛ كل الأصفار = معطَّل. */
    val burstRx: UmBurstDir = UmBurstDir(),
    val burstTx: UmBurstDir = UmBurstDir(),
    /** أولوية الحركة 1..8 (rate-limit-priority)؛ null = غير مقروءة/غير مضبوطة. 8 = الافتراضي. */
    val priority: Int? = null,
    /** فترة التصفير الدوري؛ null = غير مقروءة أو قيمة زمنية مخصّصة غير مدعومة (لا تُعامل كمعطّلة). */
    val resetInterval: UmResetInterval? = null
)

/**
 * Profile كما قرأناه من الراوتر مع روابطه (profile-limitation) بترتيب الراوتر.
 * [validitySeconds] = null مع [validityUnlimited]=true تعني صلاحية غير محدودة (0 في RouterOS)؛ null مع false = غير مقروءة.
 * [startMode] = null إن لم يُقرأ الحقل أو قيمته غير معروفة.
 */
data class UmProfile(
    val name: String,
    val nameForUsers: String,
    val validitySeconds: Long?,
    val validityUnlimited: Boolean,
    val sharedUsers: Int,
    val sharedUnlimited: Boolean,
    val startMode: UmStartMode?,
    val limitations: List<String>,
    /** جدول كل رابط (اسم Limitation ← نافذة التشغيل) كما يقرؤه الراوتر؛ رابط بلا قيد = ALWAYS. */
    val schedules: Map<String, UmSchedule> = emptyMap(),
    /** سعر الباقة في User Manager بالوحدة الصغرى (price × 100)؛ null = غير مقروء. */
    val priceMinor: Long? = null
)

/**
 * مواصفة Limitation مستقلة. null = لا يُلمس الحقل عند التعديل (ولا يُرسل عند الإنشاء)؛
 * في الوقت والنقل: 0 = إزالة الحد صراحةً، >0 = القيمة (ثوانٍ/بايت).
 */
data class UmLimitationSpec(
    val name: String,
    val rxKbps: Int? = null,
    val txKbps: Int? = null,
    val uptimeSeconds: Long? = null,
    val transferBytes: Long? = null,
    /** نفس دلالة [transferBytes]: null = لا يُلمس، 0 = إزالة صريحة، >0 = بايت. */
    val downloadBytes: Long? = null,
    val uploadBytes: Long? = null,
    /** null = لا يُلمس؛ [UmBurstDir] بأصفار = تعطيل صريح لـ Burst هذا الاتجاه. */
    val burstRx: UmBurstDir? = null,
    val burstTx: UmBurstDir? = null,
    /** 1..8؛ null = لا يُلمس. إعادة الأولوية العادية = [UmLimitRules.PRIORITY_DEFAULT] صراحةً. */
    val priority: Int? = null,
    /** null = لا يُلمس؛ [UmResetInterval.DISABLED] = تعطيل صريح. v6 لا يدعمه: انظر [BaseDialect.supportsResetInterval]. */
    val resetInterval: UmResetInterval? = null,
    /** نص تاريخ بدء احتساب الفترة بصيغة RouterOS؛ null = يُترك للراوتر. */
    val resetStartTime: String? = null
)

/** مواصفة Profile مستقلة (لا علاقة لها بالباقة التجارية ولا بـ Limitations). null = لا يُلمس. */
data class UmProfileSpec(
    val name: String,
    val nameForUsers: String? = null,
    val validitySeconds: Long? = null,
    val validityUnlimited: Boolean = false,
    val sharedUsers: Int? = null,
    val sharedUnlimited: Boolean = false,
    val startMode: UmStartMode? = null,
    /** سعر Profile بالوحدة الصغرى (يُكتب price بالوحدة الكبيرة بخانتين)؛ null = لا يُلمس. */
    val priceMinor: Long? = null
)

/** نتيجة تعديل مجموعة روابط Profile ← Limitations. */
data class UmLinkChange(val added: List<String>, val removed: List<String>) {
    val changed: Boolean get() = added.isNotEmpty() || removed.isNotEmpty()
}

/** صورة كاملة من الراوتر تكفي لحساب العلاقات: Profiles وLimitations والمستخدمون المرتبطون بكل Profile. */
data class UmGraph(
    val profiles: List<UmProfile>,
    val limitations: List<UmLimitation>,
    val usersByProfile: Map<String, List<String>>
) {
    fun profile(name: String): UmProfile? = profiles.firstOrNull { it.name == name }
    fun limitation(name: String): UmLimitation? = limitations.firstOrNull { it.name == name }
}

// ───────────────────────────── سجل العملية ─────────────────────────────

enum class UmStepAction { CREATED, UPDATED, LINKED, UNLINKED, REMOVED, REUSED, VERIFIED, ROLLED_BACK, ROLLBACK_FAILED, FAILED }

data class UmOpEntry(val action: UmStepAction, val type: UmComponentType?, val target: String, val detail: String = "")

/** سجل عملية متعددة الخطوات: يُمرَّر للمنفّذ ويُقرأ بعد النجاح أو الفشل (يبقى متاحاً حتى عند رمي الاستثناء). */
class UmOpLog {
    private val items = mutableListOf<UmOpEntry>()
    val entries: List<UmOpEntry> get() = items.toList()
    fun add(action: UmStepAction, type: UmComponentType?, target: String, detail: String = "") {
        items += UmOpEntry(action, type, target, detail)
    }

    val failed: Boolean get() = items.any { it.action == UmStepAction.FAILED }
    val rolledBack: Boolean get() = items.any { it.action == UmStepAction.ROLLED_BACK }
    val rollbackIncomplete: Boolean get() = items.any { it.action == UmStepAction.ROLLBACK_FAILED }

    /** سطر مضغوط للتدقيق/السجل: action:type:target;... */
    fun summary(): String = items.joinToString("; ") {
        "${it.action.name.lowercase()}:${it.type?.name?.lowercase() ?: "-"}:${it.target}" + if (it.detail.isNotBlank()) "(${it.detail.take(60)})" else ""
    }
}

// ───────────────────────────── التحقق بعد الكتابة ─────────────────────────────

data class UmMismatch(val field: String, val expected: String, val actual: String)

/** نتيجة مقارنة مواصفة الباقة بما قُرئ فعلياً من الراوتر. */
data class UmVerification(val mismatches: List<UmMismatch>) {
    val ok: Boolean get() = mismatches.isEmpty()
    fun describe(): String = mismatches.joinToString("; ") { "${it.field}: expected ${it.expected}, router has ${it.actual}" }
}

object UmVerifier {
    /**
     * يقارن [spec] بقراءة الراوتر ([profile] و[limits] مقروءان بعد الكتابة). يتحقق فقط من الحقول التي كُتبت فعلاً
     * ([UmPackageSpec.willWrite]) ومن المكوّنات التي يديرها التطبيق؛ المكوّن المرجعي يُتحقق من وجوده وربطه فقط.
     */
    fun verify(spec: UmPackageSpec, profile: UmProfile?, limits: Map<String, UmLimitation>): UmVerification {
        val out = mutableListOf<UmMismatch>()
        if (profile == null) return UmVerification(listOf(UmMismatch("profile", spec.name, "missing")))
        if (spec.profileMode == UmComponentMode.MANAGE) {
            if (spec.willWrite(UmWrite.VALIDITY)) {
                when {
                    spec.validityUnlimited ->
                        if (!profile.validityUnlimited) out += UmMismatch("validity", "unlimited", profile.validitySeconds?.toString() ?: "unreadable")
                    (spec.validitySeconds ?: 0L) > 0L ->
                        if (profile.validitySeconds != spec.validitySeconds) out += UmMismatch("validity", "${spec.validitySeconds}s", profile.validitySeconds?.let { "${it}s" } ?: if (profile.validityUnlimited) "unlimited" else "unreadable")
                }
            }
            if (spec.willWrite(UmWrite.SHARED_USERS)) {
                if (spec.sharedUnlimited) {
                    if (!profile.sharedUnlimited) out += UmMismatch("shared-users", "unlimited", profile.sharedUsers.toString())
                } else {
                    val want = spec.sharedUsers.coerceAtLeast(1)
                    if (profile.sharedUnlimited || profile.sharedUsers != want) out += UmMismatch("shared-users", want.toString(), if (profile.sharedUnlimited) "unlimited" else profile.sharedUsers.toString())
                }
            }
            if (spec.willWrite(UmWrite.START_MODE) && profile.startMode != null && profile.startMode != spec.startMode) {
                out += UmMismatch("validity-start", spec.startMode.name, profile.startMode.name)
            }
            // السعر: يُقارَن فقط إن أعاده الراوتر؛ حقل غير مقروء لا يُعدّ فشلاً (لا نملك دليلاً عليه)
            spec.priceMinor?.takeIf { spec.willWrite(UmWrite.PRICE) }?.let { want ->
                if (profile.priceMinor != null && profile.priceMinor != want) out += UmMismatch("price", UmUnits.formatPrice(want), UmUnits.formatPrice(profile.priceMinor))
            }
        }
        val wanted = spec.effectiveLimitations()
        val linked = profile.limitations
        val missing = wanted.filter { it !in linked }
        if (missing.isNotEmpty()) out += UmMismatch("limitations", wanted.joinToString(","), linked.joinToString(",").ifEmpty { "none" })
        if (spec.limitationNames.isNotEmpty()) {
            val extra = linked.filter { it !in wanted }
            if (extra.isNotEmpty()) out += UmMismatch("limitations", wanted.joinToString(","), linked.joinToString(","))
        }
        spec.schedule?.takeIf { spec.willWrite(UmWrite.SCHEDULE) }?.let { want ->
            wanted.filter { it in linked }.forEach { lim ->
                val got = profile.schedules[lim] ?: UmSchedule.ALWAYS
                if (got != want) out += UmMismatch("schedule:$lim", describe(want), describe(got))
            }
        }
        if (spec.limitationMode == UmComponentMode.MANAGE) {
            val primary = wanted.firstOrNull()?.let { limits[it] }
            if (primary == null) {
                out += UmMismatch("limitation", wanted.firstOrNull().orEmpty(), "missing")
            } else {
                if (spec.willWrite(UmWrite.SPEED)) {
                    if (primary.rxKbps != spec.rxKbps.coerceAtLeast(0)) out += UmMismatch("rate-limit-rx", "${spec.rxKbps}k", "${primary.rxKbps}k")
                    if (primary.txKbps != spec.txKbps.coerceAtLeast(0)) out += UmMismatch("rate-limit-tx", "${spec.txKbps}k", "${primary.txKbps}k")
                }
                spec.uptimeSeconds?.takeIf { spec.willWrite(UmWrite.UPTIME_LIMIT) }?.let { want ->
                    val w = want.takeIf { it > 0 }
                    if (primary.uptimeSeconds != w) out += UmMismatch("uptime-limit", w?.toString() ?: "none", primary.uptimeSeconds?.toString() ?: "none")
                }
                spec.transferBytes?.takeIf { spec.willWrite(UmWrite.TRANSFER_LIMIT) }?.let { want ->
                    val w = want.takeIf { it > 0 }
                    if (primary.transferBytes != w) out += UmMismatch("transfer-limit", w?.toString() ?: "none", primary.transferBytes?.toString() ?: "none")
                }
                out += verifyExtras(
                    spec.downloadBytes.takeIf { spec.willWrite(UmWrite.DOWNLOAD_LIMIT) },
                    spec.uploadBytes.takeIf { spec.willWrite(UmWrite.UPLOAD_LIMIT) },
                    spec.burstRx.takeIf { spec.willWrite(UmWrite.BURST) },
                    spec.burstTx.takeIf { spec.willWrite(UmWrite.BURST) },
                    primary
                )
                out += verifyAdvanced(
                    spec.priority.takeIf { spec.willWrite(UmWrite.PRIORITY) },
                    spec.resetInterval.takeIf { spec.willWrite(UmWrite.RESET_INTERVAL) && wantsResetCheck(spec) },
                    primary
                )
            }
        }
        return UmVerification(out)
    }

    /** تفعيل أو تعطيل التصفير يُتحقق منه؛ لكن v6 لا يكتبه أصلاً فلا معنى لمقارنته هناك (يُحدَّد بأن القيمة المقروءة null). */
    private fun wantsResetCheck(spec: UmPackageSpec) = spec.resetInterval != null

    private fun describe(s: UmSchedule) = "${UmSchedule.weekdaysWire(s.weekdays)} ${UmSchedule.clock(s.fromSeconds)}-${UmSchedule.clock(s.tillSeconds)}"

    /** الأولوية والتصفير الدوري. تصفير غير مقروء (null) لا يُعدّ فشلاً حين المطلوب DISABLED (الافتراضي)، ويُعدّ فشلاً حين المطلوب تفعيله. */
    internal fun verifyAdvanced(priority: Int?, reset: UmResetInterval?, actual: UmLimitation): List<UmMismatch> = buildList {
        priority?.let { if (actual.priority != it) add(UmMismatch("rate-limit-priority", it.toString(), actual.priority?.toString() ?: "unset")) }
        reset?.let { want ->
            val got = actual.resetInterval
            val same = got == want || (want == UmResetInterval.DISABLED && got == null)
            if (!same) add(UmMismatch("reset-counters-interval", want.wire, got?.wire ?: "unset"))
        }
    }

    /** مقارنة حدّي التنزيل/الرفع المنفصلين وBurst (الحقول المطلوبة فقط). */
    internal fun verifyExtras(download: Long?, upload: Long?, burstRx: UmBurstDir?, burstTx: UmBurstDir?, actual: UmLimitation): List<UmMismatch> = buildList {
        download?.let { v -> val w = v.takeIf { it > 0 }; if (actual.downloadBytes != w) add(UmMismatch("download-limit", w?.toString() ?: "none", actual.downloadBytes?.toString() ?: "none")) }
        upload?.let { v -> val w = v.takeIf { it > 0 }; if (actual.uploadBytes != w) add(UmMismatch("upload-limit", w?.toString() ?: "none", actual.uploadBytes?.toString() ?: "none")) }
        fun dir(d: String, want: UmBurstDir?, got: UmBurstDir) {
            if (want == null) return
            if (want.rateKbps != got.rateKbps) add(UmMismatch("rate-limit-burst-$d", "${want.rateKbps}k", "${got.rateKbps}k"))
            if (want.thresholdKbps != got.thresholdKbps) add(UmMismatch("rate-limit-burst-threshold-$d", "${want.thresholdKbps}k", "${got.thresholdKbps}k"))
            if (want.timeSeconds != got.timeSeconds) add(UmMismatch("rate-limit-burst-time-$d", "${want.timeSeconds}s", "${got.timeSeconds}s"))
        }
        dir("rx", burstRx, actual.burstRx); dir("tx", burstTx, actual.burstTx)
    }

    /** نفس المقارنة لـ Limitation مستقل بعد كتابته. */
    fun verifyLimitation(spec: UmLimitationSpec, actual: UmLimitation?): UmVerification {
        if (actual == null) return UmVerification(listOf(UmMismatch("limitation", spec.name, "missing")))
        val out = mutableListOf<UmMismatch>()
        spec.rxKbps?.let { if (actual.rxKbps != it.coerceAtLeast(0)) out += UmMismatch("rate-limit-rx", "${it}k", "${actual.rxKbps}k") }
        spec.txKbps?.let { if (actual.txKbps != it.coerceAtLeast(0)) out += UmMismatch("rate-limit-tx", "${it}k", "${actual.txKbps}k") }
        spec.uptimeSeconds?.let { v -> val w = v.takeIf { it > 0 }; if (actual.uptimeSeconds != w) out += UmMismatch("uptime-limit", w?.toString() ?: "none", actual.uptimeSeconds?.toString() ?: "none") }
        spec.transferBytes?.let { v -> val w = v.takeIf { it > 0 }; if (actual.transferBytes != w) out += UmMismatch("transfer-limit", w?.toString() ?: "none", actual.transferBytes?.toString() ?: "none") }
        out += verifyExtras(spec.downloadBytes, spec.uploadBytes, spec.burstRx, spec.burstTx, actual)
        out += verifyAdvanced(spec.priority, spec.resetInterval, actual)
        return UmVerification(out)
    }

    /** نفس المقارنة لـ Profile مستقل بعد كتابته (الروابط لا تدخل هنا). */
    fun verifyProfile(spec: UmProfileSpec, actual: UmProfile?): UmVerification {
        if (actual == null) return UmVerification(listOf(UmMismatch("profile", spec.name, "missing")))
        val out = mutableListOf<UmMismatch>()
        when {
            spec.validityUnlimited -> if (!actual.validityUnlimited) out += UmMismatch("validity", "unlimited", actual.validitySeconds?.toString() ?: "unreadable")
            spec.validitySeconds != null && spec.validitySeconds > 0 ->
                if (actual.validitySeconds != spec.validitySeconds) out += UmMismatch("validity", "${spec.validitySeconds}s", actual.validitySeconds?.toString() ?: "unreadable")
        }
        when {
            spec.sharedUnlimited -> if (!actual.sharedUnlimited) out += UmMismatch("shared-users", "unlimited", actual.sharedUsers.toString())
            spec.sharedUsers != null -> if (actual.sharedUnlimited || actual.sharedUsers != spec.sharedUsers) out += UmMismatch("shared-users", spec.sharedUsers.toString(), if (actual.sharedUnlimited) "unlimited" else actual.sharedUsers.toString())
        }
        if (spec.startMode != null && actual.startMode != null && actual.startMode != spec.startMode) out += UmMismatch("validity-start", spec.startMode.name, actual.startMode.name)
        spec.priceMinor?.let { want -> if (actual.priceMinor != null && actual.priceMinor != want) out += UmMismatch("price", UmUnits.formatPrice(want), UmUnits.formatPrice(actual.priceMinor)) }
        return UmVerification(out)
    }
}

// ───────────────────────────── العلاقات ─────────────────────────────

/**
 * استخدام مكوّن: Profiles وPackages (الباقات التجارية في التطبيق) والمستخدمون المرتبطون.
 * [packages] تأتي من بيانات التطبيق (Profile.linkedProfileName)، والباقي من الراوتر.
 */
data class UmUsage2(val profiles: List<String>, val packages: List<String>, val users: List<String>)

object UmRelations {
    /** Profiles التي تربط هذا الـ Limitation. */
    fun profilesOfLimitation(graph: UmGraph, limitation: String): List<String> =
        graph.profiles.filter { limitation in it.limitations }.map { it.name }

    /**
     * استخدام Limitation: Profiles التي تربطه، الباقات عبر هذه الـ Profiles، والمستخدمون على تلك الـ Profiles (بلا تكرار).
     * [packagesByProfile] = اسم Profile ← أسماء الباقات التجارية المشيرة إليه.
     */
    fun usageOfLimitation(graph: UmGraph, limitation: String, packagesByProfile: Map<String, List<String>>): UmUsage2 {
        val profiles = profilesOfLimitation(graph, limitation)
        return UmUsage2(
            profiles = profiles,
            packages = profiles.flatMap { packagesByProfile[it].orEmpty() }.distinct(),
            users = profiles.flatMap { graph.usersByProfile[it].orEmpty() }.distinct()
        )
    }

    /** استخدام Profile: هو نفسه (profiles = [name])، الباقات المشيرة إليه، والمستخدمون المرتبطون به. */
    fun usageOfProfile(graph: UmGraph, profile: String, packagesByProfile: Map<String, List<String>>): UmUsage2 =
        UmUsage2(listOf(profile), packagesByProfile[profile].orEmpty().distinct(), graph.usersByProfile[profile].orEmpty().distinct())

    /** «مشترك» = يؤثر تعديله على أكثر من عنصر واحد من العناصر المرتبطة (Profile لـ Limitation، باقة لـ Profile). */
    fun isSharedLimitation(usage: UmUsage2): Boolean = usage.profiles.size > 1 || usage.packages.size > 1
    fun isSharedProfile(usage: UmUsage2): Boolean = usage.packages.size > 1
}

// ───────────────────────────── الحذف ─────────────────────────────

enum class DeleteBlocker {
    /** Profile عليه مستخدمون على الراوتر: تُعالَج ارتباطاتهم أولاً (لا تجاوز). */
    PROFILE_HAS_USERS,
    /** Profile تشير إليه باقات في التطبيق: تُفصل/تُنقل أولاً. */
    PROFILE_USED_BY_PACKAGES,
    /** Limitation مربوط بـ Profiles: يُفصل عنها أولاً (أو بطلب صريح مع إقرار الأثر). */
    LIMITATION_LINKED_TO_PROFILES,
    /** حذف يؤثر على مستخدمين عبر Profiles ولم يُقرّ المستخدم الأثر. */
    IMPACT_NOT_ACKNOWLEDGED,
    NOT_FOUND
}

enum class DeleteStatus { ALLOWED, NEEDS_ACKNOWLEDGEMENT, BLOCKED }

data class DeleteVerdict(val status: DeleteStatus, val blockers: List<DeleteBlocker>, val usage: UmUsage2) {
    val allowed: Boolean get() = status == DeleteStatus.ALLOWED
}

object UmDeleteRules {
    /**
     * حذف Profile: ممنوع إن كان عليه مستخدمون أو تشير إليه باقات (يجب معالجتهم قبل الحذف). روابطه بـ Limitations تُفكّ معه
     * والـ Limitations نفسها لا تُحذف. فصل Profile عن باقة لا يحذفه من الراوتر.
     */
    fun profile(usage: UmUsage2, exists: Boolean = true): DeleteVerdict {
        if (!exists) return DeleteVerdict(DeleteStatus.BLOCKED, listOf(DeleteBlocker.NOT_FOUND), usage)
        val blockers = buildList {
            if (usage.users.isNotEmpty()) add(DeleteBlocker.PROFILE_HAS_USERS)
            if (usage.packages.isNotEmpty()) add(DeleteBlocker.PROFILE_USED_BY_PACKAGES)
        }
        return DeleteVerdict(if (blockers.isEmpty()) DeleteStatus.ALLOWED else DeleteStatus.BLOCKED, blockers, usage)
    }

    /**
     * حذف Limitation: إن كان مربوطاً بـ Profiles فلا يُحذف إلا بطلب فصل صريح ([detachFromProfiles]).
     * وإن كان الفصل يمسّ مستخدمين فعليين (عبر Profiles) يُطلب إقرار الأثر ([acknowledged]) قبل السماح.
     */
    fun limitation(usage: UmUsage2, detachFromProfiles: Boolean, acknowledged: Boolean, exists: Boolean = true): DeleteVerdict {
        if (!exists) return DeleteVerdict(DeleteStatus.BLOCKED, listOf(DeleteBlocker.NOT_FOUND), usage)
        if (usage.profiles.isEmpty()) return DeleteVerdict(DeleteStatus.ALLOWED, emptyList(), usage)
        if (!detachFromProfiles) return DeleteVerdict(DeleteStatus.BLOCKED, listOf(DeleteBlocker.LIMITATION_LINKED_TO_PROFILES), usage)
        val needsAck = usage.users.isNotEmpty() || usage.profiles.size > 1 || usage.packages.isNotEmpty()
        return if (needsAck && !acknowledged) DeleteVerdict(DeleteStatus.NEEDS_ACKNOWLEDGEMENT, listOf(DeleteBlocker.IMPACT_NOT_ACKNOWLEDGED), usage)
        else DeleteVerdict(DeleteStatus.ALLOWED, emptyList(), usage)
    }
}

// ───────────────────────────── تعديل المكوّن المشترك ─────────────────────────────

/** تعديل مباشر (يؤثر على كل الارتباطات) أو مستقل (نسخة جديدة تُربط بالعنصر الحالي فقط). */
enum class SharedEditChoice { DIRECT, INDEPENDENT_COPY }

object UmSharedEdit {
    /** اسم نسخة غير مستخدم: base-copy ثم base-copy-2 ... */
    fun copyName(base: String, existing: Collection<String>): String {
        val taken = existing.map { it.lowercase() }.toHashSet()
        val first = "$base-copy"
        if (first.lowercase() !in taken) return first
        var i = 2
        while ("$first-$i".lowercase() in taken) i++
        return "$first-$i"
    }

    /** يحوّل مكوّناً مقروءاً إلى مواصفة بكل قيمه صراحةً (لإنشاء نسخة طبق الأصل بلا تخمين). */
    fun cloneSpec(src: UmLimitation, newName: String) = UmLimitationSpec(
        name = newName, rxKbps = src.rxKbps, txKbps = src.txKbps,
        uptimeSeconds = src.uptimeSeconds ?: 0L, transferBytes = src.transferBytes ?: 0L,
        downloadBytes = src.downloadBytes ?: 0L, uploadBytes = src.uploadBytes ?: 0L,
        burstRx = src.burstRx, burstTx = src.burstTx,
        priority = src.priority, resetInterval = src.resetInterval
    )

    fun cloneSpec(src: UmProfile, newName: String) = UmProfileSpec(
        name = newName, nameForUsers = src.nameForUsers.ifBlank { newName },
        validitySeconds = src.validitySeconds, validityUnlimited = src.validityUnlimited,
        sharedUsers = if (src.sharedUnlimited) null else src.sharedUsers, sharedUnlimited = src.sharedUnlimited,
        startMode = src.startMode, priceMinor = src.priceMinor
    )
}

// ───────────────────────────── المصدر والبصمة والمزامنة ─────────────────────────────

object UmOrigin {
    fun key(type: UmComponentType, name: String) = "${type.name.lowercase()}__$name"

    /**
     * مصدر المكوّن: السجل المحفوظ إن وُجد، وإلا بادئة التطبيق للـ Limitation المشتق (mgtik-)، وإلا خارجي.
     * لا نفترض ملكية التطبيق لمجرد وجود المكوّن على الراوتر أو في قاعدة التطبيق.
     */
    fun classify(type: UmComponentType, name: String, registry: Map<String, ComponentOrigin>): ComponentOrigin =
        registry[key(type, name)] ?: if (type == UmComponentType.LIMITATION && name.startsWith(UM_LIMITATION_PREFIX)) ComponentOrigin.MGTIK else ComponentOrigin.EXTERNAL
}

/** بصمة إعدادات مكوّن (ثابتة بين التشغيلات) لاكتشاف أي تعديل تم خارج التطبيق منذ آخر كتابة منه. */
object UmFingerprint {
    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    /** الحقول الجديدة (Burst وحدّا التنزيل/الرفع) تدخل البصمة فقط إن وُجدت، فلا تتغير بصمات المكونات المسجّلة سابقاً (لا انجراف كاذب). */
    fun of(l: UmLimitation): String {
        val extras = if (l.downloadBytes == null && l.uploadBytes == null && !l.burstRx.enabled && !l.burstTx.enabled) ""
        else "|dl${l.downloadBytes ?: 0}|ul${l.uploadBytes ?: 0}|bx${l.burstRx.rateKbps}/${l.burstRx.thresholdKbps}/${l.burstRx.timeSeconds}|bt${l.burstTx.rateKbps}/${l.burstTx.thresholdKbps}/${l.burstTx.timeSeconds}"
        // الأولوية والتصفير الدوري كذلك: القيمتان الافتراضيتان (8، معطَّل) أو غير المقروءتان لا تغيّران البصمة
        val prio = l.priority?.takeIf { it != UmLimitRules.PRIORITY_DEFAULT }?.let { "|pr$it" }.orEmpty()
        val reset = l.resetInterval?.takeIf { it != UmResetInterval.DISABLED }?.let { "|rs${it.wire}" }.orEmpty()
        return hash("L|${l.name}|${l.rxKbps}|${l.txKbps}|${l.uptimeSeconds ?: 0}|${l.transferBytes ?: 0}$extras$prio$reset")
    }

    fun of(p: UmProfile): String {
        // السعر (غير الصفري) والجداول المقيِّدة تدخل البصمة فقط حين توجد: بصمات ما سُجّل سابقاً لا تتغير
        val price = p.priceMinor?.takeIf { it > 0 }?.let { "|\$$it" }.orEmpty()
        val sched = p.schedules.filterValues { !it.isAlwaysOn }.toSortedMap()
            .entries.joinToString("") { (k, v) -> "|s$k=${UmSchedule.clock(v.fromSeconds)}-${UmSchedule.clock(v.tillSeconds)}/${UmSchedule.weekdaysWire(v.weekdays)}" }
        return hash("P|${p.name}|${p.validitySeconds ?: 0}|${p.validityUnlimited}|${if (p.sharedUnlimited) "u" else p.sharedUsers}|${p.startMode?.name ?: "-"}|${p.limitations.sorted().joinToString(",")}$price$sched")
    }
}

enum class DriftState {
    /** البصمة المسجّلة تطابق ما على الراوتر الآن. */
    IN_SYNC,
    /** تغيّر المكوّن على الراوتر منذ آخر كتابة من التطبيق: لا يُكتب فوقه دون تنبيه. */
    EXTERNAL_CHANGE,
    /** لا بصمة مسجّلة (مكوّن خارجي أو قديم): لا يمكن الحكم، وأول كتابة تسجّل بصمة. */
    NEVER_RECORDED,
    MISSING_ON_ROUTER
}

object UmDrift {
    fun detect(recorded: String?, current: String?): DriftState = when {
        current == null -> DriftState.MISSING_ON_ROUTER
        recorded.isNullOrBlank() -> DriftState.NEVER_RECORDED
        recorded == current -> DriftState.IN_SYNC
        else -> DriftState.EXTERNAL_CHANGE
    }

    /** هل يجوز الكتابة فوق المكوّن دون تنبيه المستخدم؟ فقط حين لا تعديل خارجياً مكتشفاً. */
    fun writeNeedsWarning(state: DriftState): Boolean = state == DriftState.EXTERNAL_CHANGE
}

/** فرق بين ما يعرفه التطبيق عن الباقة (محلياً) وما على الراوتر الآن. */
data class UmSyncDiff(val field: String, val local: String, val router: String)

object UmPackageSync {
    /**
     * مقارنة حقول الباقة المحلية (السرعة، عدد الأجهزة، الصلاحية) مع Profile وLimitation الأساسي على الراوتر.
     * القيمة المحلية غير المحددة (0 ساعة، بلا حد) لا تُعدّ فرقاً: لا نخمّن أن غيابها يعني قيمة بعينها.
     */
    fun diff(
        rxKbps: Int, txKbps: Int, sharedUsers: Int, sharedUnlimited: Boolean,
        validityHours: Int, validityUnlimited: Boolean,
        limitUptimeSeconds: Long?, limitTransferBytes: Long?,
        router: UmProfile, primary: UmLimitation?
    ): List<UmSyncDiff> = buildList {
        if (router.sharedUnlimited != sharedUnlimited || (!sharedUnlimited && router.sharedUsers != sharedUsers))
            add(UmSyncDiff("shared-users", if (sharedUnlimited) "unlimited" else "$sharedUsers", if (router.sharedUnlimited) "unlimited" else "${router.sharedUsers}"))
        when {
            validityUnlimited -> if (!router.validityUnlimited) add(UmSyncDiff("validity", "unlimited", router.validitySeconds?.let { "${it}s" } ?: "?"))
            validityHours > 0 -> if (router.validitySeconds != validityHours * 3_600L) add(UmSyncDiff("validity", "${validityHours * 3_600L}s", router.validitySeconds?.let { "${it}s" } ?: if (router.validityUnlimited) "unlimited" else "?"))
        }
        if (primary == null) {
            add(UmSyncDiff("limitation", "linked", "none"))
        } else {
            if (primary.rxKbps != rxKbps) add(UmSyncDiff("rate-limit-rx", "${rxKbps}k", "${primary.rxKbps}k"))
            if (primary.txKbps != txKbps) add(UmSyncDiff("rate-limit-tx", "${txKbps}k", "${primary.txKbps}k"))
            if (limitUptimeSeconds != null && primary.uptimeSeconds != limitUptimeSeconds) add(UmSyncDiff("uptime-limit", "$limitUptimeSeconds", "${primary.uptimeSeconds ?: "none"}"))
            if (limitTransferBytes != null && primary.transferBytes != limitTransferBytes) add(UmSyncDiff("transfer-limit", "$limitTransferBytes", "${primary.transferBytes ?: "none"}"))
        }
    }
}

// ───────────────────────────── لقطات الاستعادة ─────────────────────────────

/**
 * لقطة إعدادات مكوّن قبل تعديل مهم. تُخزَّن في السحابة (للاستعادة من MGTik)؛ [limitation] أو [profile] واحد منهما غير null.
 */
data class UmSnapshot(
    val id: String,
    val type: UmComponentType,
    val name: String,
    val takenAtMillis: Long,
    val reason: String,
    val limitation: UmLimitation? = null,
    val profile: UmProfile? = null
) {
    fun toMap(): Map<String, Any?> = buildMap {
        put("type", type.name.lowercase()); put("name", name); put("taken_at", takenAtMillis); put("reason", reason)
        limitation?.let { l ->
            put("rx_kbps", l.rxKbps); put("tx_kbps", l.txKbps); put("uptime_seconds", l.uptimeSeconds); put("transfer_bytes", l.transferBytes)
            put("download_bytes", l.downloadBytes); put("upload_bytes", l.uploadBytes)
            put("burst_rx_rate", l.burstRx.rateKbps); put("burst_rx_threshold", l.burstRx.thresholdKbps); put("burst_rx_time", l.burstRx.timeSeconds)
            put("burst_tx_rate", l.burstTx.rateKbps); put("burst_tx_threshold", l.burstTx.thresholdKbps); put("burst_tx_time", l.burstTx.timeSeconds)
            put("priority", l.priority); put("reset_interval", l.resetInterval?.wire)
        }
        profile?.let { p ->
            put("name_for_users", p.nameForUsers); put("validity_seconds", p.validitySeconds); put("validity_unlimited", p.validityUnlimited)
            put("shared_users", p.sharedUsers); put("shared_unlimited", p.sharedUnlimited); put("start_mode", p.startMode?.name)
            put("limitations", p.limitations)
            put("price_minor", p.priceMinor)
            // نافذة كل رابط نصاً: limitation|from|till|days
            put("schedules", p.schedules.map { (lim, s) -> "$lim|${s.fromSeconds}|${s.tillSeconds}|${UmSchedule.weekdaysWire(s.weekdays)}" })
        }
    }

    companion object {
        fun fromMap(id: String, m: Map<String, Any?>): UmSnapshot? {
            val type = (m["type"] as? String)?.let { t -> UmComponentType.entries.firstOrNull { it.name.equals(t, true) } } ?: return null
            val name = m["name"] as? String ?: return null
            fun long(k: String) = (m[k] as? Number)?.toLong()
            fun int(k: String) = (m[k] as? Number)?.toInt()
            val at = long("taken_at") ?: 0L
            val reason = m["reason"] as? String ?: ""
            return when (type) {
                UmComponentType.LIMITATION -> UmSnapshot(id, type, name, at, reason, limitation = UmLimitation(
                    name, int("rx_kbps") ?: 0, int("tx_kbps") ?: 0, long("uptime_seconds"), long("transfer_bytes"),
                    downloadBytes = long("download_bytes"), uploadBytes = long("upload_bytes"),
                    burstRx = UmBurstDir(int("burst_rx_rate") ?: 0, int("burst_rx_threshold") ?: 0, long("burst_rx_time") ?: 0L),
                    burstTx = UmBurstDir(int("burst_tx_rate") ?: 0, int("burst_tx_threshold") ?: 0, long("burst_tx_time") ?: 0L),
                    priority = int("priority"), resetInterval = UmResetInterval.fromWire(m["reset_interval"] as? String)))
                UmComponentType.PROFILE -> UmSnapshot(id, type, name, at, reason, profile = UmProfile(
                    name = name, nameForUsers = m["name_for_users"] as? String ?: name,
                    validitySeconds = long("validity_seconds"), validityUnlimited = m["validity_unlimited"] as? Boolean ?: false,
                    sharedUsers = int("shared_users") ?: 1, sharedUnlimited = m["shared_unlimited"] as? Boolean ?: false,
                    startMode = (m["start_mode"] as? String)?.let { s -> UmStartMode.entries.firstOrNull { it.name == s } },
                    limitations = (m["limitations"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
                    schedules = (m["schedules"] as? List<*>)?.filterIsInstance<String>().orEmpty().mapNotNull { row ->
                        val p = row.split('|')
                        if (p.size != 4) null else p[0] to UmSchedule(p[1].toIntOrNull() ?: 0, p[2].toIntOrNull() ?: UmSchedule.MAX_SECONDS, p[3].split(',').mapNotNull { UmWeekday.fromWire(it) }.toSet().ifEmpty { UmWeekday.ALL })
                    }.toMap(),
                    priceMinor = long("price_minor")))
            }
        }
    }
}

/** قواعد أسماء المكوّنات (Profile/Limitation) المُدخَلة يدوياً. */
object UmNameRules {
    enum class Problem { BLANK, TOO_LONG, INVALID_CHARS, RESERVED, EXISTS }

    private const val MAX_LEN = 64

    /** اسم صالح: أحرف وأرقام و - و _ و . فقط (بلا مسافات) وحتى 64 حرفاً، ولا يُستعمل `default`. التكرار بحسب الحالة غير الحساسة. */
    fun validate(name: String, existing: Collection<String>, allowExisting: Boolean = false): Problem? = when {
        name.isBlank() -> Problem.BLANK
        name.length > MAX_LEN -> Problem.TOO_LONG
        !name.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' } -> Problem.INVALID_CHARS
        name.equals("default", ignoreCase = true) -> Problem.RESERVED
        !allowExisting && existing.any { it.equals(name, ignoreCase = true) } -> Problem.EXISTS
        else -> null
    }
}

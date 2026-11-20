package ps.mgtik.hotspot.data.userman

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import ps.mgtik.hotspot.core.network.RouterApi
import ps.mgtik.hotspot.core.network.RouterApiException
import ps.mgtik.hotspot.core.network.RouterCommandRejected
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.network.string
import ps.mgtik.hotspot.domain.model.UserManConfig

/**
 * UserManDialect - كل ما يختلف بين User Manager v6 (/tool/user-manager) وv7 (/user-manager).
 * باقي التطبيق لا يعرف أي جيل يعمل تحته. أسماء الحقول مبنية على توثيق RouterOS ولم تُجرَّب على جهاز حقيقي بعد
 * (انظر USERMAN_MIGRATION في التقرير): كل لهجة معزولة في ملف واحد لتعديلها بسهولة بعد أول اختبار فعلي.
 */
interface UserManDialect {
    val generation: UmGeneration

    suspend fun listPackages(api: RouterApi): List<UmPackage>

    /**
     * ينشئ أو يحدّث Limitation + Profile + Profile-Limitation للباقة (idempotent) وفق [UmPackageSpec]: المكوّن المرجعي لا يُكتب عليه،
     * والحقول خارج [UmPackageSpec.writes] لا تُلمس. فشل خطوة بعد نجاح سابقتها يتراجع عمّا أنشأته أو غيّرته هذه العملية وحدها
     * (لا يُحذف مكوّن موجود مسبقاً)، وأخطاء التراجع تُسجَّل في [log] ولا تحجب الخطأ الأصلي المُعاد رميه.
     */
    suspend fun upsertPackage(api: RouterApi, spec: UmPackageSpec, log: UmOpLog = UmOpLog())

    /** قراءة بعد الكتابة: هل ما على الراوتر الآن يطابق [spec]؟ (المرجع التشغيلي الوحيد لنجاح التنفيذ). */
    suspend fun verifyPackage(api: RouterApi, spec: UmPackageSpec): UmVerification

    /**
     * يكتب حقل price على Profile قائم فقط (لا ينشئ Profile ولا Limitation ولا رابطاً) ثم يعيد قراءته.
     * null = الباقة غير موجودة على الراوتر (لا شيء كُتب).
     */
    suspend fun setProfilePrice(api: RouterApi, name: String, priceMinor: Long): UmProfile?

    /** يحذف Profile الباقة وروابطه، ويحذف Limitation المشتق mgtik-<name> فقط إن لم يربطه Profile آخر. لا يحذف أي Limitation مشترك/خارجي. */
    suspend fun deletePackage(api: RouterApi, name: String): Boolean

    // ───────────── Profiles وLimitations كمكونات مستقلة ─────────────

    suspend fun listProfiles(api: RouterApi): List<UmProfile>
    suspend fun listLimitations(api: RouterApi): List<UmLimitation>

    /** Profiles وLimitations والمستخدمون المرتبطون بكل Profile بقراءة واحدة متسقة (لحساب العلاقات والأثر). */
    suspend fun readGraph(api: RouterApi): UmGraph

    /** ينشئ Limitation جديداً؛ يرفض (RouterCommandRejected) إن كان الاسم موجوداً: لا استبدال صامت لمكوّن قائم. */
    suspend fun createLimitation(api: RouterApi, spec: UmLimitationSpec)

    /** يعدّل Limitation موجوداً (الحقول null لا تُلمس). false = غير موجود. */
    suspend fun updateLimitation(api: RouterApi, spec: UmLimitationSpec): Boolean

    /** يعيد تسمية Limitation مع الإبقاء على روابطه بـ Profiles. false = غير موجود. يرفض اسماً مأخوذاً. */
    suspend fun renameLimitation(api: RouterApi, oldName: String, newName: String): Boolean

    /** يحذف Limitation وروابطه فقط (لا يمسّ أي Profile). الفحص الوقائي للعلاقات مسؤولية المستدعي ([UmDeleteRules]). */
    suspend fun removeLimitation(api: RouterApi, name: String): Boolean

    suspend fun createProfile(api: RouterApi, spec: UmProfileSpec)
    suspend fun updateProfile(api: RouterApi, spec: UmProfileSpec): Boolean

    /** يعيد تسمية Profile مع روابطه ومستخدميه ([syncNameForUsers] يغيّر name-for-users أيضاً). يرفض اسماً مأخوذاً. */
    suspend fun renameProfile(api: RouterApi, oldName: String, newName: String, syncNameForUsers: Boolean = false): Boolean

    /** يحذف Profile وروابطه بـ Limitations فقط (الـ Limitations نفسها تبقى). الفحص الوقائي مسؤولية المستدعي. */
    suspend fun removeProfile(api: RouterApi, name: String): Boolean

    /** يضبط روابط Profile بـ Limitations على [names] بالضبط (تُضاف الناقصة وتُفصل الزائدة دون حذف أي Limitation). */
    suspend fun setProfileLimitations(api: RouterApi, profile: String, names: List<String>): UmLinkChange

    suspend fun linkLimitation(api: RouterApi, profile: String, limitation: String): Boolean
    suspend fun unlinkLimitation(api: RouterApi, profile: String, limitation: String): Boolean

    /** يعيد مكوّناً إلى لقطة محفوظة (كل القيم صراحةً؛ الروابط لـ Profile تُعاد لما كانت وما فُقد منها يُذكر في الناتج). */
    suspend fun restoreSnapshot(api: RouterApi, snapshot: UmSnapshot): List<String>

    /** يعيد تسمية الباقة وLimitation المشتقة منها دون المساس بالمستخدمين المسندين لها. false = الباقة القديمة غير موجودة. */
    suspend fun renamePackage(api: RouterApi, oldName: String, newName: String): Boolean

    suspend fun listUsers(api: RouterApi): List<UmUser>

    /** (الاسم، التعليق) فقط للمستخدمين: مسار خفيف لا يقرأ الباقات. للوسوم والتوليد والحذف بالتعليق. */
    suspend fun listUserNames(api: RouterApi): List<Pair<String, String>> = listUsers(api).map { it.name to it.comment.orEmpty() }

    /** «مستخدم ← باقته الحالية» لمن لهم باقة. v7: قراءة واحدة لـ user-profile. */
    suspend fun userPackages(api: RouterApi): Map<String, String> =
        listUsers(api).mapNotNull { u -> u.packageName?.takeIf(String::isNotBlank)?.let { u.name to it } }.toMap()

    /** أسماء المستخدمين المسنَدة لهذه الباقة حالياً على الراوتر (حارس الحذف: لا تُحذف باقة عليها مستخدمون). */
    suspend fun usersOnPackage(api: RouterApi, packageName: String): List<String> =
        userPackages(api).filter { it.value.equals(packageName, ignoreCase = true) }.keys.toList()

    suspend fun findUser(api: RouterApi, name: String): UmUser?

    /** ينشئ مستخدماً ويُسند له الباقة (إن وُجدت). يرمي [RouterCommandRejected] عند رفض الراوتر. */
    suspend fun createUser(api: RouterApi, name: String, password: String, comment: String, packageName: String?, disabled: Boolean)

    suspend fun setDisabled(api: RouterApi, name: String, disabled: Boolean)

    suspend fun deleteUser(api: RouterApi, name: String): Boolean

    /** مجموع جلسات المستخدم (الوقت والرفع والتنزيل). UserMan لا يحمل العدّادات في صف المستخدم نفسه. */
    suspend fun usage(api: RouterApi, name: String): UmUsage

    /**
     * الجلسات الحيّة الآن من User Manager (مصدر الجلسات والاستهلاك): active صحيح وحالة محاسبة لا تدل على النهاية.
     * سجلات الجلسات التاريخية لا تدخل هنا. مدة كل جلسة = ساعة الراوتر − وقت البداية إن أمكن، وإلا uptime المبلَّغ.
     */
    suspend fun liveSessions(api: RouterApi): List<UmSession>

    /**
     * سجل جلسات مستخدم من User Manager نفسه (المنتهية والجارية)، الأحدث أولاً، بلا تكرار وبحدّ [limit].
     * هذا هو المصدر التاريخي الحقيقي (بداية/نهاية/سبب إنهاء/NAS)؛ لا يُستنتج من رصد الجلسات الحية.
     * الحقول غير المدعومة على الراوتر تبقى فارغة ولا تُخمَّن.
     */
    suspend fun sessionHistory(api: RouterApi, username: String, limit: Int = 200): List<UmSession>

    /** إسناد باقة جديدة (تجديد): فترة جديدة بعدّادات جديدة، لا تصفير للعدّادات القديمة. */
    /**
     * إسناد باقة (فترة) للمستخدم. idempotent: إعادة المحاولة بعد نجاح ضاع إقراره لا تُنشئ تفعيلاً ثانياً.
     * [renew] = تجديد صريح (فترة جديدة حتى لو كانت الباقة نفسها)؛ وإلا يُتخطى الإسناد إن كانت باقته الحالية هي نفسها.
     */
    suspend fun activatePackage(api: RouterApi, userName: String, packageName: String, renew: Boolean = false)

    /**
     * استهلاك الفترة الحالية فقط (الجلسات التي بدأت منذ بدء تفعيل الباقة الجاري): هو ما تُقارَن به حدود الباقة لأن الحدود في User Manager
     * تُفرض لكل فترة. [usage] يبقى الإجمالي التاريخي لكل الجلسات. حين لا يُعرف بدء الفترة (v6، أو راوتر لا يعرض الحقل) يساوي [usage].
     */
    suspend fun periodUsage(api: RouterApi, name: String): UmUsage = usage(api, name)

    // ───────────── الحالة والتهيئة والصيانة (شاشة إعداد User Manager وقسم التشخيص والصيانة) ─────────────

    /** لقطة حالة كاملة بالقراءة فقط: الحزمة والتفعيل وسجل الراوتر (NAS) وRADIUS وuse-radius وأعداد المستخدمين والباقات. */
    suspend fun inspect(api: RouterApi, cfg: UserManConfig, routerOsVersion: String): UmStatus

    /**
     * يهيّئ الراوتر ليعمل الهوتسبوت عبر User Manager: تفعيله (v7)، سجل الراوتر (NAS)، سجل `/radius` للهوتسبوت،
     * و`use-radius` لسيرفرات الهوتسبوت. كل خطوة idempotent وتُرجع نتيجتها؛ فشل خطوة (رفض الراوتر) لا يوقف بقيتها.
     * لا يمسّ المستخدمين ولا الباقات.
     */
    suspend fun provision(api: RouterApi, cfg: UserManConfig): List<UmProvisionStep>

    /** يحفظ نسخة من قاعدة بيانات User Manager على الراوتر نفسه باسم [name] (ملف .umb). */
    suspend fun backupDatabase(api: RouterApi, name: String)

    /** تحسين قاعدة البيانات (v7 فقط). false = غير مدعوم في هذا الجيل. */
    suspend fun optimizeDatabase(api: RouterApi): Boolean

    /** Limitations أنشأها التطبيق (بادئة mgtik-) ولا باقة لها (بقايا حذف/إعادة تسمية ناقصة). */
    suspend fun orphanLimitations(api: RouterApi): List<String>

    /** يحذف الـ Limitations المعطاة مع روابطها (profile-limitation). يعيد عدد ما حُذف فعلاً. */
    suspend fun removeLimitations(api: RouterApi, names: Collection<String>): Int
}

/** مساعدات مشتركة بين اللهجتين. */
internal abstract class BaseDialect : UserManDialect {

    protected fun JsonObject.isTrue(key: String): Boolean {
        val v = string(key)
        return v.equals("true", ignoreCase = true) || v.equals("yes", ignoreCase = true)
    }

    protected suspend fun RouterApi.idOf(path: String, key: String, value: String): String? =
        print(path, query = mapOf(key to value), props = listOf(".id", key)).firstOrNull()?.string(".id")

    protected fun requireId(id: String?, what: String): String = id ?: throw RouterCommandRejected("no such $what")

    /** يجمع جلسات المستخدم من [sessionPath] (حقول uptime وupload وdownload). */
    protected suspend fun sumSessions(api: RouterApi, sessionPath: String, name: String, sinceSeconds: Long? = null): UmUsage {
        // جلسة بلا وقت بدء مفهوم تُحتسب (تحفّظاً): لا نُسقط استهلاكاً لا نستطيع تأريخه
        // وتُزال الجلسة المكررة (الصف نفسه بمعرّف محاسبة واحد) كي لا يُحتسب استهلاكها مرتين
        val rows = UmSessionHistory.dedupe(api.print(sessionPath, query = mapOf("user" to name)).map(::parseSession)).filter { s ->
            sinceSeconds == null || (UmUnits.parseRouterDateTime(s.startedRaw)?.let { it >= sinceSeconds } ?: true)
        }
        return UmUsage(
            uptimeSeconds = rows.sumOf { it.uptimeSeconds ?: 0L },
            uploadBytes = rows.sumOf { it.uploadBytes },
            downloadBytes = rows.sumOf { it.downloadBytes }
        )
    }

    /** اسم Limitation المشتق من اسم الباقة. */
    protected fun limitationName(pkg: String) = "$LIMITATION_PREFIX$pkg"

    /** خصائص Limitation من مواصفة مستقلة: null لا يُرسل (الراوتر يحتفظ بقيمته)، وفي الوقت والنقل 0 = إزالة صريحة. */
    protected fun limitationAttrs(spec: UmLimitationSpec): Map<String, Any?> = buildMap {
        spec.rxKbps?.let { put("rate-limit-rx", UmUnits.formatRate(it)) }
        spec.txKbps?.let { put("rate-limit-tx", UmUnits.formatRate(it)) }
        spec.uptimeSeconds?.let { put("uptime-limit", if (it > 0) "${it}s" else "0s") }
        spec.transferBytes?.let { put("transfer-limit", if (it > 0) it.toString() else "0") }
        spec.downloadBytes?.let { put("download-limit", if (it > 0) it.toString() else "0") }
        spec.uploadBytes?.let { put("upload-limit", if (it > 0) it.toString() else "0") }
        spec.burstRx?.let { putBurst("rx", it) }
        spec.burstTx?.let { putBurst("tx", it) }
        spec.priority?.let { put("rate-limit-priority", it.toString()) }
        // التصفير الدوري غير موجود في v6: لا يُرسل أبداً هناك (التعطيل الصريح عديم الأثر، والتفعيل مرفوض في requireValid)
        if (supportsResetInterval) {
            spec.resetInterval?.let { put("reset-counters-interval", it.wire) }
            spec.resetStartTime?.let { put("reset-counters-start-time", it) }
        }
    }

    /** هل يملك هذا الجيل reset-counters-interval على Limitation؟ v7 نعم. v6 لا (لا خاصية مدمجة؛ المعروف جدولة سكربت خارجي). */
    protected open val supportsResetInterval: Boolean get() = true

    private fun MutableMap<String, Any?>.putBurst(dir: String, b: UmBurstDir) {
        put("rate-limit-burst-$dir", UmUnits.formatRate(b.rateKbps))
        put("rate-limit-burst-threshold-$dir", UmUnits.formatRate(b.thresholdKbps))
        put("rate-limit-burst-time-$dir", UmUnits.formatSeconds(b.timeSeconds))
    }

    /** يرفض قبل أي كتابة: إعدادات Burst/حدود غير صالحة أو متعارضة بعد دمجها مع الحالة الحالية على الراوتر. */
    protected fun requireValid(existing: UmLimitation?, spec: UmLimitationSpec) {
        val unsupported = buildList {
            if (!supportsResetInterval && ((spec.resetInterval ?: UmResetInterval.DISABLED) != UmResetInterval.DISABLED || spec.resetStartTime != null)) {
                add(UmLimitIssue(UmLimitRules.UNSUPPORTED, "reset-counters-interval"))
            }
        }
        val issues = unsupported + UmLimitRules.validate(existing, spec)
        if (issues.isNotEmpty()) throw RouterApiException(ApiError(ERR_LIMIT_INVALID, issues.joinToString(",") { "${it.code}:${it.field}" }))
    }

    /** خصائص Profile من مواصفة مستقلة: الصلاحية لا تُرسل 0s إلا بقرار صريح (validityUnlimited)، وعدد الأجهزة قد يكون unlimited. */
    protected fun profileAttrs(spec: UmProfileSpec): Map<String, Any?> = buildMap {
        spec.nameForUsers?.let { put("name-for-users", it) }
        when {
            spec.validityUnlimited -> put("validity", "0s")
            (spec.validitySeconds ?: 0L) > 0L -> put("validity", "${spec.validitySeconds}s")
        }
        when {
            spec.sharedUnlimited -> put("override-shared-users", "unlimited")
            spec.sharedUsers != null -> put("override-shared-users", spec.sharedUsers.coerceAtLeast(1).toString())
        }
        spec.startMode?.let { put(startAttr, startValue(it)) }
        spec.priceMinor?.let { put("price", UmUnits.formatPrice(it)) }
    }

    /** مواصفة Limitation الأساسي من مواصفة الباقة: فقط ما سيُكتب فعلاً ([UmPackageSpec.willWrite]). */
    private fun UmPackageSpec.toLimitationSpec(primary: String) = UmLimitationSpec(
        name = primary,
        rxKbps = rxKbps.takeIf { willWrite(UmWrite.SPEED) },
        txKbps = txKbps.takeIf { willWrite(UmWrite.SPEED) },
        uptimeSeconds = uptimeSeconds?.takeIf { willWrite(UmWrite.UPTIME_LIMIT) },
        transferBytes = transferBytes?.takeIf { willWrite(UmWrite.TRANSFER_LIMIT) },
        downloadBytes = downloadBytes?.takeIf { willWrite(UmWrite.DOWNLOAD_LIMIT) },
        uploadBytes = uploadBytes?.takeIf { willWrite(UmWrite.UPLOAD_LIMIT) },
        burstRx = burstRx?.takeIf { willWrite(UmWrite.BURST) },
        burstTx = burstTx?.takeIf { willWrite(UmWrite.BURST) },
        priority = priority?.takeIf { willWrite(UmWrite.PRIORITY) },
        resetInterval = resetInterval?.takeIf { willWrite(UmWrite.RESET_INTERVAL) },
        resetStartTime = resetStartTime?.takeIf { willWrite(UmWrite.RESET_INTERVAL) }
    )

    private fun UmPackageSpec.toProfileSpec() = UmProfileSpec(
        name = name,
        nameForUsers = name,
        validitySeconds = validitySeconds?.takeIf { willWrite(UmWrite.VALIDITY) && !validityUnlimited },
        validityUnlimited = validityUnlimited && willWrite(UmWrite.VALIDITY),
        sharedUsers = if (willWrite(UmWrite.SHARED_USERS) && !sharedUnlimited) sharedUsers else null,
        sharedUnlimited = sharedUnlimited && willWrite(UmWrite.SHARED_USERS),
        startMode = startMode.takeIf { willWrite(UmWrite.START_MODE) },
        priceMinor = priceMinor?.takeIf { willWrite(UmWrite.PRICE) }
    )

    // ───────────── قراءة المكونات ─────────────

    protected fun JsonObject.toLimitation(): UmLimitation? {
        val name = string("name")?.takeIf { it.isNotBlank() } ?: return null
        return UmLimitation(
            name = name,
            rxKbps = UmUnits.parseRateKbps(string("rate-limit-rx")),
            txKbps = UmUnits.parseRateKbps(string("rate-limit-tx")),
            uptimeSeconds = UmUnits.parseDurationSeconds(string("uptime-limit"))?.takeIf { it > 0 },
            transferBytes = UmUnits.parseBytes(string("transfer-limit"))?.takeIf { it > 0 },
            downloadBytes = UmUnits.parseBytes(string("download-limit"))?.takeIf { it > 0 },
            uploadBytes = UmUnits.parseBytes(string("upload-limit"))?.takeIf { it > 0 },
            burstRx = burstOf("rx"), burstTx = burstOf("tx"),
            priority = string("rate-limit-priority")?.trim()?.toIntOrNull()?.takeIf { it in UmLimitRules.PRIORITY_HIGHEST..UmLimitRules.PRIORITY_DEFAULT },
            resetInterval = UmResetInterval.fromWire(string("reset-counters-interval"))
        )
    }

    private fun JsonObject.burstOf(dir: String) = UmBurstDir(
        rateKbps = UmUnits.parseRateKbps(string("rate-limit-burst-$dir")),
        thresholdKbps = UmUnits.parseRateKbps(string("rate-limit-burst-threshold-$dir")),
        timeSeconds = UmUnits.parseDurationSeconds(string("rate-limit-burst-time-$dir"))?.takeIf { it > 0 } ?: 0L
    )

    protected fun JsonObject.toProfile(links: List<String>, schedules: Map<String, UmSchedule> = emptyMap()): UmProfile? {
        val name = string("name")?.takeIf { it.isNotBlank() } ?: return null
        val validityRaw = string("validity")?.trim().orEmpty()
        val validity = UmUnits.parseDurationSeconds(validityRaw)
        val sharedRaw = string("override-shared-users")?.trim().orEmpty()
        return UmProfile(
            name = name,
            nameForUsers = string("name-for-users").orEmpty(),
            validitySeconds = validity?.takeIf { it > 0 },
            validityUnlimited = validity == 0L || validityRaw.equals("unlimited", ignoreCase = true),
            sharedUsers = sharedRaw.toIntOrNull() ?: 1,
            sharedUnlimited = sharedRaw.equals("unlimited", ignoreCase = true),
            startMode = startModeOf(this),
            limitations = links,
            schedules = schedules,
            priceMinor = UmUnits.parsePriceMinor(string("price"))
        )
    }

    /** خصائص رابط Profile↔Limitation المقروءة: المعرّف والطرفان ونافذة التشغيل (from-time/till-time/weekdays). */
    private val linkProps = listOf(".id", "profile", "limitation", "from-time", "till-time", "weekdays")

    private class LinkRow(val id: String, val profile: String, val limitation: String, val schedule: UmSchedule)

    private fun JsonObject.toLinkRow(): LinkRow? {
        val lim = string("limitation")?.takeIf { it.isNotBlank() } ?: return null
        return LinkRow(string(".id").orEmpty(), string("profile").orEmpty(), lim, UmSchedule.parse(string("from-time"), string("till-time"), string("weekdays")))
    }

    private suspend fun linkRows(api: RouterApi, profile: String): List<LinkRow> =
        api.print(umProfileLimitationPath, query = mapOf("profile" to profile), props = linkProps).mapNotNull { it.toLinkRow() }

    private suspend fun allLinkRows(api: RouterApi): Map<String, List<LinkRow>> =
        api.print(umProfileLimitationPath, props = linkProps).mapNotNull { it.toLinkRow() }.groupBy { it.profile }

    /** يطبّق نافذة التشغيل على رابط قائم (قراءة المعرّف أولاً). false = لا رابط. */
    private suspend fun setLinkSchedule(api: RouterApi, profile: String, limitation: String, schedule: UmSchedule): Boolean {
        val rows = linkRows(api, profile).filter { it.limitation == limitation }
        rows.forEach { api.set(umProfileLimitationPath, it.id, UmScheduleRules.attrs(schedule)) }
        return rows.isNotEmpty()
    }

    private suspend fun linksOf(api: RouterApi, profile: String): List<String> =
        linkRows(api, profile).map { it.limitation }.distinct()

    private suspend fun readLimitation(api: RouterApi, name: String): UmLimitation? =
        api.print(umLimitationPath, query = mapOf("name" to name)).firstOrNull { it.string("name") == name }?.toLimitation()

    private suspend fun readProfile(api: RouterApi, name: String): UmProfile? =
        api.print(umProfilePath, query = mapOf("name" to name)).firstOrNull { it.string("name") == name }?.let { row ->
            val rows = linkRows(api, name)
            row.toProfile(rows.map { it.limitation }.distinct(), rows.associate { it.limitation to it.schedule })
        }

    override suspend fun listLimitations(api: RouterApi): List<UmLimitation> = api.print(umLimitationPath).mapNotNull { it.toLimitation() }

    override suspend fun listProfiles(api: RouterApi): List<UmProfile> {
        val links = allLinkRows(api)
        return api.print(umProfilePath).mapNotNull { row ->
            val rows = links[row.string("name").orEmpty()].orEmpty()
            row.toProfile(rows.map { it.limitation }.distinct(), rows.associate { it.limitation to it.schedule })
        }
    }

    override suspend fun readGraph(api: RouterApi): UmGraph {
        val limitations = listLimitations(api)
        val profiles = listProfiles(api)
        val users = userPackages(api).entries.groupBy({ it.value }, { it.key })
        return UmGraph(profiles, limitations, users)
    }

    // ───────────── الإنشاء والتعديل المستقل ─────────────

    override suspend fun createLimitation(api: RouterApi, spec: UmLimitationSpec) {
        if (readLimitation(api, spec.name) != null) throw RouterCommandRejected("failure: already have such entry (limitation ${spec.name})")
        requireValid(null, spec)
        api.add(umLimitationPath, limitationAttrs(spec) + createExtras + ("name" to spec.name))
    }

    override suspend fun updateLimitation(api: RouterApi, spec: UmLimitationSpec): Boolean {
        val id = api.idOf(umLimitationPath, "name", spec.name) ?: return false
        requireValid(readLimitation(api, spec.name), spec)
        val attrs = limitationAttrs(spec)
        if (attrs.isNotEmpty()) api.set(umLimitationPath, id, attrs)
        return true
    }

    override suspend fun createProfile(api: RouterApi, spec: UmProfileSpec) {
        if (readProfile(api, spec.name) != null) throw RouterCommandRejected("failure: already have such entry (profile ${spec.name})")
        api.add(umProfilePath, profileAttrs(spec.copy(nameForUsers = spec.nameForUsers ?: spec.name)) + createExtras + ("name" to spec.name))
    }

    override suspend fun updateProfile(api: RouterApi, spec: UmProfileSpec): Boolean {
        val id = api.idOf(umProfilePath, "name", spec.name) ?: return false
        val attrs = profileAttrs(spec)
        if (attrs.isNotEmpty()) api.set(umProfilePath, id, attrs)
        return true
    }

    // ───────────── الروابط ─────────────

    override suspend fun linkLimitation(api: RouterApi, profile: String, limitation: String): Boolean {
        if (api.idOf(umProfilePath, "name", profile) == null) throw RouterCommandRejected("no such profile: $profile")
        if (api.idOf(umLimitationPath, "name", limitation) == null) throw RouterCommandRejected("no such limitation: $limitation")
        if (limitation in linksOf(api, profile)) return false
        api.add(umProfileLimitationPath, mapOf("profile" to profile, "limitation" to limitation))
        return true
    }

    override suspend fun unlinkLimitation(api: RouterApi, profile: String, limitation: String): Boolean {
        val ids = api.print(umProfileLimitationPath, query = mapOf("profile" to profile, "limitation" to limitation), props = listOf(".id"))
            .mapNotNull { it.string(".id") }
        ids.forEach { api.remove(umProfileLimitationPath, it) }
        return ids.isNotEmpty()
    }

    override suspend fun setProfileLimitations(api: RouterApi, profile: String, names: List<String>): UmLinkChange {
        val wanted = names.filter { it.isNotBlank() }.distinct()
        wanted.forEach { if (api.idOf(umLimitationPath, "name", it) == null) throw RouterCommandRejected("no such limitation: $it") }
        val current = linksOf(api, profile)
        val added = wanted.filter { it !in current }
        val removed = current.filter { it !in wanted }
        added.forEach { linkLimitation(api, profile, it) }
        removed.forEach { unlinkLimitation(api, profile, it) }
        return UmLinkChange(added, removed)
    }

    // ───────────── إعادة التسمية والحذف ─────────────

    override suspend fun renameLimitation(api: RouterApi, oldName: String, newName: String): Boolean {
        val id = api.idOf(umLimitationPath, "name", oldName) ?: return false
        if (!oldName.equals(newName, ignoreCase = true) && api.idOf(umLimitationPath, "name", newName) != null) {
            throw RouterCommandRejected("failure: already have such entry (limitation $newName)")
        }
        val before = api.print(umProfileLimitationPath, query = mapOf("limitation" to oldName), props = listOf(".id", "profile", "limitation"))
            .mapNotNull { it.string("profile") }.distinct()
        api.set(umLimitationPath, id, mapOf("name" to newName))
        // الراوتر يحدّث المراجع عادةً؛ لا نفترض ذلك: نعيد ما فُقد من روابط بالاسم الجديد
        before.forEach { if (newName !in linksOf(api, it)) api.add(umProfileLimitationPath, mapOf("profile" to it, "limitation" to newName)) }
        return true
    }

    override suspend fun removeLimitation(api: RouterApi, name: String): Boolean {
        val id = api.idOf(umLimitationPath, "name", name) ?: return false
        api.print(umProfileLimitationPath, query = mapOf("limitation" to name), props = listOf(".id"))
            .mapNotNull { it.string(".id") }.forEach { api.remove(umProfileLimitationPath, it) }
        return api.remove(umLimitationPath, id)
    }

    override suspend fun renameProfile(api: RouterApi, oldName: String, newName: String, syncNameForUsers: Boolean): Boolean {
        val id = api.idOf(umProfilePath, "name", oldName) ?: return false
        if (!oldName.equals(newName, ignoreCase = true) && api.idOf(umProfilePath, "name", newName) != null) {
            throw RouterCommandRejected("failure: already have such entry (profile $newName)")
        }
        val before = linksOf(api, oldName)
        api.set(umProfilePath, id, buildMap { put("name", newName); if (syncNameForUsers) put("name-for-users", newName) })
        val after = linksOf(api, newName)
        before.filter { it !in after }.forEach { api.add(umProfileLimitationPath, mapOf("profile" to newName, "limitation" to it)) }
        return true
    }

    override suspend fun removeProfile(api: RouterApi, name: String): Boolean {
        val id = api.idOf(umProfilePath, "name", name) ?: return false
        api.print(umProfileLimitationPath, query = mapOf("profile" to name), props = listOf(".id"))
            .mapNotNull { it.string(".id") }.forEach { api.remove(umProfileLimitationPath, it) }
        return api.remove(umProfilePath, id)
    }

    // ───────────── الباقة (Profile + Limitations) مع التراجع عند الفشل الجزئي ─────────────

    override suspend fun deletePackage(api: RouterApi, name: String): Boolean {
        val removedProfile = removeProfile(api, name)
        // Limitation المشتق فقط، وفقط إن لم يعد مربوطاً بأي Profile آخر؛ المشترك والخارجي لا يُحذفان أبداً
        val derived = limitationName(name)
        if (api.print(umProfileLimitationPath, query = mapOf("limitation" to derived), props = listOf(".id")).isEmpty()) removeLimitation(api, derived)
        return removedProfile
    }

    override suspend fun renamePackage(api: RouterApi, oldName: String, newName: String): Boolean {
        val profId = api.idOf(umProfilePath, "name", oldName) ?: return false
        if (!oldName.equals(newName, ignoreCase = true) && api.idOf(umProfilePath, "name", newName) != null) {
            throw RouterCommandRejected("failure: already have such entry (profile $newName)")
        }
        val oldDerived = limitationName(oldName)
        val newDerived = limitationName(newName)
        val before = linksOf(api, oldName)
        api.set(umProfilePath, profId, mapOf("name" to newName, "name-for-users" to newName))
        // Limitation المشتق يُعاد تسميته فقط إن كان خاصاً بهذه الباقة؛ المشترك مع Profile آخر يبقى باسمه
        val derivedId = api.idOf(umLimitationPath, "name", oldDerived)
        val derivedIsPrivate = derivedId != null && api.print(umProfileLimitationPath, query = mapOf("limitation" to oldDerived), props = listOf("profile"))
            .all { it.string("profile") in setOf(oldName, newName) }
        val renamedDerived = derivedId != null && derivedIsPrivate && api.idOf(umLimitationPath, "name", newDerived) == null
        if (renamedDerived) api.set(umLimitationPath, derivedId!!, mapOf("name" to newDerived))
        val after = linksOf(api, newName)
        before.map { if (renamedDerived && it == oldDerived) newDerived else it }.filter { it !in after }
            .forEach { api.add(umProfileLimitationPath, mapOf("profile" to newName, "limitation" to it)) }
        return true
    }

    /** خطوة تراجع: وصف يُسجَّل + تنفيذ. تُنفَّذ بترتيب عكسي. */
    private class Undo(val type: UmComponentType?, val target: String, val run: suspend () -> Unit)

    override suspend fun upsertPackage(api: RouterApi, spec: UmPackageSpec, log: UmOpLog) {
        if (spec.profileMode == UmComponentMode.REFERENCE) {
            if (api.idOf(umProfilePath, "name", spec.name) == null) {
                log.add(UmStepAction.FAILED, UmComponentType.PROFILE, spec.name, "missing")
                throw RouterCommandRejected("no such profile: ${spec.name}")
            }
            log.add(UmStepAction.REUSED, UmComponentType.PROFILE, spec.name, "reference, nothing written")
            return
        }
        spec.schedule?.takeIf { spec.willWrite(UmWrite.SCHEDULE) }?.let { sch ->
            val bad = UmScheduleRules.validate(sch)
            if (bad.isNotEmpty()) {
                log.add(UmStepAction.FAILED, null, spec.name, "schedule invalid")
                throw RouterApiException(ApiError(ERR_LIMIT_INVALID, bad.joinToString(",") { "${it.code}:${it.field}" }))
            }
        }
        val undo = ArrayDeque<Undo>()
        try {
            val wanted = spec.effectiveLimitations()
            val primary = wanted.first()
            if (spec.limitationMode == UmComponentMode.MANAGE) {
                writeLimitation(api, spec.toLimitationSpec(primary), spec.name, log, undo)
                wanted.drop(1).forEach { requireLimitation(api, it, log) }
            } else {
                wanted.forEach { requireLimitation(api, it, log) }
            }
            writeProfile(api, spec.toProfileSpec(), log, undo)
            linkAll(api, spec, wanted, log, undo)
        } catch (e: Throwable) {
            log.add(UmStepAction.FAILED, null, spec.name, e.message.orEmpty().take(120))
            withContext(NonCancellable) { rollback(undo, log) }
            throw e
        }
    }

    private suspend fun requireLimitation(api: RouterApi, name: String, log: UmOpLog) {
        if (api.idOf(umLimitationPath, "name", name) == null) {
            log.add(UmStepAction.FAILED, UmComponentType.LIMITATION, name, "missing")
            throw RouterCommandRejected("no such limitation: $name")
        }
        log.add(UmStepAction.REUSED, UmComponentType.LIMITATION, name, "reference, nothing written")
    }

    private suspend fun writeLimitation(api: RouterApi, spec: UmLimitationSpec, ownerProfile: String, log: UmOpLog, undo: ArrayDeque<Undo>) {
        val before = readLimitation(api, spec.name)
        requireValid(before, spec)
        if (before == null) {
            api.add(umLimitationPath, limitationAttrs(spec) + createExtras + ("name" to spec.name))
            log.add(UmStepAction.CREATED, UmComponentType.LIMITATION, spec.name)
            // يُحذف عند التراجع فقط إن لم يربطه Profile آخر في الأثناء (لا يُحذف مشترك)
            undo.addFirst(Undo(UmComponentType.LIMITATION, spec.name) {
                if (api.print(umProfileLimitationPath, query = mapOf("limitation" to spec.name), props = listOf("profile"))
                        .all { it.string("profile").isNullOrBlank() || it.string("profile") == ownerProfile }
                ) removeLimitation(api, spec.name)
            })
            return
        }
        val attrs = limitationAttrs(spec)
        if (attrs.isEmpty()) { log.add(UmStepAction.REUSED, UmComponentType.LIMITATION, spec.name, "no field to write"); return }
        api.set(umLimitationPath, api.idOf(umLimitationPath, "name", spec.name)!!, attrs)
        log.add(UmStepAction.UPDATED, UmComponentType.LIMITATION, spec.name, attrs.keys.joinToString(","))
        undo.addFirst(Undo(UmComponentType.LIMITATION, spec.name) { restoreLimitationValues(api, before, attrs.keys) })
    }

    /**
     * [touched] = مفاتيح الخصائص التي غيّرتها العملية: ما لم يكن له قيمة سابقة مقروءة يُعاد لافتراض RouterOS
     * (priority=8، reset=disabled) كي يتراجع التعديل فعلاً بدل ترك القيمة الجديدة.
     */
    private suspend fun restoreLimitationValues(api: RouterApi, l: UmLimitation, touched: Set<String> = emptySet()) {
        val id = api.idOf(umLimitationPath, "name", l.name) ?: return
        api.set(umLimitationPath, id, mapOf(
            "rate-limit-rx" to UmUnits.formatRate(l.rxKbps), "rate-limit-tx" to UmUnits.formatRate(l.txKbps),
            "uptime-limit" to "${l.uptimeSeconds ?: 0L}s", "transfer-limit" to (l.transferBytes ?: 0L).toString(),
            "download-limit" to (l.downloadBytes ?: 0L).toString(), "upload-limit" to (l.uploadBytes ?: 0L).toString()
        ) + buildMap<String, Any?> {
            putBurst("rx", l.burstRx); putBurst("tx", l.burstTx)
            (l.priority ?: UmLimitRules.PRIORITY_DEFAULT.takeIf { "rate-limit-priority" in touched })?.let { put("rate-limit-priority", it.toString()) }
            if (supportsResetInterval) {
                (l.resetInterval ?: UmResetInterval.DISABLED.takeIf { "reset-counters-interval" in touched })?.let { put("reset-counters-interval", it.wire) }
            }
        })
    }

    private suspend fun restoreProfileValues(api: RouterApi, p: UmProfile) {
        val id = api.idOf(umProfilePath, "name", p.name) ?: return
        api.set(umProfilePath, id, buildMap {
            if (p.nameForUsers.isNotBlank()) put("name-for-users", p.nameForUsers)
            when {
                p.validityUnlimited -> put("validity", "0s")
                p.validitySeconds != null -> put("validity", "${p.validitySeconds}s")
            }
            put("override-shared-users", if (p.sharedUnlimited) "unlimited" else p.sharedUsers.toString())
            p.startMode?.let { put(startAttr, startValue(it)) }
            p.priceMinor?.let { put("price", UmUnits.formatPrice(it)) }
        })
    }

    private suspend fun writeProfile(api: RouterApi, spec: UmProfileSpec, log: UmOpLog, undo: ArrayDeque<Undo>) {
        val before = readProfile(api, spec.name)
        if (before == null) {
            api.add(umProfilePath, profileAttrs(spec) + createExtras + ("name" to spec.name))
            log.add(UmStepAction.CREATED, UmComponentType.PROFILE, spec.name)
            undo.addFirst(Undo(UmComponentType.PROFILE, spec.name) { removeProfile(api, spec.name) })
            return
        }
        val attrs = profileAttrs(spec)
        if (attrs.isEmpty()) { log.add(UmStepAction.REUSED, UmComponentType.PROFILE, spec.name, "no field to write"); return }
        api.set(umProfilePath, api.idOf(umProfilePath, "name", spec.name)!!, attrs)
        log.add(UmStepAction.UPDATED, UmComponentType.PROFILE, spec.name, attrs.keys.joinToString(","))
        undo.addFirst(Undo(UmComponentType.PROFILE, spec.name) { restoreProfileValues(api, before) })
    }

    private suspend fun linkAll(api: RouterApi, spec: UmPackageSpec, wanted: List<String>, log: UmOpLog, undo: ArrayDeque<Undo>) {
        val rows = linkRows(api, spec.name)
        val current = rows.map { it.limitation }.distinct()
        val schedule = spec.schedule?.takeIf { spec.willWrite(UmWrite.SCHEDULE) }
        wanted.filter { it !in current }.forEach { lim ->
            // رابط جديد بجدول افتراضي لا يحتاج خصائص إضافية (القيم الافتراضية في RouterOS هي نفسها: طوال الأسبوع وعلى مدار الساعة)
            val extra = schedule?.takeIf { !it.isAlwaysOn }?.let { UmScheduleRules.attrs(it) }.orEmpty()
            api.add(umProfileLimitationPath, mapOf("profile" to spec.name, "limitation" to lim) + extra)
            log.add(UmStepAction.LINKED, UmComponentType.LIMITATION, lim, "-> ${spec.name}")
            undo.addFirst(Undo(UmComponentType.LIMITATION, lim) { unlinkLimitation(api, spec.name, lim) })
        }
        // روابط قائمة: الجدول يُحدَّث فقط إن اختلف عن المطلوب (إعادة المحاولة لا تكتب شيئاً)، ويُعاد القديم عند التراجع
        if (schedule != null) {
            rows.filter { it.limitation in wanted && it.schedule != schedule }.forEach { row ->
                api.set(umProfileLimitationPath, row.id, UmScheduleRules.attrs(schedule))
                log.add(UmStepAction.UPDATED, UmComponentType.LIMITATION, row.limitation, "schedule")
                undo.addFirst(Undo(UmComponentType.LIMITATION, row.limitation) { api.set(umProfileLimitationPath, row.id, UmScheduleRules.attrs(row.schedule)) })
            }
        }
        // قائمة صريحة = المرجع الدقيق: الروابط الزائدة تُفصل (دون حذف أي Limitation). القائمة المشتقة القديمة لا تفصل شيئاً.
        if (spec.limitationNames.isNotEmpty()) {
            current.filter { it !in wanted }.forEach { lim ->
                unlinkLimitation(api, spec.name, lim)
                log.add(UmStepAction.UNLINKED, UmComponentType.LIMITATION, lim, "<- ${spec.name}")
                undo.addFirst(Undo(UmComponentType.LIMITATION, lim) { linkLimitation(api, spec.name, lim) })
            }
        }
    }

    private suspend fun rollback(undo: ArrayDeque<Undo>, log: UmOpLog) {
        for (u in undo) {
            try {
                u.run()
                log.add(UmStepAction.ROLLED_BACK, u.type, u.target)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.add(UmStepAction.ROLLBACK_FAILED, u.type, u.target, e.message.orEmpty().take(120))
            }
        }
    }

    override suspend fun setProfilePrice(api: RouterApi, name: String, priceMinor: Long): UmProfile? {
        val id = api.idOf(umProfilePath, "name", name) ?: return null
        api.set(umProfilePath, id, mapOf("price" to UmUnits.formatPrice(priceMinor)))
        return readProfile(api, name)
    }

    override suspend fun verifyPackage(api: RouterApi, spec: UmPackageSpec): UmVerification {
        val profile = readProfile(api, spec.name)
        val limits = spec.effectiveLimitations().mapNotNull { n -> readLimitation(api, n)?.let { n to it } }.toMap()
        return UmVerifier.verify(spec, profile, limits)
    }

    // ───────────── الاستعادة من لقطة ─────────────

    override suspend fun restoreSnapshot(api: RouterApi, snapshot: UmSnapshot): List<String> {
        val problems = mutableListOf<String>()
        snapshot.limitation?.let { l ->
            if (api.idOf(umLimitationPath, "name", l.name) == null) {
                api.add(umLimitationPath, mapOf("name" to l.name) + createExtras)
            }
            restoreLimitationValues(api, l)
        }
        snapshot.profile?.let { p ->
            if (api.idOf(umProfilePath, "name", p.name) == null) api.add(umProfilePath, mapOf("name" to p.name) + createExtras)
            restoreProfileValues(api, p)
            val existing = p.limitations.filter { api.idOf(umLimitationPath, "name", it) != null }
            (p.limitations - existing.toSet()).forEach { problems += "limitation missing: $it" }
            setProfileLimitations(api, p.name, existing)
            // نافذة كل رابط كما كانت (الرابط الجديد يُنشأ بالافتراضي؛ يُكتب الجدول صراحةً فقط إن كان مقيِّداً)
            existing.forEach { lim -> p.schedules[lim]?.let { setLinkSchedule(api, p.name, lim, it) } }
        }
        return problems
    }

    // خصائص تختلف بين الجيلين
    /** اسم خاصية بداية الصلاحية: v6 starts-at، v7 starts-when. */
    protected abstract val startAttr: String
    protected abstract fun startValue(mode: UmStartMode): String
    protected abstract fun startModeOf(row: JsonObject): UmStartMode?
    /** خصائص إضافية عند إنشاء Profile/Limitation (v6: owner = العميل). */
    protected open val createExtras: Map<String, Any?> get() = emptyMap()

    // مسارات تختلف بين الجيلين (تعرّفها كل لهجة)
    protected abstract val umUserPath: String
    protected abstract val umProfilePath: String
    protected abstract val umLimitationPath: String
    protected abstract val umProfileLimitationPath: String
    protected abstract val umNasPath: String
    protected abstract val umDatabasePath: String
    protected abstract val umSessionPath: String

    /** أسماء حقول الجلسة حسب الجيل (مسمّاة هنا في مكان واحد؛ v6 غير مثبَّتة على راوتر حقيقي بعد). */
    protected abstract val sessionAddressKeys: List<String>
    protected abstract val sessionStartKeys: List<String>
    protected abstract val sessionEndKeys: List<String>

    protected fun JsonObject.firstOf(keys: List<String>): String? =
        keys.firstNotNullOfOrNull { k -> string(k)?.takeIf { it.isNotBlank() } }

    /** صف جلسة خام ← [UmSession] (دون حساب المدة من الساعة). نقي ويُختبر مباشرة. */
    internal fun parseSession(row: JsonObject): UmSession = UmSession(
        id = row.string(".id").orEmpty(),
        username = row.string("user").orEmpty(),
        active = row.isTrue("active"),
        status = row.string("status").orEmpty(),
        address = row.firstOf(sessionAddressKeys).orEmpty(),
        macAddress = row.string("calling-station-id").orEmpty(),
        startedRaw = row.firstOf(sessionStartKeys),
        uptimeSeconds = UmUnits.parseDurationSeconds(row.string("uptime")),
        uploadBytes = UmUnits.parseBytes(row.string("upload")) ?: 0L,
        downloadBytes = UmUnits.parseBytes(row.string("download")) ?: 0L,
        acctSessionId = row.string("acct-session-id").orEmpty(),
        nasAddress = row.string("nas-ip-address").orEmpty(),
        terminateCause = row.string("terminate-cause").orEmpty(),
        endedRaw = row.firstOf(sessionEndKeys),
        lastAccountingRaw = row.string("last-accounting-packet")?.takeIf { it.isNotBlank() }
    )

    private val sessionProps get() = listOf(".id", "user", "active", "status", "uptime", "upload", "download", "calling-station-id",
        "acct-session-id", "nas-ip-address", "terminate-cause", "last-accounting-packet") +
        sessionAddressKeys + sessionStartKeys + sessionEndKeys

    override suspend fun sessionHistory(api: RouterApi, username: String, limit: Int): List<UmSession> {
        // الاستعلام بالمستخدم على الراوتر (مطابقة حرفية مع اسم الحساب)، والتصفية/الترتيب/الحد على الجهاز
        val rows = api.print(umSessionPath, query = mapOf("user" to username), props = sessionProps)
            .map(::parseSession).filter { it.username.equals(username, ignoreCase = true) }
        return UmSessionHistory.dedupe(rows).take(limit.coerceAtLeast(1))
    }

    override suspend fun liveSessions(api: RouterApi): List<UmSession> {
        // الفلترة على الجهاز لا في استعلام الراوتر: صيغة استعلام الأعلام لم تُثبَّت على راوتر حقيقي، وخطؤها يُخفي جلسات حية بصمت
        val live = api.print(umSessionPath, props = sessionProps).map(::parseSession).filter { it.isLive && it.username.isNotBlank() }
        if (live.isEmpty()) return live
        // UserMan لا يحدّث uptime إلا مع حزم المحاسبة المرحلية؛ الفرق بين وقتين من ساعة الراوتر نفسها أدق ولا يتأثر بالمنطقة الزمنية
        val now = routerClockSeconds(api)
        return live.map { s ->
            val started = UmUnits.parseRouterDateTime(s.startedRaw)
            if (now != null && started != null && now >= started) s.copy(uptimeSeconds = now - started) else s
        }
    }

    private suspend fun routerClockSeconds(api: RouterApi): Long? {
        val row = lenient { api.print("/system/clock", props = listOf("date", "time")).firstOrNull() } ?: return null
        return UmUnits.parseRouterDateTime("${row.string("date").orEmpty()} ${row.string("time").orEmpty()}")
    }

    /** فحوص خاصة بالجيل (الحزمة/التفعيل/العميل/سجل NAS) مع السر المشترك المقروء من سجل NAS. */
    protected class Engine(
        val installed: Boolean,
        val enabled: Boolean?,
        val useProfiles: Boolean?,
        val dbPath: String?,
        val dbSizeBytes: Long?,
        val nasSecret: String?,
        val checks: List<UmCheck>
    )

    protected abstract suspend fun inspectEngine(api: RouterApi, cfg: UserManConfig): Engine

    /** تفعيل User Manager نفسه (v7). null = لا ينطبق على هذا الجيل. */
    protected open suspend fun ensureEnabled(api: RouterApi): Pair<UmAction, String>? = null

    /** يُنشئ/يحدّث سجل NAS للراوتر نفسه بالسر المشترك. */
    protected abstract suspend fun ensureNas(api: RouterApi, cfg: UserManConfig): Pair<UmAction, String>

    /** قراءة متسامحة: أي فشل ← null (الحزمة غير مثبتة/المسار غير موجود)، مع إعادة رمي إلغاء الـ coroutine. */
    protected suspend fun <T> lenient(block: suspend () -> T): T? =
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }

    protected fun JsonObject.isHotspotRadius(): Boolean =
        string("service").orEmpty().split(',').any { it.trim().equals("hotspot", ignoreCase = true) }

    /** عنوان NAS قد يكون قائمة/نطاقاً؛ نطابق العنصر حرفياً. */
    protected fun addressMatches(raw: String?, wanted: String): Boolean =
        raw.orEmpty().split(',').any { it.trim() == wanted.trim() }

    override suspend fun inspect(api: RouterApi, cfg: UserManConfig, routerOsVersion: String): UmStatus {
        val e = inspectEngine(api, cfg)
        val checks = e.checks.toMutableList()

        val radiusRows = lenient { api.print("/radius") }.orEmpty()
        val radiusEntry = radiusRows.firstOrNull { it.isHotspotRadius() && addressMatches(it.string("address"), cfg.radiusAddress) && !it.isTrue("disabled") }
        checks += UmCheck(UmCheckKeys.RADIUS, radiusEntry != null, "hotspot radius entry for ${cfg.radiusAddress}")

        val radiusSecret = radiusEntry?.string("secret")
        val secretOk = !radiusSecret.isNullOrEmpty() && radiusSecret == e.nasSecret &&
            (cfg.radiusSecret.isBlank() || radiusSecret == cfg.radiusSecret)
        checks += UmCheck(UmCheckKeys.SECRET_MATCH, secretOk, "radius secret matches user-manager router secret")

        val hp = lenient { api.print("/ip/hotspot/profile") }
        checks += UmCheck(
            UmCheckKeys.USE_RADIUS,
            !hp.isNullOrEmpty() && hp.all { it.isTrue("use-radius") },
            "hotspot server profiles use-radius"
        )

        // قائمة غير مقروءة (لا صف) = لا فحص: لا نُظهر فشلاً لا نستطيع إثباته
        val incoming = lenient { api.print("/radius/incoming").firstOrNull() }
        if (incoming != null) {
            checks += UmCheck(UmCheckKeys.INCOMING, incoming.isTrue("accept"), "radius incoming accept=${incoming.string("accept")}")
        }

        val users = if (e.installed) lenient { api.print(umUserPath, props = listOf(".id")).size } ?: 0 else 0
        val packages = if (e.installed) lenient { api.print(umProfilePath, props = listOf(".id")).size } ?: 0 else 0
        return UmStatus(
            generation = generation, routerOsVersion = routerOsVersion, packageInstalled = e.installed,
            enabled = e.enabled, useProfiles = e.useProfiles, dbPath = e.dbPath, dbSizeBytes = e.dbSizeBytes,
            userCount = users, packageCount = packages, checks = checks
        )
    }

    override suspend fun provision(api: RouterApi, cfg: UserManConfig): List<UmProvisionStep> = buildList {
        ensureEnabled(api)?.let { add(UmProvisionStep(UmStep.ENABLE, it.first, it.second)) }
        add(runStep(UmStep.NAS) { ensureNas(api, cfg) })
        add(runStep(UmStep.RADIUS) { ensureRadius(api, cfg) })
        add(runStep(UmStep.HOTSPOT_PROFILES) { ensureHotspotProfiles(api) })
        // يُضاف فقط إن كانت القائمة مقروءة: فصل الجلسة عند بلوغ الحد يحتاج قبول الراوتر لطلبات Disconnect من User Manager
        if (lenient { api.print("/radius/incoming").firstOrNull() } != null) add(runStep(UmStep.INCOMING) { ensureIncoming(api) })
    }

    private suspend fun ensureIncoming(api: RouterApi): Pair<UmAction, String> {
        val row = api.print("/radius/incoming").firstOrNull() ?: return UmAction.UNCHANGED to ""
        if (row.isTrue("accept")) return UmAction.UNCHANGED to ""
        api.setSingle("/radius/incoming", mapOf("accept" to "yes"))
        return UmAction.UPDATED to "accept"
    }

    /** رفض الراوتر لخطوة يُسجَّل فشلاً لها فقط؛ أخطاء الاتصال/المصادقة تصعد كما هي (المدير المركزي يتولاها). */
    protected suspend fun runStep(step: UmStep, block: suspend () -> Pair<UmAction, String>): UmProvisionStep =
        try {
            block().let { UmProvisionStep(step, it.first, it.second) }
        } catch (e: RouterCommandRejected) {
            UmProvisionStep(step, UmAction.FAILED, e.routerMessage.take(160))
        }

    private suspend fun ensureRadius(api: RouterApi, cfg: UserManConfig): Pair<UmAction, String> {
        val path = "/radius"
        val rows = api.print(path)
        val entry = rows.firstOrNull { it.isHotspotRadius() && addressMatches(it.string("address"), cfg.radiusAddress) }
        if (entry == null) {
            api.add(path, mapOf("service" to "hotspot", "address" to cfg.radiusAddress, "secret" to cfg.radiusSecret))
            return UmAction.CREATED to cfg.radiusAddress
        }
        val id = requireId(entry.string(".id"), "radius entry")
        val attrs = buildMap<String, Any?> {
            if (entry.string("secret") != cfg.radiusSecret) put("secret", cfg.radiusSecret)
            if (entry.isTrue("disabled")) put("disabled", "no")
        }
        if (attrs.isEmpty()) return UmAction.UNCHANGED to cfg.radiusAddress
        api.set(path, id, attrs)
        return UmAction.UPDATED to cfg.radiusAddress
    }

    private suspend fun ensureHotspotProfiles(api: RouterApi): Pair<UmAction, String> {
        val path = "/ip/hotspot/profile"
        val pending = api.print(path, props = listOf(".id", "name", "use-radius")).filter { !it.isTrue("use-radius") }
        pending.forEach { api.set(path, requireId(it.string(".id"), "hotspot profile"), mapOf("use-radius" to "yes")) }
        return if (pending.isEmpty()) UmAction.UNCHANGED to "" else UmAction.UPDATED to pending.size.toString()
    }

    /**
     * قراءة الباقات من الراوتر عبر روابط profile-limitation الحقيقية (لا بالتسمية وحدها):
     * قيم الباقة تؤخذ من limitation التطبيق `mgtik-<name>` إن وُجد، وإلا من أول limitation مربوط (باقة أنشأها المدير يدوياً).
     * ما عدا ذلك يُعرض في [UmPackage.foreignLimitations] كي لا يُخفى قيد يؤثر على المستخدمين.
     */
    override suspend fun listPackages(api: RouterApi): List<UmPackage> {
        val limits = api.print(umLimitationPath).associateBy { it.string("name").orEmpty() }
        val links = api.print(umProfileLimitationPath).groupBy({ it.string("profile").orEmpty() }, { it.string("limitation").orEmpty() })
        return api.print(umProfilePath).mapNotNull { p ->
            val name = p.string("name") ?: return@mapNotNull null
            val own = limitationName(name)
            val linked = links[name].orEmpty().filter { it.isNotBlank() }.distinct()
            // قيد التطبيق غير المربوط لا يُعدّ قيم الباقة: لا يُفرض على الراوتر، وإظهاره كأنه مطبَّق يُخفي إنشاءً ناقصاً (limitationLinked)
            val primary = if (own in linked) own else linked.firstOrNull()
            val l = primary?.let { limits[it] }
            val validityRaw = p.string("validity")?.trim().orEmpty()
            val validity = UmUnits.parseDurationSeconds(validityRaw)
            UmPackage(
                name = name,
                rxKbps = UmUnits.parseRateKbps(l?.string("rate-limit-rx")),
                txKbps = UmUnits.parseRateKbps(l?.string("rate-limit-tx")),
                sharedUsers = p.string("override-shared-users")?.toIntOrNull() ?: 1,
                uptimeSeconds = UmUnits.parseDurationSeconds(l?.string("uptime-limit"))?.takeIf { it > 0 },
                transferBytes = UmUnits.parseBytes(l?.string("transfer-limit"))?.takeIf { it > 0 },
                validitySeconds = validity?.takeIf { it > 0 },
                validityUnlimited = validity == 0L || validityRaw.equals("unlimited", ignoreCase = true),
                startMode = startModeOf(p),
                foreignLimitations = linked.filter { it != primary },
                limitationLinked = own in linked,
                limitationOrphaned = own in limits && own !in linked,
                sharedUnlimited = p.string("override-shared-users").equals("unlimited", ignoreCase = true),
                downloadBytes = UmUnits.parseBytes(l?.string("download-limit"))?.takeIf { it > 0 },
                uploadBytes = UmUnits.parseBytes(l?.string("upload-limit"))?.takeIf { it > 0 }
            )
        }
    }

    override suspend fun backupDatabase(api: RouterApi, name: String) {
        api.command("$umDatabasePath/save", backupArgs(name))
    }

    protected open fun backupArgs(name: String): Map<String, Any?> = mapOf("name" to name)

    override suspend fun optimizeDatabase(api: RouterApi): Boolean = false

    override suspend fun orphanLimitations(api: RouterApi): List<String> {
        val profiles = api.print(umProfilePath, props = listOf("name")).mapNotNull { it.string("name") }.toHashSet()
        return api.print(umLimitationPath, props = listOf("name")).mapNotNull { it.string("name") }
            .filter { it.startsWith(LIMITATION_PREFIX) && it.removePrefix(LIMITATION_PREFIX) !in profiles }
    }

    override suspend fun removeLimitations(api: RouterApi, names: Collection<String>): Int {
        var removed = 0
        for (name in names) {
            // حماية: لا نحذف إلا ما يحمل بادئة التطبيق مهما كان المُدخَل
            if (!name.startsWith(LIMITATION_PREFIX)) continue
            val id = api.idOf(umLimitationPath, "name", name) ?: continue
            api.print(umProfileLimitationPath, query = mapOf("limitation" to name), props = listOf(".id"))
                .mapNotNull { it.string(".id") }.forEach { api.remove(umProfileLimitationPath, it) }
            if (api.remove(umLimitationPath, id)) removed++
        }
        return removed
    }

    companion object {
        /** بادئة Limitations التي ينشئها التطبيق (mgtik-<اسم الباقة>). */
        const val LIMITATION_PREFIX = UM_LIMITATION_PREFIX
        /** إعدادات Limitation غير صالحة/متعارضة (Burst، حدّا التنزيل/الرفع): الرسالة = "code:field,..." ولا يُكتب شيء. */
        const val ERR_LIMIT_INVALID = "ERR_UM_LIMIT_INVALID"
    }
}

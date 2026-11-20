package ps.mgtik.hotspot.data.userman

import ps.mgtik.hotspot.core.network.RouterApi
import ps.mgtik.hotspot.core.network.RouterApiException
import ps.mgtik.hotspot.core.network.RouterCommandRejected
import ps.mgtik.hotspot.core.network.RouterConfig
import ps.mgtik.hotspot.core.network.RouterOpKind
import ps.mgtik.hotspot.core.network.RouterConnectionManager
import ps.mgtik.hotspot.core.network.string
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.generation.CreateOutcome
import ps.mgtik.hotspot.domain.generation.RouterTags
import ps.mgtik.hotspot.domain.generation.SagaError
import ps.mgtik.hotspot.domain.model.RouterDisableState
import ps.mgtik.hotspot.domain.model.RouterUserSnapshot
import ps.mgtik.hotspot.domain.model.UserManConfig
import ps.mgtik.hotspot.domain.model.UserManMode
import ps.mgtik.hotspot.domain.repository.ISettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import ps.mgtik.hotspot.domain.usecase.UserEditRules
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UserManSource - المصدر الوحيد للتعامل مع مستخدمي وباقات الراوتر. يحل محل RouterUserDataSource وRouterProfileDataSource
 * (لا حسابات ولا باقات على الراوتر خارجه). يختار اللهجة (v6 أو v7) من إصدار RouterOS ويخفيها عن بقية التطبيق.
 * `/ip/hotspot/active` (الجلسات) و`/ip/hotspot` (السيرفرات) ليسا مستخدمين ويبقيان خارج هذا الصنف.
 */
@Singleton
class UserManSource @Inject constructor(
    private val connectionManager: RouterConnectionManager,
    private val settingsRepository: ISettingsRepository
) {
    private val dialects = ConcurrentHashMap<String, UserManDialect>()
    private val versions = ConcurrentHashMap<String, String>()
    @Volatile private var cachedConfig: UserManConfig? = null

    // المستخدم جزء من المفتاح: تغيير الحساب/الراوتر على نفس العنوان لا يعيد استعمال لهجة أو إصدار راوتر آخر
    private fun keyOf(config: RouterConfig) = "${config.host}:${config.port}:${config.username}"

    /**
     * إعداد User Manager المحفوظ (جيل/عميل/RADIUS). يُقرأ من السحابة مرة ويُخزَّن؛ تعذّر القراءة (مهلة/عطل) يعيد الافتراضي
     * دون تخزينه فتُعاد المحاولة في الطلب التالي. [applyConfig] يستبدله فور الحفظ.
     */
    suspend fun currentConfig(): UserManConfig {
        cachedConfig?.let { return it }
        val loaded = try {
            withTimeoutOrNull(CONFIG_LOAD_TIMEOUT_MS) { settingsRepository.getUserManConfig().first() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return loaded?.also { cachedConfig = it } ?: UserManConfig()
    }

    /** يعتمد إعداداً جديداً فوراً ويُسقط اللهجات المخزَّنة لتُبنى من جديد بالإعداد الجديد. */
    fun applyConfig(config: UserManConfig) {
        cachedConfig = config
        dialects.clear()
    }

    /** يُسقط كل ما خُزِّن (إعداد ولهجات وإصدارات) فيُعاد الاكتشاف من الراوتر في الطلب التالي (مثلاً بعد ترقية RouterOS). */
    fun invalidate() {
        cachedConfig = null
        dialects.clear()
        versions.clear()
    }

    private suspend fun versionFor(config: RouterConfig, api: RouterApi, refresh: Boolean = false): String {
        if (!refresh) versions[keyOf(config)]?.let { return it }
        val v = api.print("/system/resource").firstOrNull()?.string("version").orEmpty()
        // لا افتراض v6: إصدار غير مقروء/غير مفهوم = خطأ صريح ولا يُخزَّن، فيُعاد الاكتشاف في الطلب التالي
        if (majorOf(v) == null) {
            versions.remove(keyOf(config))
            throw RouterApiException(ApiError(ERR_ROUTER_VERSION_UNKNOWN, "Cannot determine the RouterOS version from /system/resource (got '${v.take(40)}')", retryable = true))
        }
        versions[keyOf(config)] = v
        return v
    }

    /** يحدد اللهجة: من الإعداد إن فُرض جيل، وإلا من إصدار RouterOS (الرئيسي ≥ 7 ← v7، وإلا v6)؛ ويحفظها لكل راوتر. */
    suspend fun dialectFor(config: RouterConfig, api: RouterApi): UserManDialect {
        dialects[keyOf(config)]?.let { return it }
        val cfg = currentConfig()
        val d = when (cfg.mode) {
            UserManMode.V6 -> UserManV6Dialect(cfg.customer)
            UserManMode.V7 -> UserManV7Dialect()
            UserManMode.AUTO -> dialectForVersion(versionFor(config, api), cfg.customer)
                ?: throw RouterApiException(ApiError(ERR_ROUTER_VERSION_UNKNOWN, "Unrecognised RouterOS version", retryable = true))
        }
        dialects[keyOf(config)] = d
        return d
    }

    private suspend fun <T> run(
        config: RouterConfig,
        kind: RouterOpKind = RouterOpKind.STANDARD,
        block: suspend (RouterApi, UserManDialect) -> T
    ): AppResult<T> =
        connectionManager.execute(config, kind = kind) { api ->
            val d = dialectFor(config, api)
            try {
                block(api, d)
            } catch (e: RouterCommandRejected) {
                // عدم تطابق مسار (القائمة غير موجودة): نعيد قراءة الإصدار من /system/resource فقط (لا تبديل عشوائي للهجة).
                // نُعيد التنفيذ مرة واحدة وفقط إذا تبيّن أن الجيل الفعلي مختلف عمّا خُزِّن؛ وإلا يُرمى الخطأ الأصلي.
                if (!looksLikePathMismatch(e.routerMessage) || currentConfig().mode != UserManMode.AUTO) throw e
                dialects.remove(keyOf(config)); versions.remove(keyOf(config))
                val fresh = dialectFor(config, api)
                if (fresh.generation == d.generation) throw e
                block(api, fresh)
            }
        }

    /** للمستدعين الذين يدمجون عدة عمليات في اتصال واحد (مثل منفّذ الاشتراكات). */
    suspend fun <T> withDialect(
        config: RouterConfig,
        kind: RouterOpKind = RouterOpKind.STANDARD,
        block: suspend (RouterApi, UserManDialect) -> T
    ): AppResult<T> = run(config, kind, block)

    /** إعدادات الباقة التشغيلية للمقارنة مع Cloud: tx = تنزيل، rx = رفع. null = الباقة غير موجودة. */
    suspend fun getProfileSettings(config: RouterConfig, name: String): AppResult<ps.mgtik.hotspot.domain.usecase.RouterProfileSettings?> =
        run(config) { api, d ->
            val um = d.listProfiles(api).firstOrNull { it.name == name }
            d.listPackages(api).firstOrNull { it.name == name }?.let {
                ps.mgtik.hotspot.domain.usecase.RouterProfileSettings(
                    downloadKbps = it.txKbps, uploadKbps = it.rxKbps, sharedUsers = it.sharedUsers,
                    uptimeSeconds = it.uptimeSeconds, transferBytes = it.transferBytes,
                    limitsKnown = true, limitationLinked = !it.limitationOrphaned, sharedUnlimited = it.sharedUnlimited,
                    startMode = um?.startMode?.let { m -> if (m == UmStartMode.ON_ASSIGN) "assigned" else "first_auth" },
                    validitySeconds = um?.validitySeconds, validityUnlimited = um?.validityUnlimited == true,
                    validityKnown = um != null
                )
            }
        }

    suspend fun usage(config: RouterConfig, name: String): AppResult<UmUsage> = run(config) { api, d -> d.usage(api, name) }

    /**
     * تحقق دفعي بعد الإنشاء: قراءة User Manager مرة واحدة (لا قراءة لكل مستخدم) ومطابقة كل اسم من [names] مع الباقة [packageName].
     * يعيد (اسم ← سبب الفشل) لمن لم يتحقق فقط: غير موجود، أو بلا الباقة المطلوبة، أو معطَّل.
     */
    suspend fun verifyCreatedUsers(config: RouterConfig, names: Collection<String>, packageName: String): AppResult<Map<String, String>> =
        run(config, RouterOpKind.BULK) { api, d ->
            val byName = d.listUsers(api).associateBy { it.name.lowercase() }
            buildMap {
                for (n in names) {
                    val u = byName[n.lowercase()]
                    when {
                        u == null -> put(n, "missing")
                        // v6 تقرأ الباقة من actual-profile (الجارية الآن فقط): باقة بدايتها عند أول دخول تبقى فارغة قبله،
                        // فالفراغ هناك «غير قابل للتحقق» لا فشل (لا نحذف حساباً سليماً). باقة مختلفة غير فارغة فشل في الجيلين
                        u.packageName == null && d.generation == UmGeneration.V6 -> if (u.disabled) put(n, "disabled")
                        u.packageName != packageName -> put(n, "profile=${u.packageName ?: "none"}")
                        u.disabled -> put(n, "disabled")
                    }
                }
            }
        }

    /**
     * أسماء من [expected] (اسم ← كلمة المرور المحفوظة) يحمل حسابها على الراوتر تعليقاً غير [comment] أو كلمة مرور مختلفة:
     * أي حساب لا نملكه (اسم مأخوذ لمستخدم آخر) أو كرت كلمة مروره لن تعمل. كلمة مرور غير مقروءة (null/فارغة) = غير قابلة
     * للتحقق فلا تُعدّ فشلاً. الاسم غير الموجود لا يُعدّ هنا (يلتقطه [verifyCreatedUsers]). قراءة دفعية واحدة، ولا تعديل.
     */
    suspend fun credentialMismatches(config: RouterConfig, expected: Map<String, String>, comment: String): AppResult<Set<String>> =
        run(config, RouterOpKind.BULK) { api, d ->
            val byName = d.listUsers(api).associateBy { it.name.lowercase() }
            expected.filter { (name, password) ->
                val u = byName[name.lowercase()] ?: return@filter false
                u.comment.orEmpty().trim() != comment.trim() ||
                    (!u.password.isNullOrBlank() && u.password != password)
            }.keys.toSet()
        }

    // ---------------- الجاهزية ----------------

    suspend fun generation(config: RouterConfig): AppResult<UmGeneration> = run(config) { _, d -> d.generation }

    // ---------------- الباقات ----------------

    suspend fun listPackages(config: RouterConfig): AppResult<List<UmPackage>> = run(config, RouterOpKind.BULK) { api, d -> d.listPackages(api) }

    suspend fun upsertPackage(config: RouterConfig, spec: UmPackageSpec, log: UmOpLog = UmOpLog()): AppResult<Unit> =
        run(config) { api, d -> d.upsertPackage(api, spec, log) }

    /** يدفع سعر التجزئة لحقل price على Profile قائم ويعيد ما قرأه بعد الكتابة (null = الباقة غير موجودة، لم يُكتب شيء). */
    suspend fun setProfilePrice(config: RouterConfig, name: String, priceMinor: Long): AppResult<UmProfile?> =
        run(config) { api, d -> d.setProfilePrice(api, name, priceMinor) }

    /** قراءة بعد الكتابة: ما على الراوتر الآن مقابل [spec] (لا نعتمد Room/Firestore دليلاً على النجاح). */
    suspend fun verifyPackage(config: RouterConfig, spec: UmPackageSpec): AppResult<UmVerification> =
        run(config) { api, d -> d.verifyPackage(api, spec) }

    // ---------------- Profiles وLimitations كمكونات مستقلة ----------------

    suspend fun readGraph(config: RouterConfig): AppResult<UmGraph> = run(config, RouterOpKind.BULK) { api, d -> d.readGraph(api) }
    suspend fun listProfiles(config: RouterConfig): AppResult<List<UmProfile>> = run(config, RouterOpKind.BULK) { api, d -> d.listProfiles(api) }
    suspend fun listLimitations(config: RouterConfig): AppResult<List<UmLimitation>> = run(config, RouterOpKind.BULK) { api, d -> d.listLimitations(api) }
    suspend fun createLimitation(config: RouterConfig, spec: UmLimitationSpec): AppResult<Unit> = run(config) { api, d -> d.createLimitation(api, spec) }
    suspend fun updateLimitation(config: RouterConfig, spec: UmLimitationSpec): AppResult<Boolean> = run(config) { api, d -> d.updateLimitation(api, spec) }
    suspend fun renameLimitation(config: RouterConfig, old: String, new: String): AppResult<Boolean> = run(config) { api, d -> d.renameLimitation(api, old, new) }
    suspend fun removeLimitation(config: RouterConfig, name: String): AppResult<Boolean> = run(config) { api, d -> d.removeLimitation(api, name) }
    suspend fun createProfile(config: RouterConfig, spec: UmProfileSpec): AppResult<Unit> = run(config) { api, d -> d.createProfile(api, spec) }
    suspend fun updateProfile(config: RouterConfig, spec: UmProfileSpec): AppResult<Boolean> = run(config) { api, d -> d.updateProfile(api, spec) }
    suspend fun renameProfile(config: RouterConfig, old: String, new: String, syncNameForUsers: Boolean = false): AppResult<Boolean> =
        run(config) { api, d -> d.renameProfile(api, old, new, syncNameForUsers) }
    suspend fun removeProfile(config: RouterConfig, name: String): AppResult<Boolean> = run(config) { api, d -> d.removeProfile(api, name) }
    suspend fun setProfileLimitations(config: RouterConfig, profile: String, names: List<String>): AppResult<UmLinkChange> =
        run(config) { api, d -> d.setProfileLimitations(api, profile, names) }
    suspend fun linkLimitation(config: RouterConfig, profile: String, limitation: String): AppResult<Boolean> = run(config) { api, d -> d.linkLimitation(api, profile, limitation) }
    suspend fun unlinkLimitation(config: RouterConfig, profile: String, limitation: String): AppResult<Boolean> = run(config) { api, d -> d.unlinkLimitation(api, profile, limitation) }
    suspend fun restoreSnapshot(config: RouterConfig, snapshot: UmSnapshot): AppResult<List<String>> = run(config, RouterOpKind.BULK) { api, d -> d.restoreSnapshot(api, snapshot) }

    suspend fun deletePackage(config: RouterConfig, name: String): AppResult<Unit> =
        run(config) { api, d -> d.deletePackage(api, name); Unit }

    suspend fun renamePackage(config: RouterConfig, oldName: String, newName: String): AppResult<Boolean> =
        run(config) { api, d -> d.renamePackage(api, oldName, newName) }

    // ---------------- المستخدمون ----------------

    suspend fun usersOnPackage(config: RouterConfig, packageName: String): AppResult<List<String>> =
        run(config, RouterOpKind.BULK) { api, d -> d.usersOnPackage(api, packageName) }

    suspend fun listUsers(config: RouterConfig): AppResult<List<UmUser>> = run(config, RouterOpKind.BULK) { api, d -> d.listUsers(api) }

    suspend fun exists(config: RouterConfig, name: String): AppResult<Boolean> =
        run(config) { api, d -> d.findUser(api, name) != null }

    suspend fun createUser(
        config: RouterConfig, name: String, password: String, comment: String, packageName: String?, disabled: Boolean
    ): AppResult<Unit> = run(config) { api, d -> d.createUser(api, name, password, comment, packageName, disabled) }

    /**
     * إنشاء مصنّف النتيجة لمسار التوليد: Created، أو AlreadyExists (اسم مأخوذ)، أو Rejected (رفض الراوتر)،
     * أو Transient/Fatal للأخطاء العابرة والنهائية. نفس عقد المصدر القديم حتى لا يتغير منطق الـ Saga.
     */
    suspend fun createUserClassified(
        config: RouterConfig, name: String, password: String, comment: String, packageName: String?, disabled: Boolean
    ): CreateOutcome {
        val result = run(config) { api, d ->
            try {
                d.createUser(api, name, password, comment, packageName, disabled)
                CreateOutcome.Created
            } catch (e: RouterCommandRejected) {
                if (e.routerMessage.contains("already have", ignoreCase = true) ||
                    e.routerMessage.contains("already exists", ignoreCase = true)
                ) {
                    // إنشاء جزئي سابق (الحساب أُضيف وفشل إسناد الباقة): نكمل الإسناد بدل إعلانه «موجود» دون باقة.
                    // لا نلمس إلا حساباً بلا باقة وبتعليقنا نفسه، كي لا نُسند باقة لحساب غريب يحمل الاسم نفسه.
                    var existing = d.findUser(api, name)
                    if (packageName != null && existing != null && existing.packageName.isNullOrBlank() &&
                        existing.comment.orEmpty().trim() == comment.trim()
                    ) {
                        d.activatePackage(api, name, packageName)
                        existing = d.findUser(api, name)
                    }
                    // «تحقق قبل إعادة التنفيذ»: الحساب الموجود هو حسابنا نفسه (اسم+تعليق+كلمة مرور+باقة+حالة) فمحاولة سابقة
                    // انقطع ردّها نجحت؛ نعتمد النتيجة دون تكرار ولا نعلنه تصادم اسم
                    if (UserOperationVerifier.isSameUser(existing, comment, password, packageName, disabled, requirePassword = true)) {
                        CreateOutcome.Created
                    } else CreateOutcome.AlreadyExists
                }
                else CreateOutcome.Rejected(e.routerMessage.take(160).ifBlank { "Rejected by router" })
            }
        }
        // انقطع الاتصال أثناء الطلب: ربما نفّذه الراوتر وضاع الرد. نفحص حالة الراوتر أولاً ولا نعيد الإنشاء مباشرة
        if (result is AppResult.Error && UserOperationVerifier.isAmbiguousSendError(result.error.code)) {
            val check = run(config) { api, d -> d.findUser(api, name) }
            if (check is AppResult.Success &&
                UserOperationVerifier.isSameUser(check.data, comment, password, packageName, disabled, requirePassword = false)
            ) return CreateOutcome.Created
        }
        return when (result) {
            is AppResult.Success -> result.data
            is AppResult.Error -> if (result.error.retryable ||
                result.error.code == ApiError.CODE_NETWORK_UNAVAILABLE || result.error.code == ApiError.CODE_TIMEOUT
            ) CreateOutcome.Transient(SagaError(result.error.code, result.error.message, true))
            else CreateOutcome.Fatal(SagaError(result.error.code, result.error.message, false))
            else -> CreateOutcome.Transient(SagaError("ERR_UNEXPECTED", "Unexpected loading state", true))
        }
    }

    /** أسماء المستخدمين مع تعليقاتهم (لأوسمة الدفعات MGTIK-...). */
    suspend fun getUsernamesWithComments(config: RouterConfig): AppResult<List<Pair<String, String>>> =
        run(config, RouterOpKind.BULK) { api, d -> d.listUserNames(api) }

    suspend fun deleteByComment(config: RouterConfig, tag: String): AppResult<Int> = run(config, RouterOpKind.BULK) { api, d ->
        val names = d.listUserNames(api).filter { RouterTags.matches(it.second, tag) }.map { it.first }
        names.forEach { d.deleteUser(api, it) }
        names.size
    }

    suspend fun deleteByUsernames(config: RouterConfig, usernames: Collection<String>): AppResult<Int> = run(config, RouterOpKind.BULK) { api, d ->
        val wanted = usernames.map { it.lowercase() }.toHashSet()
        val names = d.listUserNames(api).map { it.first }.filter { it.lowercase() in wanted }
        names.forEach { d.deleteUser(api, it) }
        names.size
    }

    /** حذف مع تحقق بقراءة ثانية: true = حُذف، false = لم يكن موجوداً. */
    suspend fun deleteAndVerify(config: RouterConfig, name: String): AppResult<Boolean> = run(config) { api, d ->
        if (d.findUser(api, name) == null) return@run false
        d.deleteUser(api, name)
        if (d.findUser(api, name) != null) {
            throw RouterApiException(ApiError(UserEditRules.ERR_ROUTER_DELETE_UNVERIFIED, "User $name still exists on the router after delete", retryable = true))
        }
        true
    }

    /** يفرض حالة التعطيل (disabled) ويتحقق منها. false = المستخدم غير موجود. */
    suspend fun enforceDisabledState(config: RouterConfig, name: String, disabled: Boolean): AppResult<Boolean> = run(config) { api, d ->
        val existing = d.findUser(api, name) ?: return@run false
        if (existing.disabled != disabled) d.setDisabled(api, name, disabled)
        if (d.findUser(api, name)?.disabled != disabled) {
            throw RouterApiException(ApiError(UserEditRules.ERR_STATE_UNVERIFIED, "User $name did not reach disabled=$disabled on the router", retryable = true))
        }
        true
    }

    /** الأرشفة: تعطيل مع تحقق. ABSENT إن لم يكن المستخدم موجوداً. */
    suspend fun disableAndVerify(config: RouterConfig, name: String): AppResult<RouterDisableState> = run(config) { api, d ->
        val existing = d.findUser(api, name) ?: return@run RouterDisableState.ABSENT
        if (!existing.disabled) d.setDisabled(api, name, true)
        val after = d.findUser(api, name) ?: return@run RouterDisableState.ABSENT
        if (!after.disabled) {
            throw RouterApiException(ApiError(UserEditRules.ERR_ARCHIVE_ROUTER_UNVERIFIED, "User $name is still enabled on the router after disable", retryable = true))
        }
        RouterDisableState.DISABLED
    }

    /** التجديد/تغيير الباقة: إسناد باقة (فترة) جديدة. */
    suspend fun activatePackage(config: RouterConfig, name: String, packageName: String): AppResult<Unit> {
        // null = لم نصل لقراءة الحالة (العملية لم تُرسل بعد)؛ false = لم يكن يحمل الباقة قبل المحاولة
        var hadPackageBefore: Boolean? = null
        val result = run(config) { api, d ->
            val existing = d.findUser(api, name) ?: throw RouterCommandRejected("no such user on the router: $name")
            hadPackageBefore = existing.packageName == packageName
            d.activatePackage(api, name, packageName)
        }
        // انقطاع الرد بعد وصول الأمر: إن صار المستخدم يحمل الباقة ولم يكن يحملها قبلاً فالتفعيل نجح (لا إعادة تنفيذ)
        if (result is AppResult.Error && UserOperationVerifier.isAmbiguousSendError(result.error.code) && hadPackageBefore == false) {
            val check = run(config) { api, d -> d.findUser(api, name) }
            if (check is AppResult.Success && check.data?.packageName == packageName) return AppResult.Success(Unit)
        }
        return result
    }

    /** لقطة المستخدم: الباقة وحدودها من الباقة المُسندة، والاستهلاك مجمّع من جلسات UserMan. */
    suspend fun getUserSnapshot(config: RouterConfig, name: String): AppResult<RouterUserSnapshot?> = run(config) { api, d ->
        val u = d.findUser(api, name) ?: return@run null
        val pkg = u.packageName?.let { n -> d.listPackages(api).firstOrNull { it.name == n } }
        val usage = d.periodUsage(api, name)
        val total = d.usage(api, name)
        RouterUserSnapshot(
            password = u.password?.takeIf { it.isNotBlank() },
            profile = u.packageName,
            comment = u.comment?.takeIf { it.isNotBlank() },
            disabled = u.disabled,
            limitUptimeSeconds = pkg?.uptimeSeconds,
            limitTransferBytes = pkg?.transferBytes,
            limitDownloadBytes = pkg?.downloadBytes,
            limitUploadBytes = pkg?.uploadBytes,
            usedDownloadBytes = usage.downloadBytes,
            usedUploadBytes = usage.uploadBytes,
            usedUptimeSeconds = usage.uptimeSeconds,
            usedBytes = usage.uploadBytes + usage.downloadBytes,
            totalUptimeSeconds = total.uptimeSeconds,
            totalBytes = total.uploadBytes + total.downloadBytes
        )
    }

    // ---------------- تشخيص القراءات (S32) ----------------

    /**
     * يقرأ الباقات والحدود والمستخدمين والجلسات كلاً على حدة ويعيد نتيجة كل قراءة (قراءة فقط)،
     * فيُعرض أيها فشل وبأي سبب بدل رسالة عامة. تتابع القراءات: فشل إحداها لا يمنع الباقي.
     */
    suspend fun readChecks(config: RouterConfig): List<UmReadCheck> = listOf(
        UmReadKind.PACKAGES to suspend { listPackages(config) },
        UmReadKind.LIMITATIONS to suspend { listLimitations(config) },
        UmReadKind.USERS to suspend { listUsers(config) },
        UmReadKind.SESSIONS to suspend { run(config, RouterOpKind.BULK) { api, d -> d.liveSessions(api) } }
    ).map { (kind, read) ->
        when (val r = read()) {
            is AppResult.Success -> UmReadCheck(kind, ok = true)
            is AppResult.Error -> UmReadCheck(kind, ok = false, errorCode = r.error.code)
            else -> UmReadCheck(kind, ok = false, errorCode = null)
        }
    }

    // ---------------- الحالة والتهيئة والصيانة ----------------

    /** لقطة حالة User Manager (قراءة فقط). [umConfig] الافتراضي = المحفوظ؛ [refreshVersion] يعيد قراءة إصدار RouterOS. */
    suspend fun status(config: RouterConfig, umConfig: UserManConfig? = null, refreshVersion: Boolean = false): AppResult<UmStatus> =
        connectionManager.execute(config) { api ->
            if (refreshVersion) dialects.remove(keyOf(config))
            val version = versionFor(config, api, refresh = refreshVersion)
            dialectFor(config, api).inspect(api, umConfig ?: currentConfig(), version)
        }

    /** تهيئة الراوتر بإعداد [umConfig] (انظر [UserManDialect.provision]). لا تمسّ المستخدمين ولا الباقات. */
    suspend fun provision(config: RouterConfig, umConfig: UserManConfig): AppResult<List<UmProvisionStep>> =
        run(config, RouterOpKind.BULK) { api, d -> d.provision(api, umConfig) }

    suspend fun backupDatabase(config: RouterConfig, name: String): AppResult<Unit> =
        run(config, RouterOpKind.BULK) { api, d -> d.backupDatabase(api, name) }

    /** true = نُفّذ التحسين، false = الجيل لا يدعمه (v6). */
    suspend fun optimizeDatabase(config: RouterConfig): AppResult<Boolean> =
        run(config, RouterOpKind.BULK) { api, d -> d.optimizeDatabase(api) }

    suspend fun findOrphanLimitations(config: RouterConfig): AppResult<List<String>> =
        run(config) { api, d -> d.orphanLimitations(api) }

    suspend fun removeOrphanLimitations(config: RouterConfig, names: Collection<String>): AppResult<Int> =
        run(config) { api, d -> d.removeLimitations(api, names) }

    companion object {
        private const val CONFIG_LOAD_TIMEOUT_MS = 4_000L

        /** تعذّر تحديد إصدار RouterOS من /system/resource (فارغ أو غير مفهوم). */
        const val ERR_ROUTER_VERSION_UNKNOWN = "ERR_ROUTER_VERSION_UNKNOWN"

        internal fun looksLikePathMismatch(message: String): Boolean =
            message.contains("no such command", ignoreCase = true) || message.contains("bad command name", ignoreCase = true)

        /** رقم الإصدار الرئيسي، أو null إن لم يكن النص يبدأ برقم. */
        internal fun majorOf(version: String): Int? = Regex("^\\s*(\\d+)").find(version)?.groupValues?.get(1)?.toIntOrNull()

        /** "7.14.1 (stable)" ← v7؛ "6.49.10 (long-term)" ← v6؛ نص غير مفهوم ← null (لا افتراض). */
        fun dialectForVersion(version: String, customer: String = UserManConfig.DEFAULT_CUSTOMER): UserManDialect? {
            val major = majorOf(version) ?: return null
            return if (major >= 7) UserManV7Dialect() else UserManV6Dialect(customer)
        }
    }
}

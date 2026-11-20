package ps.mgtik.hotspot.data.userman

import kotlinx.serialization.json.JsonObject
import ps.mgtik.hotspot.core.network.RouterApi
import ps.mgtik.hotspot.core.network.string
import ps.mgtik.hotspot.core.network.RouterCommandRejected
import ps.mgtik.hotspot.domain.model.UserManConfig

/**
 * RouterOS v6: القائمة /tool/user-manager بنموذج العملاء (customer). كل المستخدمين والباقات تتبع عميلاً واحداً [customer]
 * (الافتراضي admin كما يُنشئه الراوتر). يعمل عبر API الثنائي فقط (لا REST على v6).
 */
internal class UserManV6Dialect(private val customer: String = "admin") : BaseDialect() {
    override val generation = UmGeneration.V6

    // v6 لا يملك reset-counters-interval على Limitation (التصفير الدوري هناك بسكربت مجدول خارجي على /tool user-manager user reset-counters)
    override val supportsResetInterval = false

    private val base = "/tool/user-manager"
    private val user = "$base/user"
    private val profile = "$base/profile"
    private val limitation = "$base/profile/limitation"
    private val profileLimitation = "$base/profile/profile-limitation"

    // v6: starts-at = logon (أول مصادقة) | now (لحظة الإسناد)؛ الملكية للعميل (owner) عند الإنشاء
    override val startAttr = "starts-at"
    override fun startValue(mode: UmStartMode) = if (mode == UmStartMode.ON_ASSIGN) "now" else "logon"
    override fun startModeOf(row: JsonObject): UmStartMode? = when (row.string("starts-at")?.trim()?.lowercase()) {
        "logon" -> UmStartMode.FIRST_AUTH
        "now" -> UmStartMode.ON_ASSIGN
        else -> null
    }
    override val createExtras: Map<String, Any?> get() = mapOf("owner" to customer)

    private fun toUser(row: JsonObject): UmUser {
        val name = row.string("username").orEmpty()
        val pkg = row.string("actual-profile")?.takeIf { it.isNotBlank() }
        return UmUser(row.string(".id").orEmpty(), name, row.isTrue("disabled"), pkg, row.string("comment"), row.string("password"))
    }

    override suspend fun listUsers(api: RouterApi): List<UmUser> = api.print(user).map { toUser(it) }

    override suspend fun listUserNames(api: RouterApi): List<Pair<String, String>> =
        api.print(user, props = listOf("username", "comment")).map { it.string("username").orEmpty() to it.string("comment").orEmpty() }

    override suspend fun findUser(api: RouterApi, name: String): UmUser? =
        api.print(user, query = mapOf("username" to name)).firstOrNull()?.let { toUser(it) }

    override suspend fun createUser(api: RouterApi, name: String, password: String, comment: String, packageName: String?, disabled: Boolean) {
        val attrs = buildMap<String, Any?> {
            put("customer", customer); put("username", name); put("password", password)
            if (disabled) put("disabled", "yes")
            if (comment.isNotBlank()) put("comment", comment.trim())
        }
        api.add(user, attrs)
        if (packageName != null) {
            try {
                activatePackage(api, name, packageName)
            } catch (e: RouterCommandRejected) {
                // رفض نهائي لإسناد الباقة (غير موجودة/مرفوضة): لا نترك حساباً بلا Profile على User Manager.
                // الأعطال العابرة لا تصل هنا (ليست RouterCommandRejected) فيبقى الإكمال الآمن عند إعادة المحاولة
                try { deleteUser(api, name) } catch (_: Exception) { }
                throw e
            }
        }
    }

    override suspend fun setDisabled(api: RouterApi, name: String, disabled: Boolean) {
        val id = requireId(api.idOf(user, "username", name), "user: $name")
        api.set(user, id, mapOf("disabled" to if (disabled) "yes" else "no"))
    }

    override suspend fun deleteUser(api: RouterApi, name: String): Boolean {
        val id = api.idOf(user, "username", name) ?: return false
        return api.remove(user, id)
    }

    override suspend fun usage(api: RouterApi, name: String): UmUsage = sumSessions(api, "$base/session", name)

    // v6: create-and-activate-profile أمر لا قائمة تفعيلات قابلة للقراءة بثبات؛ كشف التكرار الوحيد المتاح هو actual-profile
    // (الباقة الجارية الآن). تفعيل منتظر/منتهٍ لا يُكشف هنا، فمنع التكرار فيهما يبقى على المستدعي (createUserClassified، والاشتراكات).
    override suspend fun activatePackage(api: RouterApi, userName: String, packageName: String, renew: Boolean) {
        val row = api.print(user, query = mapOf("username" to userName)).firstOrNull()
        val id = requireId(row?.string(".id"), "user: $userName")
        // إعادة محاولة بعد نجاح ضاع إقراره: الباقة جارية فعلاً (actual-profile) فلا نُنشئ تفعيلاً ثانياً. التجديد الصريح يمرّ دائماً.
        if (!renew && row?.string("actual-profile") == packageName) return
        api.command("$user/create-and-activate-profile", mapOf("customer" to customer, "profile" to packageName, "numbers" to id))
    }

    // ───────────── الحالة والتهيئة والصيانة ─────────────

    override val umUserPath = user
    override val umProfilePath = profile
    override val umLimitationPath = limitation
    override val umProfileLimitationPath = profileLimitation
    override val umNasPath = "$base/router"
    override val umDatabasePath = "$base/database"
    override val umSessionPath = "$base/session"
    // أسماء v6 مبنية على ما يظهر في واجهة User Manager 6 (user/active/till-time موثَّقة في منتديات MikroTik)؛ لم تُجرَّب على راوتر حقيقي
    override val sessionAddressKeys = listOf("user-ip", "user-address")
    override val sessionStartKeys = listOf("from-time", "started")
    override val sessionEndKeys = listOf("till-time", "ended")

    override suspend fun inspectEngine(api: RouterApi, cfg: UserManConfig): Engine {
        val customers = lenient { api.print("$base/customer") }
        val installed = customers != null
        val checks = mutableListOf(UmCheck(UmCheckKeys.PACKAGE, installed, if (installed) "user-manager menu present" else "user-manager package missing or not accessible"))
        checks += UmCheck(UmCheckKeys.CUSTOMER, customers?.any { it.string("login") == customer || it.string("name") == customer } == true, "customer '$customer' exists")

        val nas = lenient { api.print(umNasPath) }.orEmpty()
            .firstOrNull { addressMatches(it.string("ip-address"), cfg.radiusAddress) && !it.isTrue("disabled") }
        checks += UmCheck(UmCheckKeys.UM_ROUTER, nas != null, "user-manager router entry for ${cfg.radiusAddress}")
        return Engine(installed, null, null, null, null, nas?.string("shared-secret"), checks)
    }

    override suspend fun ensureNas(api: RouterApi, cfg: UserManConfig): Pair<UmAction, String> {
        val rows = api.print(umNasPath)
        val entry = rows.firstOrNull { addressMatches(it.string("ip-address"), cfg.radiusAddress) }
        if (entry == null) {
            val common = mapOf<String, Any?>("ip-address" to cfg.radiusAddress, "shared-secret" to cfg.radiusSecret)
            try {
                api.add(umNasPath, common + ("customer" to customer))
            } catch (e: RouterCommandRejected) {
                // v5 وبعض بناءات v6 تسمّي الخاصية subscriber بدل customer: نعيد المحاولة بالاسم الآخر فقط عند «خاصية مجهولة»
                if (!looksLikeUnknownParameter(e.routerMessage)) throw e
                api.add(umNasPath, common + ("subscriber" to customer))
            }
            return UmAction.CREATED to cfg.radiusAddress
        }
        val attrs = buildMap<String, Any?> {
            if (entry.string("shared-secret") != cfg.radiusSecret) put("shared-secret", cfg.radiusSecret)
            if (entry.isTrue("disabled")) put("disabled", "no")
        }
        if (attrs.isEmpty()) return UmAction.UNCHANGED to cfg.radiusAddress
        api.set(umNasPath, requireId(entry.string(".id"), "user-manager router"), attrs)
        return UmAction.UPDATED to cfg.radiusAddress
    }

    private fun looksLikeUnknownParameter(message: String): Boolean =
        message.contains("unknown", ignoreCase = true) || message.contains("expected end of command", ignoreCase = true)
}

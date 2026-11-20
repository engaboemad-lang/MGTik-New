package ps.mgtik.hotspot.data.userman

import ps.mgtik.hotspot.core.network.RouterApi
import ps.mgtik.hotspot.core.network.string
import ps.mgtik.hotspot.core.network.RouterCommandRejected
import ps.mgtik.hotspot.core.network.RouterApiException
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.domain.model.UserManConfig

/** RouterOS v7: القائمة /user-manager (user, limitation, profile, profile-limitation, user-profile). */
internal class UserManV7Dialect : BaseDialect() {
    override val generation = UmGeneration.V7

    private val user = "/user-manager/user"
    private val limitation = "/user-manager/limitation"
    private val profile = "/user-manager/profile"
    private val profileLimitation = "/user-manager/profile-limitation"
    private val userProfile = "/user-manager/user-profile"

    // v7: starts-when = first-auth | assigned
    override val startAttr = "starts-when"
    override fun startValue(mode: UmStartMode) = if (mode == UmStartMode.ON_ASSIGN) "assigned" else "first-auth"
    override fun startModeOf(row: kotlinx.serialization.json.JsonObject): UmStartMode? = when (row.string("starts-when")?.trim()?.lowercase()) {
        "first-auth" -> UmStartMode.FIRST_AUTH
        "assigned" -> UmStartMode.ON_ASSIGN
        else -> null
    }

    /** صف مستخدم ← [UmUser] بالباقة من فهرس [packages] المقروء مرة واحدة (لا استعلام لكل مستخدم). */
    private fun toUser(row: kotlinx.serialization.json.JsonObject, packages: Map<String, String>): UmUser {
        val name = row.string("name").orEmpty()
        return UmUser(row.string(".id").orEmpty(), name, row.isTrue("disabled"), packages[name], row.string("comment"), row.string("password"))
    }

    /** فهرس «مستخدم ← باقته الحالية» بقراءة user-profile مرة واحدة؛ آخر صف للمستخدم هو الأحدث (نفس دلالة lastOrNull السابقة). */
    private suspend fun packageIndex(api: RouterApi): Map<String, String> {
        val index = HashMap<String, String>()
        for (row in api.print(userProfile, props = listOf("user", "profile"))) {
            val u = row.string("user") ?: continue
            val p = row.string("profile") ?: continue
            index[u] = p
        }
        return index
    }

    private suspend fun packageOf(api: RouterApi, name: String): String? =
        api.print(userProfile, query = mapOf("user" to name), props = listOf("profile")).lastOrNull()?.string("profile")

    /** طلبان ثابتان مهما بلغ عدد المستخدمين: user + user-profile. */
    override suspend fun listUsers(api: RouterApi): List<UmUser> {
        val rows = api.print(user)
        val packages = packageIndex(api)
        return rows.map { toUser(it, packages) }
    }

    override suspend fun userPackages(api: RouterApi): Map<String, String> = packageIndex(api)

    /** أسماء وتعليقات فقط: طلب واحد دون أي بحث عن الباقات. */
    override suspend fun listUserNames(api: RouterApi): List<Pair<String, String>> =
        api.print(user, props = listOf("name", "comment")).map { it.string("name").orEmpty() to it.string("comment").orEmpty() }

    override suspend fun findUser(api: RouterApi, name: String): UmUser? =
        api.print(user, query = mapOf("name" to name)).firstOrNull()?.let { toUser(it, mapOfNotNull(name, packageOf(api, name))) }

    private fun mapOfNotNull(k: String, v: String?): Map<String, String> = if (v == null) emptyMap() else mapOf(k to v)

    override suspend fun createUser(api: RouterApi, name: String, password: String, comment: String, packageName: String?, disabled: Boolean) {
        val attrs = buildMap<String, Any?> {
            put("name", name); put("password", password)
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
        val id = requireId(api.idOf(user, "name", name), "user: $name")
        api.set(user, id, mapOf("disabled" to if (disabled) "yes" else "no"))
    }

    override suspend fun deleteUser(api: RouterApi, name: String): Boolean {
        val id = api.idOf(user, "name", name) ?: return false
        api.print(userProfile, query = mapOf("user" to name), props = listOf(".id")).mapNotNull { it.string(".id") }
            .forEach { api.remove(userProfile, it) }
        return api.remove(user, id)
    }

    override suspend fun usage(api: RouterApi, name: String): UmUsage = sumSessions(api, "/user-manager/session", name)

    /**
     * صف user-profile جديد يبدأ فوراً إن لم تكن للمستخدم فترة جارية، وإلا يُصفّ (state=waiting) ويبدأ بعد الجارية؛
     * لا يصفّر فترة جارية. لذلك: التجديد المكرر (إعادة محاولة) يُتخطى إن وُجد أصلاً تفعيل منتظر لنفس الباقة، وإسناد
     * «تأكد من الباقة» يُتخطى إن كان آخر تفعيل لها حيّاً. بعد الإضافة نتحقق بقراءة ثانية أن الصف ظهر فعلاً.
     * أسماء الحالات (running/running-active/waiting) من وثائق RouterOS 7 ولم تُجرَّب على راوتر حقيقي؛ حالة غائبة تُعامل كحيّة
     * في الإسناد العادي وكغير معروفة (تُضاف فترة) في التجديد.
     */
    override suspend fun periodUsage(api: RouterApi, name: String): UmUsage {
        // user-profile لا يعرض وقت بدء في وثائق RouterOS 7 (للقراءة فقط: state وend-time): البدء = end-time − validity الباقة.
        // لا نطلب start-time من الراوتر (غير موثَّق)؛ يُقرأ فقط إن وُجد في الصف. وإن لم يُعرف البدء يُرجَع الإجمالي (لا يُخفى استهلاك). لم يُجرَّب على راوتر حقيقي.
        val running = api.print(userProfile, query = mapOf("user" to name), props = listOf("profile", "state", "end-time"))
            .lastOrNull { it.string("state") in RUNNING_STATES }
        val start = running?.let { row ->
            val validity = row.string("profile")?.let { p ->
                api.print(profile, query = mapOf("name" to p), props = listOf("validity")).firstOrNull()?.string("validity")
            }?.let { UmUnits.parseDurationSeconds(it) }
            UmUnits.periodStartSeconds(row.string("end-time"), validity) ?: row.string("start-time")?.let { UmUnits.parseRouterDateTime(it) }
        }
        return sumSessions(api, "/user-manager/session", name, start)
    }

    override suspend fun activatePackage(api: RouterApi, userName: String, packageName: String, renew: Boolean) {
        val before = api.print(userProfile, query = mapOf("user" to userName), props = listOf(".id", "profile", "state"))
        val last = before.lastOrNull()
        if (last != null && last.string("profile") == packageName) {
            val state = last.string("state")
            if (renew) { if (state == "waiting") return }
            else if (state == null || state in LIVE_STATES) return
        }
        api.add(userProfile, mapOf("user" to userName, "profile" to packageName))
        val after = api.print(userProfile, query = mapOf("user" to userName), props = listOf(".id", "profile"))
        if (after.size <= before.size) {
            throw RouterApiException(ApiError(ERR_ACTIVATE_UNVERIFIED, "Package '$packageName' not visible for '$userName' after activation", retryable = true))
        }
    }

    private companion object {
        val LIVE_STATES = setOf("running", "running-active", "waiting")
        val RUNNING_STATES = setOf("running", "running-active")
        const val ERR_ACTIVATE_UNVERIFIED = "ERR_USER_ROUTER_ACTIVATE_UNVERIFIED"
    }

    // ───────────── الحالة والتهيئة والصيانة ─────────────

    override val umUserPath = user
    override val umProfilePath = profile
    override val umLimitationPath = limitation
    override val umProfileLimitationPath = profileLimitation
    override val umNasPath = "/user-manager/router"
    override val umDatabasePath = "/user-manager/database"
    override val umSessionPath = "/user-manager/session"
    // موثَّقة في دليل RouterOS 7 (user-manager/session): user-address وstarted وcalling-station-id وactive وstatus
    override val sessionAddressKeys = listOf("user-address")
    override val sessionStartKeys = listOf("started")
    override val sessionEndKeys = listOf("ended")

    override suspend fun inspectEngine(api: RouterApi, cfg: UserManConfig): Engine {
        val um = lenient { api.print("/user-manager").firstOrNull() }
        val installed = um != null
        val checks = mutableListOf(UmCheck(UmCheckKeys.PACKAGE, installed, if (installed) "user-manager menu present" else "user-manager package missing or not accessible"))
        checks += UmCheck(UmCheckKeys.ENABLED, um?.isTrue("enabled") == true, "user-manager enabled=${um?.string("enabled")}")
        // الخاصية غائبة في بعض إصدارات v7 الأولى: لا تنطبق فلا تُعدّ فشلاً
        checks += UmCheck(UmCheckKeys.USE_PROFILES, um != null && (um.string("use-profiles") == null || um.isTrue("use-profiles")), "user-manager use-profiles=${um?.string("use-profiles")}")

        val nas = lenient { api.print(umNasPath) }.orEmpty()
            .firstOrNull { addressMatches(it.string("address"), cfg.radiusAddress) && !it.isTrue("disabled") }
        checks += UmCheck(UmCheckKeys.UM_ROUTER, nas != null, "user-manager router entry for ${cfg.radiusAddress}")

        val db = lenient { api.print(umDatabasePath).firstOrNull() }
        return Engine(installed, um?.isTrue("enabled"), um?.takeIf { it.string("use-profiles") != null }?.isTrue("use-profiles"),
            db?.string("db-path"), db?.string("db-size")?.toLongOrNull(), nas?.string("shared-secret"), checks)
    }

    override suspend fun ensureEnabled(api: RouterApi): Pair<UmAction, String>? {
        val um = api.print("/user-manager").firstOrNull() ?: throw RouterCommandRejected("user-manager package missing")
        val attrs = buildMap<String, Any?> {
            if (!um.isTrue("enabled")) put("enabled", "yes")
            if (um.string("use-profiles") != null && !um.isTrue("use-profiles")) put("use-profiles", "yes")
        }
        if (attrs.isEmpty()) return UmAction.UNCHANGED to ""
        api.setSingle("/user-manager", attrs)
        return UmAction.UPDATED to attrs.keys.joinToString(",")
    }

    override suspend fun ensureNas(api: RouterApi, cfg: UserManConfig): Pair<UmAction, String> {
        val rows = api.print(umNasPath)
        val entry = rows.firstOrNull { addressMatches(it.string("address"), cfg.radiusAddress) }
            ?: rows.firstOrNull { it.string("name") == cfg.nasName }
        if (entry == null) {
            api.add(umNasPath, mapOf("name" to cfg.nasName, "address" to cfg.radiusAddress, "shared-secret" to cfg.radiusSecret))
            return UmAction.CREATED to cfg.nasName
        }
        val attrs = buildMap<String, Any?> {
            if (entry.string("shared-secret") != cfg.radiusSecret) put("shared-secret", cfg.radiusSecret)
            if (!addressMatches(entry.string("address"), cfg.radiusAddress)) put("address", cfg.radiusAddress)
            if (entry.isTrue("disabled")) put("disabled", "no")
        }
        if (attrs.isEmpty()) return UmAction.UNCHANGED to cfg.nasName
        api.set(umNasPath, requireId(entry.string(".id"), "user-manager router"), attrs)
        return UmAction.UPDATED to cfg.nasName
    }

    override fun backupArgs(name: String): Map<String, Any?> = mapOf("name" to name, "overwrite" to "yes")

    override suspend fun optimizeDatabase(api: RouterApi): Boolean {
        api.command("$umDatabasePath/optimize-db")
        return true
    }
}

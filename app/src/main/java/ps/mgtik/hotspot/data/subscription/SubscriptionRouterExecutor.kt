package ps.mgtik.hotspot.data.subscription

import ps.mgtik.hotspot.core.network.RouterApi
import ps.mgtik.hotspot.core.network.RouterCommandRejected
import ps.mgtik.hotspot.core.network.RouterConfig
import ps.mgtik.hotspot.data.userman.UmUser
import ps.mgtik.hotspot.data.userman.UserManDialect
import ps.mgtik.hotspot.data.userman.UserManSource
import ps.mgtik.hotspot.data.repository.RouterRepositoryImpl
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.Subscription
import ps.mgtik.hotspot.domain.model.SubscriptionOp
import ps.mgtik.hotspot.domain.subscription.RouterIntent
import ps.mgtik.hotspot.domain.subscription.SubscriptionRules
import javax.inject.Inject
import javax.inject.Singleton

/** ما قُرئ من الراوتر بعد تنفيذ الأمر (للتحقق وللتسجيل). */
data class RouterObservation(
    val routerUserId: String,
    val userExists: Boolean,
    val disabled: Boolean,
    val uptime: String,
    val bytesIn: Long,
    val bytesOut: Long,
    val activeSessions: Int
)

/** نتيجة الأمر: تحقّق النتيجة المطلوبة أم رفضه الراوتر لهذا الحساب بعينه. */
sealed class ExecutionOutcome {
    data class Applied(val observed: RouterObservation) : ExecutionOutcome()
    data class Rejected(val message: String) : ExecutionOutcome()
}

/**
 * SubscriptionRouterExecutor - ينفّذ أمراً إدارياً واحداً على حساب User Manager المقابل ثم يقرأ الحالة الفعلية للتحقق منها.
 * يعمل على RouterOS 6.x و7.x عبر RouterApi، ويعيد استعمال RouterConnectionManager (لا اتصال موازٍ).
 *
 * الربط بالحساب: باسم الدخول؛ ولا يُكتب أي تعليق على الحساب. حسابات قديمة تحمل التعليق
 * `MGTIK-SUB:<subscriptionId>` تُوجد به أولاً (توافقاً مع ما أُنشئ سابقاً). معرّف الراوتر الداخلي (.id) يُقرأ ويُحدَّث عند كل مزامنة.
 *
 * التعطيل: disabled=yes ثم قطع الجلسات النشطة لهذا الحساب (تعطيل الحساب وحده لا ينهي جلسة قائمة)،
 * ثم قراءة الحالة والتحقق من انعدام الجلسات.
 */
@Singleton
class SubscriptionRouterExecutor @Inject constructor(
    private val userMan: UserManSource
) {
    suspend fun apply(config: RouterConfig, sub: Subscription, op: SubscriptionOp): AppResult<ExecutionOutcome> =
        userMan.withDialect(config) { api, d ->
            try {
                when (SubscriptionRules.intentOf(op)) {
                    RouterIntent.ENABLE -> enable(api, d, sub, op)
                    RouterIntent.DISABLE -> disable(api, d, sub)
                    RouterIntent.NONE -> ExecutionOutcome.Applied(observe(api, d, sub, existing = find(api, d, sub)))
                }
            } catch (e: RouterCommandRejected) {
                ExecutionOutcome.Rejected(e.routerMessage.take(160).ifBlank { "Rejected by router" })
            }
        }

    /** يقرأ الحالة الفعلية فقط (مزامنة/كشف تعارض) دون أي تغيير. */
    suspend fun observeOnly(config: RouterConfig, sub: Subscription): AppResult<RouterObservation> =
        userMan.withDialect(config) { api, d -> observe(api, d, sub, existing = find(api, d, sub)) }

    /**
     * التفعيل: مستخدم جديد ← إنشاء مع إسناد الباقة؛ موجود ← تفعيل (disabled=no)، وعند التجديد يُسند فترة/باقة جديدة
     * (فتبدأ فترة جديدة بحدودها بدل تصفير عدّادات قديمة). حدود الوقت والبيانات والسرعة تأتي من الباقة وليست من الاشتراك.
     */
    private suspend fun enable(api: RouterApi, d: UserManDialect, sub: Subscription, op: SubscriptionOp): ExecutionOutcome {
        val existing = find(api, d, sub)
        val pkg = sub.routerProfileName.takeIf { it.isNotBlank() }
        if (existing == null) {
            d.createUser(api, sub.customerUsername, sub.routerPassword, tagOf(sub.id), pkg, disabled = false)
        } else {
            if (existing.disabled) d.setDisabled(api, existing.name, false)
            if (pkg != null && (SubscriptionRules.resetsCounters(op) || existing.packageName != pkg)) {
                d.activatePackage(api, existing.name, pkg, renew = SubscriptionRules.resetsCounters(op))
            }
        }
        val after = find(api, d, sub) ?: return ExecutionOutcome.Rejected("Router user was not found after creation")
        return ExecutionOutcome.Applied(observe(api, d, sub, existing = after))
    }

    private suspend fun disable(api: RouterApi, d: UserManDialect, sub: Subscription): ExecutionOutcome {
        val existing = find(api, d, sub)
        if (existing != null) d.setDisabled(api, existing.name, true)
        // قطع الجلسات الحية على NAS: التعطيل في User Manager يمنع الدخول التالي لكنه لا ينهي جلسة قائمة.
        // الجلسات المتبقية تُقاس على NAS نفسه (لا من سجل User Manager الذي يتأخر إغلاقه حتى وصول حزمة المحاسبة)
        val remaining = RouterRepositoryImpl.disconnectOnNas(api, sub.customerUsername).remaining
        return ExecutionOutcome.Applied(observe(api, d, sub, existing = find(api, d, sub), nasSessions = remaining))
    }

    /** البحث بوسم الاشتراك أولاً ثم بالاسم (نفس منطق الإصدار السابق). */
    private suspend fun find(api: RouterApi, d: UserManDialect, sub: Subscription): UmUser? =
        d.listUserNames(api).firstOrNull { it.second == tagOf(sub.id) }?.let { d.findUser(api, it.first) } ?: d.findUser(api, sub.customerUsername)

    /** [nasSessions] = ما بقي على NAS بعد قطع صريح؛ غير ذلك تُحسب الجلسات الحيّة من User Manager. */
    private suspend fun observe(api: RouterApi, d: UserManDialect, sub: Subscription, existing: UmUser?, nasSessions: Int? = null): RouterObservation {
        val sessions = nasSessions ?: d.liveSessions(api).count { it.username.equals(sub.customerUsername, ignoreCase = true) }
        if (existing == null) return RouterObservation("", false, true, "", 0L, 0L, sessions)
        val usage = d.usage(api, existing.name)
        return RouterObservation(
            routerUserId = existing.id,
            userExists = true,
            disabled = existing.disabled,
            uptime = if (usage.uptimeSeconds > 0) "${usage.uptimeSeconds}s" else "",
            bytesIn = usage.uploadBytes,
            bytesOut = usage.downloadBytes,
            activeSessions = sessions
        )
    }

    companion object {
        fun tagOf(subscriptionId: String) = "MGTIK-SUB:$subscriptionId"

        /**
         * التحقق من أن الحالة المقروءة تطابق نية الأمر. نقي لسهولة الاختبار.
         * ENABLE: الحساب موجود ومفعّل. DISABLE: الحساب غير موجود أو معطّل، ولا جلسات نشطة له.
         */
        fun mismatch(intent: RouterIntent, o: RouterObservation): String? = when (intent) {
            RouterIntent.ENABLE -> when {
                !o.userExists -> "VERIFY_MISMATCH: router user is missing"
                o.disabled -> "VERIFY_MISMATCH: router user is still disabled"
                else -> null
            }
            RouterIntent.DISABLE -> when {
                o.userExists && !o.disabled -> "VERIFY_MISMATCH: router user is still enabled"
                o.activeSessions > 0 -> "VERIFY_MISMATCH: ${o.activeSessions} active session(s) remain"
                else -> null
            }
            RouterIntent.NONE -> null
        }
    }
}

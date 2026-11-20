package ps.mgtik.hotspot.domain.subscription

import ps.mgtik.hotspot.core.util.toSortableIso
import ps.mgtik.hotspot.domain.model.NewSubscription
import ps.mgtik.hotspot.domain.model.PeriodUnit
import ps.mgtik.hotspot.domain.model.Subscription
import ps.mgtik.hotspot.domain.model.SubscriptionExecution
import ps.mgtik.hotspot.domain.model.SubscriptionOp
import ps.mgtik.hotspot.domain.model.SubscriptionStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Date

/** ماذا يجب أن تكون عليه حالة حساب المستخدم على الراوتر لحالة إدارية معيّنة. */
enum class RouterIntent { ENABLE, DISABLE, NONE }

/** نتيجة تحويل حالة: تعديل صالح أو رفض برمز خطأ (لا استثناءات للمنطق العادي). */
sealed class Change {
    data class Ok(val sub: Subscription) : Change()
    data class Rejected(val code: String) : Change()
}

/** تجديد صالح مع حدود الفترة الجديدة (لفاتورتها). */
sealed class RenewalResult {
    data class Ok(val sub: Subscription, val periodStart: Instant, val periodEnd: Instant) : RenewalResult()
    data class Rejected(val code: String) : RenewalResult()
}

/** ما استحق فعله الآن: انتقال حالة زمني، أو إعادة مزامنة أمر عالق مع الراوتر. */
sealed class Due {
    enum class Kind { ACTIVATE, EXPIRE, AUTO_RENEW }
    data class Time(val subscriptionId: String, val kind: Kind) : Due()
    data class Sync(val subscriptionId: String) : Due()
}

/**
 * SubscriptionRules - قواعد دورة حياة الاشتراك الإدارية (نقية: بلا Firebase/Android/ساعة نظام).
 *
 * سياسة المدة (معرَّفة صراحةً):
 *  • الصلاحية تُحتسب من تاريخ بدء الصلاحية (startsAt) لا من لحظة تنفيذ التفعيل الفعلية، فتأخر الجدولة لا يمدّد الاشتراك.
 *  • DAYS: مدة متصلة = العدد × 24 ساعة. MONTHS: أشهر تقويمية في المنطقة الزمنية المعطاة (31 يناير + شهر = آخر فبراير).
 *  • المدة الإدارية مستقلة تماماً عن حد المدة التشغيلي في الباقة (وقت الاتصال)؛ لا يُشتق أحدهما من الآخر.
 *  • التجديد قبل الانتهاء يمدّد من موعد الانتهاء الحالي (لا تضيع أيام)؛ بعد الانتهاء يبدأ من لحظة التجديد.
 *  • الإيقاف الإداري لا يجمّد الساعة: الصلاحية تستمر بالجريان أثناء الإيقاف.
 *  • الإلغاء يعطّل حساب الراوتر ولا يحذفه.
 */
object SubscriptionRules {

    const val EXPIRING_SOON_DAYS = 3L
    const val MAX_PERIOD_MONTHS = 120
    const val MAX_PERIOD_DAYS = 3650

    const val ERR_INVALID_STATE = "ERR_SUB_INVALID_STATE"
    const val ERR_EXPIRED_RENEW = "ERR_SUB_EXPIRED_RENEW_REQUIRED"
    const val ERR_NOT_FOUND = "ERR_SUB_NOT_FOUND"
    const val ERR_USERNAME_TAKEN = "ERR_SUB_USERNAME_TAKEN"
    const val ERR_NAME_REQUIRED = "ERR_SUB_NAME_REQUIRED"
    const val ERR_USERNAME_INVALID = "ERR_SUB_USERNAME_INVALID"
    const val ERR_PASSWORD_INVALID = "ERR_SUB_PASSWORD_INVALID"
    const val ERR_PROFILE_REQUIRED = "ERR_SUB_PROFILE_REQUIRED"
    const val ERR_PERIOD_INVALID = "ERR_SUB_PERIOD_INVALID"
    const val ERR_PRICE_INVALID = "ERR_SUB_PRICE_INVALID"
    const val ERR_DISCOUNT_INVALID = "ERR_SUB_DISCOUNT_INVALID"
    const val ERR_START_INVALID = "ERR_SUB_START_INVALID"
    const val ERR_ROUTER_PENDING = "ERR_SUB_ROUTER_PENDING"

    private val USERNAME = Regex("^[A-Za-z0-9._@-]{3,32}$")

    fun parse(iso: String): Instant? =
        if (iso.isBlank()) null else try { Instant.parse(iso) } catch (e: DateTimeParseException) { null }

    fun iso(instant: Instant): String = Date.from(instant).toSortableIso()

    // ───────────── التواريخ ─────────────

    fun expiryFrom(start: Instant, unit: PeriodUnit, count: Int, zone: ZoneId): Instant = when (unit) {
        PeriodUnit.DAYS -> start.plus(count.toLong(), ChronoUnit.DAYS)
        PeriodUnit.MONTHS -> start.atZone(zone).plusMonths(count.toLong()).toInstant()
    }

    fun cycleLabel(unit: PeriodUnit, count: Int): String = when {
        unit == PeriodUnit.MONTHS && count == 1 -> "monthly"
        unit == PeriodUnit.MONTHS -> "${count}_months"
        else -> "${count}_days"
    }

    /** بداية فترة التجديد: موعد الانتهاء الحالي إن كان نشطاً ولم ينتهِ، وإلا لحظة التجديد. */
    fun renewalStart(sub: Subscription, now: Instant): Instant {
        val exp = parse(sub.expiresAt)
        return if (sub.status == SubscriptionStatus.ACTIVE && exp != null && exp.isAfter(now)) exp else now
    }

    // ───────────── التحقق ─────────────

    /** رمز أول خطأ في مدخلات إنشاء اشتراك، أو null. */
    fun validateNew(d: NewSubscription, now: Instant): String? {
        if (d.customerName.trim().length < 2) return ERR_NAME_REQUIRED
        if (!USERNAME.matches(d.username)) return ERR_USERNAME_INVALID
        if (d.password.length < 4 || d.password.any { it.isWhitespace() }) return ERR_PASSWORD_INVALID
        if (d.profileId.isBlank()) return ERR_PROFILE_REQUIRED
        val max = if (d.periodUnit == PeriodUnit.MONTHS) MAX_PERIOD_MONTHS else MAX_PERIOD_DAYS
        if (d.periodCount !in 1..max) return ERR_PERIOD_INVALID
        if (d.priceMinor < 0) return ERR_PRICE_INVALID
        if (d.discountMinor < 0 || d.discountMinor > d.priceMinor) return ERR_DISCOUNT_INVALID
        if (d.startsAtIso != null && parse(d.startsAtIso) == null) return ERR_START_INVALID
        return null
    }

    // ───────────── ما تتطلبه الحالة من الراوتر ─────────────

    fun intentOf(status: SubscriptionStatus): RouterIntent = when (status) {
        SubscriptionStatus.ACTIVE -> RouterIntent.ENABLE
        SubscriptionStatus.EXPIRED, SubscriptionStatus.SUSPENDED, SubscriptionStatus.CANCELLED -> RouterIntent.DISABLE
        SubscriptionStatus.DRAFT, SubscriptionStatus.SCHEDULED -> RouterIntent.NONE
    }

    fun intentOf(op: SubscriptionOp): RouterIntent = when (op) {
        SubscriptionOp.ACTIVATE, SubscriptionOp.RENEW, SubscriptionOp.RESUME -> RouterIntent.ENABLE
        SubscriptionOp.EXPIRE, SubscriptionOp.SUSPEND, SubscriptionOp.CANCEL -> RouterIntent.DISABLE
    }

    /** بداية فترة جديدة (تفعيل/تجديد) تعني تصفير العدّادات كي تبدأ القيود من جديد؛ الاستئناف لا يصفّر. */
    fun resetsCounters(op: SubscriptionOp): Boolean = op == SubscriptionOp.ACTIVATE || op == SubscriptionOp.RENEW

    /**
     * تعارض ظاهر: التنفيذ متزامن لكن ما قرأناه من الراوتر يخالف الحالة الإدارية
     * (مثلاً منتهٍ إدارياً والحساب ما زال مفعّلاً، أو نشط والحساب معطّل). يجب إظهاره ومعالجته.
     */
    fun hasConflict(sub: Subscription): Boolean {
        val observedDisabled = sub.routerObservedDisabled ?: return false
        if (sub.execution != SubscriptionExecution.SYNCED) return false
        return when (intentOf(sub.status)) {
            RouterIntent.ENABLE -> observedDisabled
            RouterIntent.DISABLE -> !observedDisabled
            RouterIntent.NONE -> false
        }
    }

    fun isExpiringSoon(sub: Subscription, now: Instant): Boolean {
        if (sub.status != SubscriptionStatus.ACTIVE) return false
        val exp = parse(sub.expiresAt) ?: return false
        return exp.isAfter(now) && !exp.isAfter(now.plus(EXPIRING_SOON_DAYS, ChronoUnit.DAYS))
    }

    // ───────────── التحويلات الإدارية (كلها نقية وتُعاد داخل معاملة السحابة) ─────────────

    private fun Subscription.pending(op: SubscriptionOp, status: SubscriptionStatus): Subscription = copy(
        status = status,
        execution = SubscriptionExecution.PENDING,
        pendingOp = op,
        lastSyncError = "",
        version = version + 1
    )

    /** تفعيل مجدول حان وقته. null = لم يعد مستحقاً (تغيّر شيء بعد الفحص). */
    fun activateOnTime(sub: Subscription, now: Instant): Subscription? {
        val start = parse(sub.startDate) ?: return null
        if (sub.status != SubscriptionStatus.SCHEDULED || start.isAfter(now)) return null
        return sub.pending(SubscriptionOp.ACTIVATE, SubscriptionStatus.ACTIVE).copy(activatedAt = iso(now))
    }

    /**
     * انتهاء صلاحية حان وقته. حارس "منع إيقاف اشتراك جُدِّد": يُعاد التحقق من الحالة والموعد الحاليين داخل المعاملة،
     * فاشتراك جُدِّد (موعده تأخر) أو أُوقف/أُلغي يدوياً لا يُعطَّل بأمر قديم.
     */
    fun expireOnTime(sub: Subscription, now: Instant): Subscription? {
        val exp = parse(sub.expiresAt) ?: return null
        if (sub.status != SubscriptionStatus.ACTIVE || exp.isAfter(now)) return null
        return sub.pending(SubscriptionOp.EXPIRE, SubscriptionStatus.EXPIRED).copy(nextRenewal = "")
    }

    /** مستحق التجديد التلقائي الآن؟ (نشط، autoRenewal، وحان الموعد) */
    fun isAutoRenewDue(sub: Subscription, now: Instant): Boolean {
        val exp = parse(sub.expiresAt) ?: return false
        return sub.status == SubscriptionStatus.ACTIVE && sub.autoRenewal && !exp.isAfter(now)
    }

    /**
     * تجديد (يدوي أو تلقائي). مسموح من ACTIVE وEXPIRED فقط؛ الموقوف يُستأنف أولاً، والمجدول/المسودة/الملغى مرفوضة.
     * المفتاح المطابق لآخر تجديد = إعادة محاولة ⇒ يُرفض بـ null من الطبقة الأعلى قبل الاستدعاء (انظر الـ Engine).
     */
    fun renew(sub: Subscription, now: Instant, key: String, zone: ZoneId): RenewalResult {
        if (sub.status != SubscriptionStatus.ACTIVE && sub.status != SubscriptionStatus.EXPIRED) {
            return RenewalResult.Rejected(ERR_INVALID_STATE)
        }
        val start = renewalStart(sub, now)
        val end = expiryFrom(start, sub.periodUnit, sub.periodCount, zone)
        val renewed = sub.pending(SubscriptionOp.RENEW, SubscriptionStatus.ACTIVE).copy(
            periodNumber = sub.periodNumber + 1,
            expiresAt = iso(end),
            nextRenewal = if (sub.autoRenewal) iso(end) else "",
            lastRenewKey = key,
            suspendedAt = ""
        )
        return RenewalResult.Ok(renewed, start, end)
    }

    fun suspend(sub: Subscription, now: Instant): Change {
        if (sub.status != SubscriptionStatus.ACTIVE) return Change.Rejected(ERR_INVALID_STATE)
        return Change.Ok(sub.pending(SubscriptionOp.SUSPEND, SubscriptionStatus.SUSPENDED).copy(suspendedAt = iso(now)))
    }

    /** استئناف: إن انتهت الصلاحية أثناء الإيقاف يتحول إلى EXPIRED ويُرفض الاستئناف (يلزم تجديد). */
    fun resume(sub: Subscription, now: Instant): Change {
        if (sub.status != SubscriptionStatus.SUSPENDED) return Change.Rejected(ERR_INVALID_STATE)
        val exp = parse(sub.expiresAt)
        if (exp == null || !exp.isAfter(now)) return Change.Rejected(ERR_EXPIRED_RENEW)
        return Change.Ok(sub.pending(SubscriptionOp.RESUME, SubscriptionStatus.ACTIVE).copy(suspendedAt = ""))
    }

    /** إلغاء من أي حالة غير ملغاة. المسودة/المجدول لا حساب لهما على الراوتر بعد ⇒ لا أمر معلّق. */
    fun cancel(sub: Subscription): Change {
        if (sub.status == SubscriptionStatus.CANCELLED) return Change.Rejected(ERR_INVALID_STATE)
        val neverOnRouter = sub.status == SubscriptionStatus.DRAFT || sub.status == SubscriptionStatus.SCHEDULED
        return Change.Ok(
            if (neverOnRouter) sub.copy(status = SubscriptionStatus.CANCELLED, version = sub.version + 1, nextRenewal = "")
            else sub.pending(SubscriptionOp.CANCEL, SubscriptionStatus.CANCELLED).copy(nextRenewal = "")
        )
    }

    // ───────────── المجدول ─────────────

    /** كل ما استحق الآن: الانتقالات الزمنية أولاً ثم الأوامر العالقة. لا ينفّذ شيئاً. */
    fun dueActions(subs: List<Subscription>, now: Instant): List<Due> {
        val time = mutableListOf<Due>()
        val sync = mutableListOf<Due>()
        for (s in subs) {
            val start = parse(s.startDate)
            val exp = parse(s.expiresAt)
            when {
                s.status == SubscriptionStatus.SCHEDULED && start != null && !start.isAfter(now) ->
                    time += Due.Time(s.id, Due.Kind.ACTIVATE)
                s.status == SubscriptionStatus.ACTIVE && exp != null && !exp.isAfter(now) ->
                    time += Due.Time(s.id, if (s.autoRenewal) Due.Kind.AUTO_RENEW else Due.Kind.EXPIRE)
                s.execution != SubscriptionExecution.SYNCED && s.pendingOp != null -> sync += Due.Sync(s.id)
            }
        }
        return time + sync
    }

    /** مفتاح تجديد تلقائي حتمي: نفس الفترة لا تُفوتر مرتين مهما تكرر الفحص. */
    fun autoRenewKey(subscriptionId: String, nextPeriodNumber: Int) = "auto_${subscriptionId}_p$nextPeriodNumber"

    /** معرّف سجل أمر الراوتر: حتمي لكل انتقال حالة (الإصدار يزيد بكل انتقال) فلا يتكرر لنفس الانتقال. */
    fun opId(subscriptionId: String, op: SubscriptionOp, version: Int) = "${subscriptionId}_${op.name}_v$version"
}

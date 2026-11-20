package ps.mgtik.hotspot.data.subscription

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.safeApiCall
import ps.mgtik.hotspot.data.cloud.source.CloudSubscriptionDataSource
import ps.mgtik.hotspot.domain.model.NewSubscription
import ps.mgtik.hotspot.domain.model.Subscription
import ps.mgtik.hotspot.domain.model.SubscriptionExecution
import ps.mgtik.hotspot.domain.model.SubscriptionInvoice
import ps.mgtik.hotspot.domain.model.SubscriptionOp
import ps.mgtik.hotspot.domain.model.SubscriptionStatus
import ps.mgtik.hotspot.domain.repository.IAuditRepository
import ps.mgtik.hotspot.domain.repository.IOfflineScheduler
import ps.mgtik.hotspot.domain.repository.ISettingsRepository
import ps.mgtik.hotspot.domain.subscription.Change
import ps.mgtik.hotspot.domain.subscription.Due
import ps.mgtik.hotspot.domain.subscription.SubscriptionBilling
import ps.mgtik.hotspot.domain.subscription.SubscriptionRules
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** ملخص دورة فحص المجدول: ما بقي معلّقاً يعني أن الدورة التالية يجب أن تعيد المحاولة. */
data class SweepSummary(val transitions: Int = 0, val synced: Int = 0, val stillPending: Int = 0)

/**
 * SubscriptionEngine - ينسّق دورة حياة الاشتراك: يغيّر الحالة الإدارية في السحابة (معاملة)، ثم يحاول تنفيذ الأمر
 * على الراوتر ويتحقق منه. فشل الراوتر لا يلغي التغيير الإداري: يبقى الأمر معلّقاً (PENDING/FAILED) ويُعاد تنفيذه
 * في الفحص الدوري، وظاهراً في الواجهة وسجل الأوامر.
 *
 * الجدولة: يُستدعى sweep() من OfflineSyncWorker الموجود (دوري كل 15 دقيقة بشرط اتصال إنترنت) فلا آلية جدولة جديدة.
 * قيد صريح: تنفيذ الأوامر يتم من الجهاز، فلا يصل الراوتر إلا حين يكون الهاتف على شبكة تصله (LAN/VPN) وبها إنترنت
 * لـ Firestore؛ وإلا يتأخر الإيقاف حتى أول فحص ناجح بعد عودة الاتصال (أمر الإيقاف لا يضيع).
 */
@Singleton
class SubscriptionEngine @Inject constructor(
    private val cloud: CloudSubscriptionDataSource,
    private val executor: SubscriptionRouterExecutor,
    private val settingsRepository: ISettingsRepository,
    private val auth: FirebaseAuth,
    private val scheduler: IOfflineScheduler,
    private val auditRepository: IAuditRepository
) {
    private val mutex = Mutex()
    private val zone: ZoneId get() = ZoneId.systemDefault()

    // ───────────── العمليات الإدارية ─────────────

    suspend fun create(draft: NewSubscription, key: String, now: Instant = Instant.now()): AppResult<Subscription> = safeApiCall {
        SubscriptionRules.validateNew(draft, now)?.let { throw Exception(it) }
        if (cloud.read(key) == null && cloud.usernameInUse(draft.username)) throw Exception(SubscriptionRules.ERR_USERNAME_TAKEN)

        val start = draft.startsAtIso?.let { SubscriptionRules.parse(it) } ?: now
        val scheduled = start.isAfter(now)
        val expires = SubscriptionRules.expiryFrom(start, draft.periodUnit, draft.periodCount, zone)
        val total = SubscriptionBilling.netMinor(draft.priceMinor, draft.discountMinor)
        val sub = Subscription(
            id = key,
            subscriptionId = key,
            customerUsername = draft.username,
            ownerId = draft.ownerId,
            ownerName = draft.ownerName,
            customerName = draft.customerName.trim(),
            customerPhone = draft.customerPhone.trim(),
            routerPassword = draft.password,
            profileId = draft.profileId,
            profileName = draft.profileName,
            routerProfileName = draft.routerProfileName,
            limitUptimeSeconds = draft.limitUptimeSeconds,
            limitTransferBytes = draft.limitTransferBytes,
            status = if (scheduled) SubscriptionStatus.SCHEDULED else SubscriptionStatus.ACTIVE,
            startDate = SubscriptionRules.iso(start),
            periodUnit = draft.periodUnit,
            periodCount = draft.periodCount,
            periodNumber = 1,
            billingCycle = SubscriptionRules.cycleLabel(draft.periodUnit, draft.periodCount),
            expiresAt = SubscriptionRules.iso(expires),
            nextRenewal = if (draft.autoRenewal) SubscriptionRules.iso(expires) else "",
            activatedAt = if (scheduled) "" else SubscriptionRules.iso(now),
            priceMinor = draft.priceMinor,
            discountMinor = draft.discountMinor,
            finalPriceMinor = total,
            priceCurrency = draft.priceCurrency,
            priceSource = draft.priceSource,
            pricingType = draft.pricingType,
            priceCategoryId = draft.priceCategoryId,
            priceRuleId = draft.priceRuleId,
            autoRenewal = draft.autoRenewal,
            notes = draft.notes.trim(),
            // الإداري نشط، لكن التفعيل على الراوتر لم يتم بعد: PENDING حتى يتحقق التنفيذ
            execution = if (scheduled) SubscriptionExecution.SYNCED else SubscriptionExecution.PENDING,
            pendingOp = if (scheduled) null else SubscriptionOp.ACTIVATE,
            version = 1,
            totalInvoicedMinor = total,
            lastInvoiceId = SubscriptionBilling.invoiceId(key, 1)
        )
        val invoice = SubscriptionInvoice(
            id = SubscriptionBilling.invoiceId(key, 1),
            subscriptionId = key,
            periodNumber = 1,
            periodStart = sub.startDate,
            periodEnd = sub.expiresAt,
            priceMinor = draft.priceMinor,
            discountMinor = draft.discountMinor,
            totalMinor = total,
            dueDate = sub.startDate
        )
        val created = cloud.create(sub, invoice)
        auditRepository.logAction("SUBSCRIPTION_CREATE", "Created subscription $key for ${draft.username}")
        afterChange(created)
    }

    suspend fun renew(id: String, key: String, now: Instant = Instant.now()): AppResult<Subscription> = safeApiCall {
        val renewed = cloud.renew(id, key, now, zone, autoOnly = false) ?: throw Exception(SubscriptionRules.ERR_INVALID_STATE)
        auditRepository.logAction("SUBSCRIPTION_RENEW", "Renewed subscription $id period ${renewed.periodNumber}")
        afterChange(renewed)
    }

    suspend fun suspend(id: String, now: Instant = Instant.now()): AppResult<Subscription> =
        transition(id, "SUBSCRIPTION_SUSPEND") { s -> SubscriptionRules.suspend(s, now) }

    suspend fun resume(id: String, now: Instant = Instant.now()): AppResult<Subscription> =
        transition(id, "SUBSCRIPTION_RESUME") { s -> SubscriptionRules.resume(s, now) }

    suspend fun cancel(id: String): AppResult<Subscription> =
        transition(id, "SUBSCRIPTION_CANCEL") { s -> SubscriptionRules.cancel(s) }

    private suspend fun transition(id: String, audit: String, rule: (Subscription) -> Change): AppResult<Subscription> = safeApiCall {
        val updated = cloud.mutate(id) { current ->
            when (val c = rule(current)) {
                is Change.Ok -> c.sub
                is Change.Rejected -> throw Exception(c.code)
            }
        } ?: throw Exception(SubscriptionRules.ERR_INVALID_STATE)
        auditRepository.logAction(audit, "Subscription $id -> ${updated.status}")
        afterChange(updated)
    }

    /** إعادة محاولة يدوية لأمر عالق (زر «إعادة المزامنة»). */
    suspend fun retrySync(id: String): AppResult<Subscription> = safeApiCall {
        val sub = cloud.read(id) ?: throw Exception(SubscriptionRules.ERR_NOT_FOUND)
        afterChange(sub)
    }

    /** يقرأ حالة الحساب الفعلية من الراوتر دون تغيير (لكشف التعارض بين الحالة الإدارية والتشغيلية). */
    suspend fun refreshFromRouter(id: String): AppResult<Subscription> {
        val sub = when (val read = safeApiCall { cloud.read(id) ?: throw Exception(SubscriptionRules.ERR_NOT_FOUND) }) {
            is AppResult.Success -> read.data
            else -> return read
        }
        val config = settingsRepository.getRouterConfig().firstOrNull()?.takeIf { it.host.isNotBlank() }
            ?: return AppResult.Error(ApiError(code = SubscriptionRules.ERR_ROUTER_PENDING, message = "Router is not configured", retryable = false))
        return when (val r = executor.observeOnly(config, sub)) {
            is AppResult.Success -> safeApiCall {
                cloud.recordObservation(id, r.data, Instant.now())
                cloud.read(id) ?: sub
            }
            is AppResult.Error -> AppResult.Error(r.error)
            else -> AppResult.Success(sub)
        }
    }

    // ───────────── المحاسبة ─────────────

    suspend fun recordPayment(invoiceId: String, amountMinor: Long, method: String, key: String): AppResult<Unit> = safeApiCall {
        cloud.recordPayment(invoiceId, amountMinor, method, key)
        auditRepository.logAction("SUBSCRIPTION_PAYMENT", "Payment on subscription invoice $invoiceId")
    }

    suspend fun voidPayment(paymentId: String): AppResult<Unit> = safeApiCall {
        cloud.voidPayment(paymentId)
        auditRepository.logAction("SUBSCRIPTION_PAYMENT_VOID", "Voided subscription payment $paymentId")
    }

    // ───────────── المجدول ─────────────

    /**
     * فحص دوري: ينفّذ الانتقالات الزمنية المستحقة (تفعيل مجدول، انتهاء، تجديد تلقائي) كلٌّ في معاملة تعيد التحقق من
     * الشرط، ثم يعيد تنفيذ كل أمر راوتر معلّق. فشل اشتراك واحد لا يمنع غيره.
     */
    suspend fun sweep(now: Instant = Instant.now()): SweepSummary = mutex.withLock {
        // لا فحص بلا مستخدم مسجَّل الدخول
        if (auth.currentUser == null) return SweepSummary()
        // تعذر القراءة (شبكة/صلاحية) ليس فشل اشتراك: تُعاد المحاولة في الدورة الدورية التالية دون تراجع أُسّي
        val subs = try {
            cloud.readAll()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SweepSummary()
        }
        val toExecute = linkedSetOf<String>()
        var transitions = 0
        for (due in SubscriptionRules.dueActions(subs, now)) {
            try {
                when (due) {
                    is Due.Time -> {
                        val changed = when (due.kind) {
                            Due.Kind.ACTIVATE -> cloud.mutate(due.subscriptionId) { SubscriptionRules.activateOnTime(it, now) }
                            Due.Kind.EXPIRE -> cloud.mutate(due.subscriptionId) { SubscriptionRules.expireOnTime(it, now) }
                            Due.Kind.AUTO_RENEW -> cloud.renew(due.subscriptionId, null, now, zone, autoOnly = true)
                        }
                        if (changed != null) transitions++
                        toExecute += due.subscriptionId
                    }
                    is Due.Sync -> toExecute += due.subscriptionId
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // يُعاد في الدورة التالية؛ الاشتراكات الأخرى تُكمل
            }
        }
        var synced = 0
        var pending = 0
        for (id in toExecute) {
            try {
                val sub = cloud.read(id) ?: continue
                val after = execute(sub, now)
                if (after.pendingOp == null) synced++ else pending++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pending++
            }
        }
        SweepSummary(transitions, synced, pending)
    }

    // ───────────── التنفيذ على الراوتر ─────────────

    private suspend fun afterChange(sub: Subscription): Subscription {
        if (sub.pendingOp == null) return sub
        val after = mutex.withLock { execute(sub, Instant.now()) }
        // لم يكتمل: جدولة مزامنة قريبة بدل انتظار الدورة الدورية فقط
        if (after.pendingOp != null) scheduler.scheduleSync()
        return after
    }

    /** ينفّذ الأمر المعلّق ويتحقق منه ويسجّل النتيجة. يُرجع آخر حالة للاشتراك (قد يبقى الأمر معلّقاً). */
    private suspend fun execute(sub: Subscription, now: Instant): Subscription {
        val op = sub.pendingOp ?: return sub
        val config = settingsRepository.getRouterConfig().firstOrNull()?.takeIf { it.host.isNotBlank() }
        if (config == null) {
            return cloud.completeSync(sub, null, "NO_ROUTER_CONFIG: router is not configured", retryable = true, now = now) ?: sub
        }
        return when (val result = executor.apply(config, sub, op)) {
            is AppResult.Success -> when (val outcome = result.data) {
                is ExecutionOutcome.Rejected ->
                    cloud.completeSync(sub, null, outcome.message, retryable = false, now = now) ?: sub
                is ExecutionOutcome.Applied -> {
                    val mismatch = SubscriptionRouterExecutor.mismatch(SubscriptionRules.intentOf(op), outcome.observed)
                    if (mismatch == null) cloud.completeSync(sub, outcome.observed, null, retryable = false, now = now) ?: sub
                    else cloud.completeSync(sub, null, mismatch, retryable = false, now = now) ?: sub
                }
            }
            is AppResult.Error ->
                cloud.completeSync(sub, null, "${result.error.code}: ${result.error.message}", retryable = result.error.retryable, now = now) ?: sub
            else -> sub
        }
    }

}

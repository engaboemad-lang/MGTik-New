package ps.mgtik.hotspot.data.router.lifecycle

import ps.mgtik.hotspot.domain.model.ConnectionLogEntry
import ps.mgtik.hotspot.domain.model.RouterConnectionState
import ps.mgtik.hotspot.domain.model.RouterConnectionState.*

/*
 * نواة نقية (بلا Android ولا Coroutines) لدورة حياة اتصال الراوتر؛ كلها قابلة للاختبار بزمن مُمرَّر.
 * لا تحمل أي بيانات حساسة (لا كلمات مرور ولا إعدادات اعتماد).
 */

// ───────────── 3.2 فواصل إعادة المحاولة ─────────────

/** 2 → 5 → 10 → 30 → 60 ثانية ثم يثبت عند 60. [floorSeconds] لإبطاء المحاولات عند التذبذب. */
internal class ReconnectBackoff(
    private val stepsSeconds: List<Int> = DEFAULT_STEPS,
    private val floorSeconds: Int = 0
) {
    private var attempt = 0
    val attemptsMade: Int get() = attempt

    /** الفاصل التالي بالمللي ثانية ثم يتقدم العدّاد. */
    fun nextDelayMs(): Long {
        val s = stepsSeconds[minOf(attempt, stepsSeconds.lastIndex)].coerceAtLeast(floorSeconds)
        attempt++
        return s * 1000L
    }

    /** إعادة المحاولة اليدوية/النجاح: يبدأ التسلسل من جديد. */
    fun reset() { attempt = 0 }

    fun withFloor(seconds: Int) = ReconnectBackoff(stepsSeconds, seconds).also { it.attempt = attempt }

    companion object { val DEFAULT_STEPS = listOf(2, 5, 10, 30, 60) }
}

/** هل يُعاد الاتصال تلقائياً بعد هذا الفشل؟ المهلة وعدم الوصول فقط؛ المصادقة والإعداد لا. */
internal object ReconnectPolicy {
    private val RETRYABLE = setOf(
        "ERR_TIMEOUT", "ERR_NETWORK_UNAVAILABLE", "ERR_ROUTER_OFFLINE", "ERR_ROUTER_VERSION_UNKNOWN",
        "ERR_ROUTER_HOST_UNREACHABLE", "ERR_ROUTER_PORT_CLOSED"
    )
    private val NON_RETRYABLE = setOf(
        "ERR_ROUTER_AUTH_FAILED", "ERR_ROUTER_NOT_CONFIGURED", "ERR_ROUTER_REJECTED", "ERR_ROUTER_SSL_UNTRUSTED",
        "ERR_ROUTER_PERMISSION_DENIED"
    )

    /** [retryableFlag] قيمة ApiError.retryable إن وُجدت؛ الرمز الصريح له الأولوية. */
    fun isRetryable(code: String?, retryableFlag: Boolean?): Boolean = when {
        code == null -> retryableFlag ?: true
        code in NON_RETRYABLE -> false
        code in RETRYABLE -> true
        else -> retryableFlag ?: false
    }

    /** شروط إيقاف المحاولات التلقائية (3.2). */
    fun shouldStop(loggedOut: Boolean, offlineMode: Boolean, configuring: Boolean, notConfigured: Boolean) =
        loggedOut || offlineMode || configuring || notConfigured
}

// ───────────── 3.3 مدة الاتصال ─────────────

/** المدد تُحسب بـ elapsedRealtime (لا تتأثر بتغيير ساعة الهاتف). */
internal class ConnectionTimeline {
    var connectedSince: Long? = null; private set
    var lastDisconnectAt: Long? = null; private set
    var reconnectCount: Int = 0; private set
    private var everConnected = false
    private var outage = false

    /** نجاح اتصال كامل: أول مرة، أو بعد انقطاع مؤكَّد (مدة جديدة وعدّاد +1). */
    fun onConnected(now: Long) {
        if (connectedSince == null) {
            connectedSince = now
            if (everConnected && outage) reconnectCount++
        }
        everConnected = true
        outage = false
    }

    /** تعافٍ صامت: لا يصفّر المدة، ويزيد العدّاد. */
    fun onSilentRecovery() { reconnectCount++ }

    /** انقطاع مؤكَّد: تنتهي المدة ويُسجَّل وقت الانقطاع. */
    fun onConfirmedDisconnect(now: Long) {
        connectedSince = null
        lastDisconnectAt = now
        outage = true
    }

    /** خروج/إعادة ضبط كاملة (جلسة جديدة). */
    fun reset() { connectedSince = null; lastDisconnectAt = null; reconnectCount = 0; everConnected = false; outage = false }

    fun connectedForMs(now: Long): Long? = connectedSince?.let { (now - it).coerceAtLeast(0) }
}

// ───────────── 4.1 آلة الحالات ─────────────

internal object RouterStateTransitions {
    /** جدول الانتقالات المسموحة الوحيد؛ أي انتقال غيره يُسجَّل ويُرفض. (البقاء في الحالة نفسها مسموح دائماً.) */
    val allowed: Map<RouterConnectionState, Set<RouterConnectionState>> = mapOf(
        NOT_CONFIGURED to setOf(DISCONNECTED, CONNECTING, CONFIGURING),
        DISCONNECTED to setOf(NOT_CONFIGURED, CONNECTING, RECONNECTING, CONFIGURING),
        CONNECTING to setOf(CONNECTED, CONNECTION_FAILED, DISCONNECTED, NOT_CONFIGURED, CONFIGURING),
        CONNECTED to setOf(RECONNECTING, CONNECTING, DISCONNECTED, NOT_CONFIGURED, CONFIGURING),
        CONNECTION_FAILED to setOf(CONNECTING, DISCONNECTED, NOT_CONFIGURED, CONFIGURING),
        RECONNECTING to setOf(CONNECTED, DISCONNECTED, CONNECTING, NOT_CONFIGURED, CONFIGURING),
        CONFIGURING to setOf(NOT_CONFIGURED, DISCONNECTED, CONNECTING, CONNECTED, CONNECTION_FAILED, RECONNECTING)
    )

    fun isAllowed(from: RouterConnectionState, to: RouterConnectionState) =
        from == to || to in (allowed[from] ?: emptySet())
}

/** لقطة حقول الحالة المؤثرة في الاتساق (مستقلة عن RouterStatus لتبقى النواة نقية). */
internal data class StatusFacts(
    val state: RouterConnectionState,
    val hasInfo: Boolean,
    val errorCode: String?,
    val attentionRequired: Boolean,
    val offlineMode: Boolean,
    val loggedOut: Boolean,
    val connectedSince: Boolean
)

/** التركيبات المتناقضة المرفوضة (4.1). تعيد سبب الرفض أو null إن كانت الحالة متسقة. */
internal object RouterStateInvariants {
    fun violation(f: StatusFacts): String? = when {
        f.state == CONNECTED && !f.hasInfo -> "CONNECTED_WITHOUT_INFO"
        f.state == CONNECTED && (f.errorCode != null || f.attentionRequired) -> "CONNECTED_WITH_ERROR"
        f.state == CONNECTED && (f.offlineMode || f.loggedOut) -> "CONNECTED_WHILE_OFFLINE_OR_LOGGED_OUT"
        f.connectedSince && f.state != CONNECTED && f.state != CONFIGURING -> "DURATION_WITHOUT_CONNECTION"
        f.state == NOT_CONFIGURED && (f.hasInfo || f.attentionRequired) -> "NOT_CONFIGURED_WITH_DATA"
        f.loggedOut && f.offlineMode -> "LOGGED_OUT_AND_OFFLINE"
        f.loggedOut && f.state != DISCONNECTED -> "LOGGED_OUT_NOT_DISCONNECTED"
        f.offlineMode && f.state != DISCONNECTED && f.state != CONNECTION_FAILED -> "OFFLINE_MODE_WITH_ACTIVE_STATE"
        else -> null
    }
}

// ───────────── 4.3 تخميد التذبذب ─────────────

/** يحتاج [required] فشلات متتالية داخل [windowMs] قبل اعتبار الانقطاع مؤكَّداً. */
internal class FailureWindow(private val required: Int = 2, private val windowMs: Long = 15_000L) {
    private val failures = ArrayDeque<Long>()

    /** تسجيل فشل؛ true = الانقطاع مؤكَّد (تحقق العدد ضمن النافذة). */
    fun onFailure(now: Long): Boolean {
        failures.addLast(now)
        while (failures.isNotEmpty() && now - failures.first() > windowMs) failures.removeFirst()
        return failures.size >= required
    }

    fun onSuccess() = failures.clear()
}

/** يكتشف تكرار الانقطاع والعودة: [threshold] انقطاعات مؤكدة خلال [windowMs]. */
internal class FlapDetector(private val threshold: Int = 3, private val windowMs: Long = 120_000L) {
    private val outages = ArrayDeque<Long>()
    var unstable: Boolean = false; private set
    /** رسالة «الاتصال غير مستقر» تظهر مرة واحدة لكل حلقة تذبذب. */
    private var announced = false

    class Outage(val flapping: Boolean, val announceUnstable: Boolean, val alertAllowed: Boolean)

    fun onConfirmedOutage(now: Long): Outage {
        outages.addLast(now)
        while (outages.isNotEmpty() && now - outages.first() > windowMs) outages.removeFirst()
        val flapping = outages.size >= threshold
        val firstOfEpisode = flapping && !announced
        if (flapping) { unstable = true; announced = true }
        // التنبيه الأول لكل حلقة؛ بعد ذلك تُمنع تنبيهات الانقطاع المتلاحقة
        val alert = !flapping || firstOfEpisode
        return Outage(flapping, firstOfEpisode, alert)
    }

    /** استقرار: لا انقطاع داخل النافذة. يُستدعى عند كل نبضة ناجحة. */
    fun onStable(now: Long) {
        while (outages.isNotEmpty() && now - outages.first() > windowMs) outages.removeFirst()
        if (outages.isEmpty()) { unstable = false; announced = false }
    }

    fun reset() { outages.clear(); unstable = false; announced = false }
}

// ───────────── 4.5 اكتشاف إعادة تشغيل الراوتر ─────────────

internal object UptimeParser {
    private val part = Regex("(\\d+)([wdhms])")

    /** "1w2d3h4m5s" أو "3h4m5s" أو "45s" ← ثوانٍ؛ null إن لم يُفهم. */
    fun seconds(uptime: String?): Long? {
        if (uptime.isNullOrBlank()) return null
        val matches = part.findAll(uptime.trim()).toList()
        if (matches.isEmpty()) return null
        val consumed = matches.sumOf { it.value.length }
        if (consumed != uptime.trim().length) return null
        return matches.sumOf { m ->
            val n = m.groupValues[1].toLong()
            n * when (m.groupValues[2]) { "w" -> 604_800L; "d" -> 86_400L; "h" -> 3_600L; "m" -> 60L; else -> 1L }
        }
    }

    /** انخفاض uptime بين نبضتين = إعادة تشغيل (يتسامح مع فروق ثوانٍ لا تعني ذلك). */
    fun rebooted(previous: Long?, current: Long?): Boolean =
        previous != null && current != null && current + TOLERANCE_S < previous

    private const val TOLERANCE_S = 5L
}

// ───────────── 4.6 خارج شبكة الراوتر ─────────────

enum class TransportKind { WIFI, CELLULAR, ETHERNET, VPN, OTHER, NONE }

internal object OffRouterNetworkRule {
    fun isPrivateHost(host: String): Boolean {
        val p = host.trim().split('.')
        if (p.size != 4) return false
        val n = p.map { it.toIntOrNull() ?: return false }
        if (n.any { it !in 0..255 }) return false
        return n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168) || (n[0] == 100 && n[1] in 64..127)
    }

    /**
     * «أنت لست على شبكة الراوتر»: فقط عند الجمع بين (راوتر بعنوان خاص) و(لا Wi-Fi/Ethernet/VPN، أي بيانات جوال أو بلا شبكة)
     * و(فشل الوصول الفعلي). الانتقال لبيانات الجوال وحده ليس دليلاً قاطعاً.
     */
    fun isOffRouterNetwork(transports: Set<TransportKind>, routerHost: String, routerReachable: Boolean): Boolean =
        !routerReachable && isPrivateHost(routerHost) &&
            transports.none { it == TransportKind.WIFI || it == TransportKind.ETHERNET || it == TransportKind.VPN }
}

// ───────────── 4.7 سجل الاتصال ─────────────

internal class ConnectionJournal(private val capacity: Int = 50) {
    private val items = ArrayDeque<ConnectionLogEntry>()
    val size: Int get() = items.size

    fun add(e: ConnectionLogEntry) {
        items.addLast(e)
        while (items.size > capacity) items.removeFirst()
    }

    fun snapshot(): List<ConnectionLogEntry> = items.toList()

    /** تصدير نصي؛ لا يحمل إلا الوقت والحالة والرمز والمدد (لا أسرار). */
    fun export(timeFormat: (Long) -> String = { it.toString() }): String =
        items.joinToString("\n") { e ->
            listOf(
                timeFormat(e.atMillis), e.state.name, e.reasonCode ?: "-",
                e.durationMs?.let { "${it / 1000}s" } ?: "-", e.reconnectResult ?: "-"
            ).joinToString(" | ")
        }
}

// ───────────── 4.8 جودة الاتصال ─────────────

internal enum class LinkQuality { GOOD, SLOW, INTERMITTENT }

/** الحدود قابلة للضبط والاختبار؛ التصنيف على نافذة نبضات لا على نبضة واحدة. */
internal data class QualityThresholds(
    val slowRttMs: Long = 800L,
    val windowSize: Int = 5,
    val intermittentFailures: Int = 2,
    val minSamples: Int = 3
)

internal class QualityTracker(private val t: QualityThresholds = QualityThresholds()) {
    private val samples = ArrayDeque<Long?>() // null = فشل النبضة

    fun record(rttMs: Long?) {
        samples.addLast(rttMs)
        while (samples.size > t.windowSize) samples.removeFirst()
    }

    fun reset() = samples.clear()

    /** null = عيّنات غير كافية. */
    fun quality(): LinkQuality? {
        if (samples.size < t.minSamples) return null
        if (samples.count { it == null } >= t.intermittentFailures) return LinkQuality.INTERMITTENT
        val ok = samples.filterNotNull().sorted()
        val median = ok[ok.size / 2]
        return if (median > t.slowRttMs) LinkQuality.SLOW else LinkQuality.GOOD
    }

    fun lastRtt(): Long? = samples.lastOrNull()
}

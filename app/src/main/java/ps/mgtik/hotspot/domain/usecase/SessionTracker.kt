package ps.mgtik.hotspot.domain.usecase

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.LiveSession
import ps.mgtik.hotspot.domain.model.SessionChanges
import ps.mgtik.hotspot.domain.model.SessionEndReason
import ps.mgtik.hotspot.domain.model.TrackedSession
import ps.mgtik.hotspot.domain.repository.ISessionHistoryRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SessionTracker - يسجّل الجلسات من قراءات RouterOS الحية الناجحة فقط (لا يُستدعى عند فشل الجلب، فلا إغلاق كاذب).
 *
 * الحالة الداخلية = الجلسات المفتوحة المعروفة؛ تُحمَّل من السحابة مرة (استئناف بعد إعادة تشغيل التطبيق دون تكرار)،
 * وتُعتمد حالة جديدة فقط بعد نجاح تثبيتها؛ عند الفشل تُعاد الحسبة من آخر حالة مُعتمدة في الجولة التالية (لا ضياع ولا تكرار).
 *
 * حدّ معروف: الرصد يعمل ما دامت شاشة مراقبة مرئية. جلسة انتهت والتطبيق مغلق تُغلق عند أول رصد لاحق بنهاية =
 * آخر وقت رُصدت فيه (وتُعلَّم endEstimated)، والبايتات المسجَّلة هي آخر قيم رُصدت.
 */
@Singleton
class SessionTracker @Inject constructor(
    private val repository: ISessionHistoryRepository
) {
    private val mutex = Mutex()
    private var open: Map<String, TrackedSession>? = null
    private var pendingExtraCloses: List<TrackedSession> = emptyList()
    private val reasonHints = ConcurrentHashMap<String, Pair<SessionEndReason, Long>>()

    /** تلميح بسبب إنهاء معروف (فصل/تعطيل من التطبيق) يُطبَّق إن اختفت جلسة هذا المستخدم خلال [HINT_TTL_MS]. */
    fun hintEndReason(username: String, reason: SessionEndReason, nowMs: Long = System.currentTimeMillis()) {
        reasonHints[username] = reason to nowMs
    }

    /** عند تسجيل الخروج: تُنسى الحالة كي لا تتسرب لمستخدم لاحق. */
    suspend fun reset() = mutex.withLock {
        open = null
        pendingExtraCloses = emptyList()
        reasonHints.clear()
    }

    suspend fun onLiveSessions(live: List<LiveSession>, nowMs: Long = System.currentTimeMillis()): AppResult<Unit> =
        mutex.withLock {
            val known = open ?: when (val r = repository.loadOpenSessions()) {
                is AppResult.Success -> {
                    // وثائق مفتوحة مكررة لمفتاح واحد (جهازان رصدا معاً): نُبقي الأحدث ونغلق الباقي
                    val groups = r.data.groupBy { SessionAccounting.matchKey(it) }
                    pendingExtraCloses = groups.values.flatMap { g ->
                        g.sortedByDescending { it.startedAtMs }.drop(1)
                            .map { SessionAccounting.closeWithReason(it, SessionEndReason.RECONNECTED, nowMs) }
                    }
                    groups.mapValues { (_, g) -> g.maxByOrNull { it.startedAtMs }!! }
                }
                is AppResult.Error -> return@withLock AppResult.Error(r.error)
                is AppResult.Loading -> return@withLock AppResult.Error(ApiError.unknownError("Unexpected loading state"))
            }

            val result = SessionAccounting.reconcile(known, live, nowMs)
            val closes = (pendingExtraCloses + result.changes.closes).map { applyHint(it, nowMs) }
            val changes = SessionChanges(upserts = result.changes.upserts, closes = closes)

            when (val applied = repository.applyChanges(changes)) {
                is AppResult.Success -> {
                    open = result.open
                    pendingExtraCloses = emptyList()
                    AppResult.Success(Unit)
                }
                is AppResult.Error -> {
                    if (open == null) open = known // التحميل نجح؛ لا نعيده كل جولة
                    AppResult.Error(applied.error)
                }
                is AppResult.Loading -> AppResult.Error(ApiError.unknownError("Unexpected loading state"))
            }
        }

    private fun applyHint(s: TrackedSession, nowMs: Long): TrackedSession {
        if (s.endReason != SessionEndReason.DISAPPEARED) return s
        val hint = reasonHints[s.username] ?: return s
        if (nowMs - hint.second > HINT_TTL_MS) { reasonHints.remove(s.username); return s }
        reasonHints.remove(s.username)
        return s.copy(endReason = hint.first)
    }

    companion object {
        const val HINT_TTL_MS = 2 * 60_000L
    }
}

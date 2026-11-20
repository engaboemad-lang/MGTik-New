package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.domain.model.LiveSession
import ps.mgtik.hotspot.domain.model.SessionChanges
import ps.mgtik.hotspot.domain.model.SessionEndReason
import ps.mgtik.hotspot.domain.model.TrackedSession
import kotlin.math.abs

/**
 * محرك محاسبة الجلسات (نقي، بلا Android/Firebase): يقارن آخر جلسات مفتوحة معروفة بما على الراوتر الآن ويستخرج
 * ما يجب فتحه/تحديثه/إغلاقه. لا يُحتسب بايت مرتين لأن الإضافة دائماً فرق بين قراءتين متتاليتين لجلسة واحدة،
 * وأن التراكم للحساب يُضاف عند الإغلاق فقط (مرة واحدة بمعاملة سحابية idempotent).
 *
 * قواعد هوية الجلسة (RouterOS لا يعطي معرّفاً ثابتاً: .id قد يتغير بعد إعادة تشغيل الراوتر):
 *  - المفتاح = اسم المستخدم + MAC؛ جلسة مفتوحة واحدة لكل مفتاح (لا سيرفر: User Manager لا يحمله، والجلسات المفتوحة القديمة تطابق بالمفتاح نفسه).
 *  - نفس الجلسة إن كان uptime غير متناقص ووقت البداية المحسوب (الآن − uptime) ضمن [START_TOLERANCE_MS] من المسجَّل.
 *    خلاف ذلك = جلسة جديدة (إعادة دخول أو إعادة تشغيل الراوتر): تُغلق القديمة وتُفتح الجديدة.
 *  - عدّاد bytes أقل من آخر قراءة داخل الجلسة نفسها = تصفير عدّاد: القراءة الجديدة تُحتسب كاملة (لا طرح سالب).
 */
object SessionAccounting {

    const val START_TOLERANCE_MS = 30_000L

    /** كتابة تحديث جلسة مفتوحة لا تتم أكثر من مرة في هذه المدة (تخفيف التكلفة)؛ الفتح والإغلاق فوريان. */
    const val PERSIST_INTERVAL_MS = 5 * 60_000L

    /** فجوة أكبر من هذه بين آخر رصد وإغلاق الجلسة تجعل وقت النهاية «تقديرياً». */
    const val GAP_TOLERANCE_MS = 45_000L

    data class Result(
        /** الحالة الجديدة للجلسات المفتوحة بحسب [matchKey]؛ تُعتمد فقط بعد نجاح تثبيت [changes]. */
        val open: Map<String, TrackedSession>,
        val changes: SessionChanges
    )

    fun matchKey(username: String, mac: String): String = "$username|${mac.lowercase()}"

    fun matchKey(s: TrackedSession): String = matchKey(s.username, s.macAddress)

    /** معرّف الوثيقة: ثابت لجلسة بعينها (البداية مقرّبة لدقيقة) فلا تتكرر الجلسة نفسها. */
    fun sessionKeyFor(username: String, mac: String, startedAtMs: Long): String =
        listOf(username, mac.lowercase().replace(":", ""), (startedAtMs / 60_000L).toString())
            .joinToString("_") { it.replace('/', '_') }

    fun reconcile(openByKey: Map<String, TrackedSession>, live: List<LiveSession>, nowMs: Long): Result {
        val nextOpen = LinkedHashMap<String, TrackedSession>()
        val upserts = ArrayList<TrackedSession>()
        val closes = ArrayList<TrackedSession>()
        val seen = HashSet<String>()

        for (row in live) {
            if (row.username.isBlank()) continue
            val mk = matchKey(row.username, row.macAddress)
            if (!seen.add(mk)) continue // صفّان بالمفتاح نفسه: نتتبع الأول فقط ولا نكرر الاحتساب
            val existing = openByKey[mk]

            if (existing != null && isSameSession(existing, row.uptimeSeconds, nowMs)) {
                val updated = advance(existing, row, nowMs)
                val needsPersist = nowMs - existing.lastPersistedMs >= PERSIST_INTERVAL_MS ||
                    updated.profile != existing.profile || updated.address != existing.address
                if (needsPersist) {
                    val persisted = updated.copy(lastPersistedMs = nowMs)
                    upserts += persisted
                    nextOpen[mk] = persisted
                } else {
                    nextOpen[mk] = updated
                }
                continue
            }

            if (existing != null) {
                closes += close(existing, SessionEndReason.RECONNECTED, nowMs)
            }
            val fresh = open(row, nowMs).copy(lastPersistedMs = nowMs)
            upserts += fresh
            nextOpen[mk] = fresh
        }

        // جلسات كانت مفتوحة ولم تعد على الراوتر
        for ((mk, s) in openByKey) {
            if (mk !in seen) closes += close(s, SessionEndReason.DISAPPEARED, nowMs)
        }
        return Result(nextOpen, SessionChanges(upserts = upserts, closes = closes))
    }

    /** إغلاق جلسة فصلها التطبيق نفسه (سبب معروف)؛ تُستعمل لتحسين دقة السبب لا لتجاوز الرصد. */
    fun closeWithReason(s: TrackedSession, reason: SessionEndReason, nowMs: Long): TrackedSession = close(s, reason, nowMs)

    internal fun isSameSession(existing: TrackedSession, uptimeSeconds: Long?, nowMs: Long): Boolean {
        val last = existing.lastUptimeSeconds
        if (uptimeSeconds == null || last == null) return true
        if (uptimeSeconds < last) return false
        if (existing.startEstimated) return true
        val computedStart = nowMs - uptimeSeconds * 1000L
        return abs(computedStart - existing.startedAtMs) <= START_TOLERANCE_MS
    }

    private fun open(row: LiveSession, nowMs: Long): TrackedSession {
        val uptime = row.uptimeSeconds
        val started = if (uptime != null) nowMs - uptime * 1000L else nowMs
        return TrackedSession(
            sessionKey = sessionKeyFor(row.username, row.macAddress, started),
            username = row.username,
            macAddress = row.macAddress,
            address = row.address,
            server = "",
            profile = row.profile,
            startedAtMs = started,
            startEstimated = uptime == null,
            lastSeenAtMs = nowMs,
            lastUptimeSeconds = uptime,
            lastRawUpload = row.uploadBytes,
            lastRawDownload = row.downloadBytes,
            // جلسة جديدة: ما تراكم على الراوتر حتى الآن يخصها (عدّاداتها بدأت من صفر عند دخولها)
            uploadBytes = row.uploadBytes,
            downloadBytes = row.downloadBytes
        )
    }

    private fun advance(s: TrackedSession, row: LiveSession, nowMs: Long): TrackedSession = s.copy(
        address = row.address,
        profile = row.profile ?: s.profile,
        lastSeenAtMs = nowMs,
        lastUptimeSeconds = row.uptimeSeconds ?: s.lastUptimeSeconds,
        lastRawUpload = row.uploadBytes,
        lastRawDownload = row.downloadBytes,
        uploadBytes = s.uploadBytes + delta(row.uploadBytes, s.lastRawUpload),
        downloadBytes = s.downloadBytes + delta(row.downloadBytes, s.lastRawDownload)
    )

    /** فرق عدّاد؛ انخفاضه = تصفير فتُحتسب القراءة الجديدة كاملة. */
    internal fun delta(raw: Long, lastRaw: Long): Long = if (raw >= lastRaw) raw - lastRaw else raw

    private fun close(s: TrackedSession, reason: SessionEndReason, nowMs: Long): TrackedSession = s.copy(
        endedAtMs = s.lastSeenAtMs,
        endReason = reason,
        endEstimated = nowMs - s.lastSeenAtMs > GAP_TOLERANCE_MS
    )
}

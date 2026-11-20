package ps.mgtik.hotspot.data.cloud.source

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.safeApiCall
import ps.mgtik.hotspot.domain.model.SessionChanges
import ps.mgtik.hotspot.domain.model.SessionEndReason
import ps.mgtik.hotspot.domain.model.TrackedSession
import ps.mgtik.hotspot.domain.model.UserSessionStats
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CloudSessionHistoryDataSource - سجل الجلسات والتراكم لكل حساب.
 *
 *  hotspot_sessions/{sessionKey}: جلسة واحدة (status = open | closed).
 *  hotspot_user_stats/{username}: مجموع الجلسات المُغلقة فقط؛ يُحدَّث داخل معاملة الإغلاق نفسها، فلا يتكرر الاحتساب
 *  حتى لو أغلقت جلسةً نفسَها جهتان أو أُعيدت المحاولة (الجلسة المُغلقة تُتخطّى).
 */
@Singleton
class CloudSessionHistoryDataSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val sessions = firestore.collection("hotspot_sessions")
    private val stats = firestore.collection("hotspot_user_stats")

    private fun statsId(username: String) = username.replace('/', '_')

    suspend fun loadOpenSessions(): AppResult<List<TrackedSession>> = safeApiCall {
        sessions.whereEqualTo("status", "open").get().await().documents.mapNotNull { it.toTracked() }
    }

    suspend fun applyChanges(changes: SessionChanges): AppResult<Unit> = safeApiCall {
        // الإغلاق أولاً: إن فشل جزء لاحق تبقى النتيجة متسقة (الإغلاق idempotent ويُعاد في الجولة التالية)
        for (s in changes.closes) closeSession(s)
        for (s in changes.upserts) upsertOpen(s)
    }

    private suspend fun upsertOpen(s: TrackedSession) {
        val ref = sessions.document(s.sessionKey)
        firestore.runTransaction { tx ->
            val snap = tx.get(ref)
            // جلسة أُغلقت من جهة أخرى: لا نعيد فتحها
            if (!(snap.exists() && snap.getString("status") == "closed")) tx.set(ref, s.toMap(open = true))
            null
        }.await()
    }

    private suspend fun closeSession(s: TrackedSession) {
        val sRef = sessions.document(s.sessionKey)
        val stRef = stats.document(statsId(s.username))
        firestore.runTransaction { tx ->
            val sSnap = tx.get(sRef)
            val stSnap = tx.get(stRef)
            if (sSnap.exists() && sSnap.getString("status") == "closed") return@runTransaction null

            val ended = s.endedAtMs ?: s.lastSeenAtMs
            tx.set(sRef, s.toMap(open = false))

            val prevSince = stSnap.getLong("tracking_since")
            val prevLastSeen = stSnap.getLong("last_seen_at")
            val prevLastEnd = stSnap.getLong("last_session_ended_at")
            val update = hashMapOf<String, Any>(
                "username" to s.username,
                "total_uptime_s" to FieldValue.increment(s.durationSeconds),
                "total_upload_bytes" to FieldValue.increment(s.uploadBytes),
                "total_download_bytes" to FieldValue.increment(s.downloadBytes),
                "closed_session_count" to FieldValue.increment(1),
                "tracking_since" to minOf(prevSince ?: s.startedAtMs, s.startedAtMs),
                "last_seen_at" to maxOf(prevLastSeen ?: ended, ended)
            )
            if (prevLastEnd == null || ended >= prevLastEnd) {
                update["last_session_ended_at"] = ended
                update["last_session_duration_s"] = s.durationSeconds
            }
            tx.set(stRef, update, SetOptions.merge())
            null
        }.await()
    }

    suspend fun getUserStats(username: String): AppResult<UserSessionStats?> = safeApiCall {
        val d = stats.document(statsId(username)).get().await()
        if (!d.exists()) null else UserSessionStats(
            username = username,
            totalUptimeSeconds = d.getLong("total_uptime_s") ?: 0L,
            totalUploadBytes = d.getLong("total_upload_bytes") ?: 0L,
            totalDownloadBytes = d.getLong("total_download_bytes") ?: 0L,
            closedSessionCount = d.getLong("closed_session_count") ?: 0L,
            lastSeenAtMs = d.getLong("last_seen_at"),
            lastSessionDurationSeconds = d.getLong("last_session_duration_s"),
            trackingSinceMs = d.getLong("tracking_since")
        )
    }

    suspend fun getSessionHistory(username: String, limit: Int): AppResult<List<TrackedSession>> = safeApiCall {
        sessions.whereEqualTo("username", username)
            .whereEqualTo("status", "closed")
            .orderBy("started_at", Query.Direction.DESCENDING)
            .limit(limit.toLong())
            .get().await().documents.mapNotNull { it.toTracked() }
    }

    private fun TrackedSession.toMap(open: Boolean): Map<String, Any?> = mapOf(
        "username" to username,
        "mac_address" to macAddress,
        "address" to address,
        "server" to server,
        "profile" to profile,
        "started_at" to startedAtMs,
        "start_estimated" to startEstimated,
        "last_seen_at" to lastSeenAtMs,
        "last_uptime_s" to lastUptimeSeconds,
        "last_raw_upload" to lastRawUpload,
        "last_raw_download" to lastRawDownload,
        "upload_bytes" to uploadBytes,
        "download_bytes" to downloadBytes,
        "status" to if (open) "open" else "closed",
        "ended_at" to if (open) null else (endedAtMs ?: lastSeenAtMs),
        "end_reason" to if (open) null else endReason?.name,
        "end_estimated" to if (open) null else endEstimated,
        "duration_s" to if (open) null else durationSeconds
    )

    private fun DocumentSnapshot.toTracked(): TrackedSession? {
        val username = getString("username") ?: return null
        val started = getLong("started_at") ?: return null
        val lastSeen = getLong("last_seen_at") ?: started
        val closed = getString("status") == "closed"
        return TrackedSession(
            sessionKey = id,
            username = username,
            macAddress = getString("mac_address").orEmpty(),
            address = getString("address").orEmpty(),
            server = getString("server").orEmpty(),
            profile = getString("profile"),
            startedAtMs = started,
            startEstimated = getBoolean("start_estimated") ?: false,
            lastSeenAtMs = lastSeen,
            lastUptimeSeconds = getLong("last_uptime_s"),
            lastRawUpload = getLong("last_raw_upload") ?: 0L,
            lastRawDownload = getLong("last_raw_download") ?: 0L,
            uploadBytes = getLong("upload_bytes") ?: 0L,
            downloadBytes = getLong("download_bytes") ?: 0L,
            endedAtMs = if (closed) (getLong("ended_at") ?: lastSeen) else null,
            endReason = if (closed) getString("end_reason")?.let { n -> SessionEndReason.values().firstOrNull { it.name == n } } else null,
            endEstimated = if (closed) (getBoolean("end_estimated") ?: false) else false,
            lastPersistedMs = lastSeen
        )
    }
}

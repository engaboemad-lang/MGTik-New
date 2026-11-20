package ps.mgtik.hotspot.data.cloud.source

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.safeApiCall
import ps.mgtik.hotspot.domain.model.ActiveSession
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CloudActiveSessionDataSource - تنفيذ العمليات السحابية للجلسات النشطة (§6.1)
 */
@Singleton
class CloudActiveSessionDataSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val activeUsersCollection = firestore.collection("active_sessions")

    fun getActiveSessionsFlow(): Flow<List<ActiveSession>> {
        return activeUsersCollection.snapshots().map { snapshot ->
            snapshot.documents.map { doc ->
                ActiveSession(
                    mikrotikId = doc.id,
                    user = doc.getString("user") ?: "",
                    address = doc.getString("address") ?: "",
                    macAddress = doc.getString("mac_address") ?: "",
                    uptime = doc.getString("uptime") ?: "",
                    bytesIn = doc.getLong("bytes_in") ?: 0L,
                    bytesOut = doc.getLong("bytes_out") ?: 0L
                )
            }
        }
    }

    suspend fun disconnectUser(username: String, idempotencyKey: String): AppResult<Unit> = safeApiCall {
        val doc = activeUsersCollection.whereEqualTo("user", username).get().await()
        for (d in doc.documents) {
            d.reference.delete().await()
        }
    }
}

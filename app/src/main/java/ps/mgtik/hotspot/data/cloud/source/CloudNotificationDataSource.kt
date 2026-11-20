package ps.mgtik.hotspot.data.cloud.source

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.safeApiCall
import ps.mgtik.hotspot.domain.model.MgtikNotification
import ps.mgtik.hotspot.domain.model.NotificationCategory
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CloudNotificationDataSource - مصدر بيانات مركز التنبيهات والإشعارات الفورية (S20)
 *
 * عزل البيانات (Multi-tenancy): كل مستند في "notifications" يحمل الحقل الإلزامي [FIELD_ADMIN_UID]
 * = uid حساب Firebase Auth الحالي (هوية الأدمن الوحيدة المعتمدة في المشروع، نفس مصدر AuditRepositoryImpl).
 * جميع الاستعلامات والكتابات مقيَّدة به، وقواعد Firestore تفرضه من جهة الخادم
 * (انظر FIRESTORE_RULES_notifications.md).
 */
@Singleton
class CloudNotificationDataSource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val authDataSource: CloudAuthDataSource
) {
    private val notificationsCollection = firestore.collection("notifications")

    /** uid الأدمن الحالي، أو null عند عدم وجود جلسة؛ يتحدّث تلقائياً عند تسجيل الدخول/الخروج. */
    private fun currentUidFlow(): Flow<String?> =
        authDataSource.sessionFlow
            .map { session -> session.user?.uid?.takeIf { session.isAuthenticated && it.isNotBlank() } }
            .distinctUntilChanged()

    private fun requireUid(): String =
        authDataSource.getCurrentUser()?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No authenticated admin session")

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getNotificationsFlow(): Flow<List<MgtikNotification>> =
        currentUidFlow().flatMapLatest { uid ->
            if (uid == null) {
                flowOf(emptyList())
            } else {
                notificationsCollection
                    .whereEqualTo(FIELD_ADMIN_UID, uid)
                    .snapshots()
                    .map { snapshot ->
                        snapshot.documents.map { doc ->
                            MgtikNotification(
                                id = doc.id,
                                title = doc.getString("title") ?: "",
                                message = doc.getString("message") ?: "",
                                category = runCatching { NotificationCategory.valueOf(doc.getString("category") ?: "SYSTEM") }
                                    .getOrDefault(NotificationCategory.SYSTEM),
                                isRead = doc.getBoolean("isRead") ?: false,
                                timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis(),
                                adminUid = doc.getString(FIELD_ADMIN_UID) ?: "",
                                targetType = doc.getString("targetType"),
                                targetId = doc.getString("targetId")
                            )
                        }
                        // الترتيب في الذاكرة لتجنّب الحاجة إلى فهرس مركّب (adminUid + timestamp)
                        .sortedByDescending { it.timestamp }
                        // سقف العرض: الاستعلام نفسه بلا limit() لأن limit مع ترتيب يتطلب فهرساً مركّباً (adminUid + timestamp)
                        .take(MAX_DISPLAYED)
                    }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getUnreadCountFlow(): Flow<Int> =
        currentUidFlow().flatMapLatest { uid ->
            if (uid == null) {
                flowOf(0)
            } else {
                notificationsCollection
                    .whereEqualTo(FIELD_ADMIN_UID, uid)
                    .whereEqualTo("isRead", false)
                    .snapshots()
                    .map { it.size() }
            }
        }

    suspend fun markAsRead(notificationId: String): AppResult<Unit> = safeApiCall {
        val uid = requireUid()
        val ref = notificationsCollection.document(notificationId)
        val doc = ref.get().await()
        // لا نعدّل إلا مستنداً يخص الأدمن الحالي
        if (doc.exists() && doc.getString(FIELD_ADMIN_UID) == uid) {
            ref.update("isRead", true).await()
        }
    }

    suspend fun deleteNotification(notificationId: String): AppResult<Unit> = safeApiCall {
        val uid = requireUid()
        val ref = notificationsCollection.document(notificationId)
        val doc = ref.get().await()
        // لا نحذف إلا مستنداً يخص الأدمن الحالي
        if (doc.exists() && doc.getString(FIELD_ADMIN_UID) == uid) {
            ref.delete().await()
        }
    }

    suspend fun markAllAsRead(): AppResult<Unit> = safeApiCall {
        val uid = requireUid()
        val unreadDocs = notificationsCollection
            .whereEqualTo(FIELD_ADMIN_UID, uid)
            .whereEqualTo("isRead", false)
            .get().await()
        val batch = firestore.batch()
        for (doc in unreadDocs.documents) {
            batch.update(doc.reference, "isRead", true)
        }
        batch.commit().await()
    }

    suspend fun clearAll(): AppResult<Unit> = safeApiCall {
        val uid = requireUid()
        val docs = notificationsCollection
            .whereEqualTo(FIELD_ADMIN_UID, uid)
            .get().await()
        val batch = firestore.batch()
        for (doc in docs.documents) {
            batch.delete(doc.reference)
        }
        batch.commit().await()
    }

    suspend fun addNotification(item: MgtikNotification): AppResult<Unit> = safeApiCall {
        val uid = requireUid()
        val id = item.id.ifBlank { "NOTIF-${UUID.randomUUID().toString().take(8)}" }
        val data = hashMapOf<String, Any>(
            FIELD_ADMIN_UID to uid,
            "title" to item.title,
            "message" to item.message,
            "category" to item.category.name,
            "isRead" to item.isRead,
            "timestamp" to item.timestamp
        )
        item.targetType?.takeIf { it.isNotBlank() }?.let { data["targetType"] = it }
        item.targetId?.takeIf { it.isNotBlank() }?.let { data["targetId"] = it }
        // Firestore يخزّن الكتابة محلياً ويزامنها عند عودة الاتصال، لكن await() بلا مهلة يعلّق دون اتصال
        // حتى يؤكد الخادم (فيعلّق العامل الذي ينشر إشعار الفشل). انتهاء المهلة = الكتابة في الطابور المحلي.
        withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) { notificationsCollection.document(id).set(data).await() }
        Unit
    }

    private companion object {
        const val FIELD_ADMIN_UID = "adminUid"
        const val MAX_DISPLAYED = 100
        const val WRITE_ACK_TIMEOUT_MS = 5_000L
    }
}

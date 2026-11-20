package ps.mgtik.hotspot.data.cloud.source

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.safeApiCall
import ps.mgtik.hotspot.data.userman.ComponentOrigin
import ps.mgtik.hotspot.data.userman.UmComponentType
import ps.mgtik.hotspot.data.userman.UmOrigin
import ps.mgtik.hotspot.data.userman.UmSnapshot
import javax.inject.Inject
import javax.inject.Singleton

/**
 * بيانات التطبيق الخاصة بمكونات User Manager (Profiles وLimitations). الراوتر هو المرجع التشغيلي لقيمها؛ هنا فقط:
 *  - um_components/{type__name}: المصدر (MGTIK/EXTERNAL/IMPORTED) وبصمة آخر إعداد كتبه التطبيق (لاكتشاف التعديل الخارجي).
 *  - um_component_snapshots/{id}: لقطات الإعدادات قبل التعديلات المهمة (للاستعادة من MGTik).
 *  - um_operation_log/{auto}: نتيجة كل خطوة من عمليات الإنشاء والتعديل والحذف والمزامنة.
 */
@Singleton
class CloudComponentDataSource @Inject constructor(private val firestore: FirebaseFirestore) {
    private val registry = firestore.collection("um_components")
    private val snapshots = firestore.collection("um_component_snapshots")
    private val oplog = firestore.collection("um_operation_log")

    data class Record(val origin: ComponentOrigin, val fingerprint: String?)

    /** السجل كاملاً: مفتاح [UmOrigin.key] ← (المصدر، البصمة). */
    suspend fun loadRegistry(): AppResult<Map<String, Record>> = safeApiCall {
        registry.get().await().documents.mapNotNull { d ->
            val origin = (d.getString("origin"))?.let { o -> ComponentOrigin.entries.firstOrNull { it.name == o } } ?: return@mapNotNull null
            d.id to Record(origin, d.getString("fingerprint"))
        }.toMap()
    }

    /** يسجّل المصدر والبصمة. [fingerprint] null = لا تغيير للبصمة الحالية (مثلاً عند الاستيراد). */
    suspend fun record(type: UmComponentType, name: String, origin: ComponentOrigin, fingerprint: String?): AppResult<Unit> = safeApiCall {
        val data = buildMap<String, Any?> {
            put("type", type.name.lowercase()); put("name", name); put("origin", origin.name); put("updated_at", FieldValue.serverTimestamp())
            if (fingerprint != null) put("fingerprint", fingerprint)
            if (origin == ComponentOrigin.IMPORTED) put("imported_at", FieldValue.serverTimestamp())
        }
        registry.document(UmOrigin.key(type, name)).set(data, com.google.firebase.firestore.SetOptions.merge()).await()
        Unit
    }

    /** إزالة سجل مكوّن حُذف من الراوتر (يبقى سجل العمليات واللقطات للتدقيق). */
    suspend fun forget(type: UmComponentType, name: String): AppResult<Unit> = safeApiCall {
        registry.document(UmOrigin.key(type, name)).delete().await(); Unit
    }

    /** يحفظ لقطة ويعيد معرّفها. */
    suspend fun saveSnapshot(snapshot: UmSnapshot): AppResult<String> = safeApiCall {
        val ref = snapshots.document()
        ref.set(snapshot.toMap() + ("id" to ref.id)).await()
        ref.id
    }

    suspend fun listSnapshots(type: UmComponentType, name: String, limit: Long = 20): AppResult<List<UmSnapshot>> = safeApiCall {
        snapshots.whereEqualTo("type", type.name.lowercase()).whereEqualTo("name", name).get().await().documents
            .mapNotNull { d -> d.data?.let { UmSnapshot.fromMap(d.id, it) } }
            .sortedByDescending { it.takenAtMillis }.take(limit.toInt())
    }

    suspend fun getSnapshot(id: String): AppResult<UmSnapshot?> = safeApiCall {
        snapshots.document(id).get().await().let { d -> d.data?.let { UmSnapshot.fromMap(d.id, it) } }
    }

    suspend fun appendLog(operation: String, target: String, success: Boolean, summary: String): AppResult<Unit> = safeApiCall {
        oplog.add(mapOf("operation" to operation, "target" to target, "success" to success, "summary" to summary.take(900), "at" to FieldValue.serverTimestamp())).await()
        Unit
    }

    suspend fun recentLog(limit: Long = 50): AppResult<List<Map<String, Any?>>> = safeApiCall {
        oplog.orderBy("at", Query.Direction.DESCENDING).limit(limit).get().await().documents.map { it.data.orEmpty() }
    }
}

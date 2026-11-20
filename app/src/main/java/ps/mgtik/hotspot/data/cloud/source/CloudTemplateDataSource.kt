package ps.mgtik.hotspot.data.cloud.source

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.safeApiCall
import ps.mgtik.hotspot.domain.model.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CloudTemplateDataSource - تطبيق نظام القوالب عبر Firebase (§7).
 */
@Singleton
class CloudTemplateDataSource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage
) {
    private val templatesCollection = firestore.collection("templates_v1")
    private val assetsFolder = storage.reference.child("assets_v1")

    suspend fun getPublishedTemplatesSnapshot(): AppResult<List<RuntimeTemplate>> = safeApiCall {
        val snapshot = templatesCollection
            .whereEqualTo("status", "published")
            .get()
            .await()
        
        snapshot.documents.mapNotNull { doc ->
            mapDocumentToRuntimeTemplate(doc.id, doc.data ?: emptyMap())
        }
    }

    suspend fun getTemplatePackage(templateId: String, version: Int): AppResult<TemplatePackage> = safeApiCall {
        val versionDoc = templatesCollection.document(templateId)
            .collection("versions")
            .document(version.toString())
            .get()
            .await()
        
        if (!versionDoc.exists()) throw Exception("إصدار القالب $version غير موجود سحابياً")
        
        val rawAssets = versionDoc.get("assets") as? List<Map<String, Any>> ?: emptyList()
        val assetsList = rawAssets.map {
            AssetRef(
                assetId = it["assetId"] as? String ?: "",
                kind = AssetKind.valueOf((it["kind"] as? String ?: "image").uppercase()),
                mime = it["mime"] as? String ?: "image/png",
                size = (it["size"] as? Long) ?: 0L
            )
        }

        val rawFonts = versionDoc.get("fonts") as? List<Map<String, Any>> ?: emptyList()
        val fontsList = rawFonts.map {
            FontDecl(
                family = it["family"] as? String ?: "Cairo",
                source = FontSource.valueOf((it["source"] as? String ?: "bundled").uppercase()),
                assetId = it["assetId"] as? String,
                weight = (it["weight"] as? Long)?.toInt() ?: 400,
                style = FontStyle.valueOf((it["style"] as? String ?: "normal").uppercase())
            )
        }

        TemplatePackage(
            templateId = templateId,
            version = version,
            schemaVersion = versionDoc.getLong("schemaVersion")?.toInt() ?: 1,
            checksum = versionDoc.getString("checksum") ?: "",
            definitionJson = versionDoc.getString("definitionJson") ?: "{}",
            assets = assetsList, 
            fonts = fontsList,
            note = versionDoc.getString("note") ?: "",
            createdAt = versionDoc.getString("createdAt") ?: ""
        )
    }

    suspend fun uploadAsset(assetId: String, bytes: ByteArray, mime: String): AppResult<Unit> = safeApiCall {
        assetsFolder.child(assetId).putBytes(bytes).await()
        Unit
    }

    suspend fun downloadAsset(assetId: String): AppResult<ByteArray> = safeApiCall {
        assetsFolder.child(assetId).getBytes(10 * 1024 * 1024).await() // حد أقصى 10 ميجا
    }

    suspend fun createTemplate(title: String, definition: TemplateDefinition): AppResult<RuntimeTemplate> = safeApiCall {
        val templateId = "tpl_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.US).format(java.util.Date())
        
        val data = hashMapOf(
            "templateId" to templateId,
            "title" to title,
            "status" to "draft",
            "version" to 1,
            "createdAt" to timestamp,
            "updatedAt" to timestamp
        )
        
        templatesCollection.document(templateId).set(data).await()
        
        RuntimeTemplate(
            templateId = templateId,
            slug = title.lowercase().replace(" ", "-"),
            title = title,
            description = "",
            author = "Admin",
            paperHint = "A4",
            orientationHint = "portrait",
            version = 1,
            schemaVersion = 1,
            checksum = "sha256:pending",
            definitionBytes = 0,
            canvas = CanvasSize(definition.canvas.width, definition.canvas.height),
            tokens = emptyList(),
            assets = AssetStats(0, 0),
            publishedAt = "",
            updatedAt = timestamp
        )
    }

    suspend fun publishTemplate(templateId: String, version: Int): AppResult<Unit> = safeApiCall {
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.US).format(java.util.Date())
        templatesCollection.document(templateId).update(
            mapOf(
                "status" to "published",
                "publishedVersion" to version,
                "publishedAt" to timestamp,
                "updatedAt" to timestamp
            )
        ).await()
        Unit
    }

    private fun mapDocumentToRuntimeTemplate(id: String, data: Map<String, Any>): RuntimeTemplate {
        return RuntimeTemplate(
            templateId = id,
            slug = data["slug"] as? String ?: "",
            title = data["title"] as? String ?: "",
            description = data["description"] as? String ?: "",
            author = data["author"] as? String ?: "",
            paperHint = data["paperHint"] as? String ?: "A4",
            orientationHint = data["orientationHint"] as? String ?: "portrait",
            version = (data["publishedVersion"] as? Long)?.toInt() ?: (data["version"] as? Long)?.toInt() ?: 1,
            schemaVersion = (data["schemaVersion"] as? Long)?.toInt() ?: 1,
            checksum = data["checksum"] as? String ?: "",
            definitionBytes = (data["definitionBytes"] as? Long)?.toInt() ?: 0,
            canvas = CanvasSize(340.0, 256.0),
            tokens = emptyList(),
            assets = AssetStats(0, 0),
            publishedAt = data["publishedAt"] as? String ?: "",
            updatedAt = data["updatedAt"] as? String ?: ""
        )
    }
}

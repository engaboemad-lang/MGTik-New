package ps.mgtik.hotspot.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import ps.mgtik.hotspot.domain.model.RuntimeTemplate

@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey val templateId: String,
    val slug: String,
    val title: String,
    val description: String,
    val author: String,
    val paperHint: String,
    val orientationHint: String,
    val version: Int,
    val schemaVersion: Int,
    val checksum: String,
    val definitionBytes: Int,
    val canvasWidth: Double,
    val canvasHeight: Double,
    val tokens: List<String>,
    val assetCount: Int,
    val assetBytes: Long,
    val publishedAt: String,
    val updatedAt: String,
    val lastSyncAt: Long = System.currentTimeMillis()
)

fun RuntimeTemplate.toEntity() = TemplateEntity(
    templateId = templateId,
    slug = slug,
    title = title,
    description = description,
    author = author,
    paperHint = paperHint,
    orientationHint = orientationHint,
    version = version,
    schemaVersion = schemaVersion,
    checksum = checksum,
    definitionBytes = definitionBytes,
    canvasWidth = canvas.width,
    canvasHeight = canvas.height,
    tokens = tokens,
    assetCount = assets.count,
    assetBytes = assets.bytes,
    publishedAt = publishedAt,
    updatedAt = updatedAt
)

fun TemplateEntity.toDomain() = RuntimeTemplate(
    templateId = templateId,
    slug = slug,
    title = title,
    description = description,
    author = author,
    paperHint = paperHint,
    orientationHint = orientationHint,
    version = version,
    schemaVersion = schemaVersion,
    checksum = checksum,
    definitionBytes = definitionBytes,
    canvas = ps.mgtik.hotspot.domain.model.CanvasSize(canvasWidth, canvasHeight),
    tokens = tokens,
    assets = ps.mgtik.hotspot.domain.model.AssetStats(assetCount, assetBytes),
    publishedAt = publishedAt,
    updatedAt = updatedAt
)

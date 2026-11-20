package ps.mgtik.hotspot.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * MGTik Template Models - المخطط الموحد والنهائي لنظام القوالب (§4)
 * مطابق لمواصفات Template Sync API v1.0.0
 */

@Serializable
data class TemplateDefinition(
    val schema_version: Int = 1,
    val canvas: TemplateCanvas,
    val elements: List<TemplateElement>,
    val iconDefs: Map<String, TemplateIconDef> = emptyMap(),
    val constraints: TemplateConstraints = TemplateConstraints()
)

@Serializable
data class TemplateCanvas(
    val width: Double,
    val height: Double,
    val unit: String = "px",
    val dpi: Int = 96,
    val background: TemplateBackground? = null,
    val header: TemplateBand? = null,
    val footer: TemplateBand? = null
)

@Serializable
data class TemplateBand(
    val height: Double,
    val background: TemplateBackground? = null
)

@Serializable
data class TemplateElement(
    val id: String,
    val type: ElementType,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val zIndex: Int = 0,
    val rotation: Double = 0.0,
    val opacity: Double = 1.0,
    val hidden: Boolean = false,
    val text: String? = null,
    val variable: String? = null,
    val fontFamily: String? = "Cairo",
    val fontSize: Double? = 14.0,
    val fontWeight: String? = "400",
    val color: String? = "#000000",
    val align: TextAlign = TextAlign.LEFT,
    val letterSpacing: Double = 0.0,
    val lineHeight: Double = 1.3,
    val src: String? = null, // asset:<assetId>
    val fit: ImageFit = ImageFit.CONTAIN,
    val borderRadius: Double = 0.0,
    val thickness: Double? = null,
    val lineStyle: LineStyle = LineStyle.SOLID,
    val iconId: String? = null,
    val strokeWidth: Double = 2.0,
    val shapeKind: ShapeKind = ShapeKind.RECT
)

@Serializable
enum class ElementType {
    @SerialName("text") TEXT,
    @SerialName("dynamic") DYNAMIC,
    @SerialName("shape") SHAPE,
    @SerialName("divider") DIVIDER,
    @SerialName("image") IMAGE,
    @SerialName("icon") ICON
}

@Serializable
enum class TextAlign {
    @SerialName("left") LEFT,
    @SerialName("center") CENTER,
    @SerialName("right") RIGHT
}

@Serializable
enum class ImageFit {
    @SerialName("contain") CONTAIN,
    @SerialName("cover") COVER,
    @SerialName("fill") FILL
}

@Serializable
enum class LineStyle {
    @SerialName("solid") SOLID,
    @SerialName("dashed") DASHED,
    @SerialName("dotted") DOTTED
}

@Serializable
enum class ShapeKind {
    @SerialName("rect") RECT,
    @SerialName("circle") CIRCLE
}

@Serializable
data class TemplateBackground(
    val type: BackgroundType,
    val color: String? = null,
    val gradient: TemplateGradient? = null,
    val image: TemplateBackgroundImage? = null
)

@Serializable
enum class BackgroundType {
    @SerialName("color") COLOR,
    @SerialName("gradient") GRADIENT,
    @SerialName("image") IMAGE
}

@Serializable
data class TemplateBackgroundImage(
    val src: String,
    val fit: ImageFit = ImageFit.COVER
)

@Serializable
data class TemplateGradient(
    val from: String,
    val to: String,
    val angle: Double = 180.0
)

@Serializable
data class TemplateIconDef(
    val viewBox: List<Double>,
    val paths: List<TemplatePath>
)

@Serializable
data class TemplatePath(
    val d: String
)

@Serializable
data class TemplateConstraints(
    val minCardWidthPt: Double = 80.0
)

/**
 * الحزم والإصدارات (Runtime)
 */

@Serializable
data class RuntimeTemplate(
    val templateId: String,
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
    val canvas: CanvasSize,
    val tokens: List<String>,
    val assets: AssetStats,
    val publishedAt: String,
    val updatedAt: String
)

@Serializable
data class CanvasSize(val width: Double, val height: Double)

@Serializable
data class AssetStats(val count: Int, val bytes: Long)

@Serializable
data class TemplatePackage(
    val templateId: String,
    val version: Int,
    val schemaVersion: Int,
    val checksum: String,
    val definitionJson: String,
    val assets: List<AssetRef>,
    val fonts: List<FontDecl>,
    val note: String,
    val createdAt: String
)

@Serializable
data class AssetRef(
    val assetId: String,
    val kind: AssetKind,
    val mime: String,
    val size: Long
)

@Serializable
enum class AssetKind {
    @SerialName("image") IMAGE,
    @SerialName("font") FONT
}

@Serializable
data class FontDecl(
    val family: String,
    val source: FontSource,
    val assetId: String? = null,
    val weight: Int = 400,
    val style: FontStyle = FontStyle.NORMAL
)

@Serializable
enum class FontSource {
    @SerialName("bundled") BUNDLED,
    @SerialName("asset") ASSET
}

@Serializable
enum class FontStyle {
    @SerialName("normal") NORMAL,
    @SerialName("italic") ITALIC
}

/**
 * الإعدادات المشتركة
 */

@Serializable
data class RuntimeConfig(
    val revision: Int,
    val currency: Currency,
    val updatedAt: String
)

@Serializable
data class Currency(
    val icon: String,
    val code: String,
    val decimals: Int,
    val position: CurrencyPosition
)

@Serializable
enum class CurrencyPosition {
    @SerialName("prefix") PREFIX,
    @SerialName("suffix") SUFFIX
}

/**
 * المزامنة (Sync)
 */

@Serializable
data class SyncStatus(
    val headToken: String,
    val publishedCount: Int,
    val configRevision: Int,
    val oldestValidToken: String,
    val pendingChanges: Int? = null,
    val upToDate: Boolean,
    val configChanged: Boolean? = null
)

@Serializable
data class ChangesData(
    val changes: List<TemplateChange>,
    val nextToken: String,
    val hasMore: Boolean,
    val headToken: String,
    val configRevision: Int
)

@Serializable
data class TemplateChange(
    val seq: Int,
    val op: SyncOp,
    val templateId: String,
    val at: String,
    val template: RuntimeTemplate? = null,
    val reason: DeleteReason? = null
)

@Serializable
enum class SyncOp {
    @SerialName("upsert") UPSERT,
    @SerialName("delete") DELETE
}

@Serializable
enum class DeleteReason {
    @SerialName("unpublished") UNPUBLISHED,
    @SerialName("deleted") DELETED
}

/**
 * ربط البيانات (§5)
 */
@Serializable
data class VoucherRow(
    val username: String? = null,
    val name: String? = null,
    val password: String? = null,
    val comment: String? = null,
    val profile: String? = null,
    val profile_ar: String? = null,
    val profile_en: String? = null,
    val retail_price: Double? = null,
    val price: Double? = null,
    val invoiced_unit_price: Double? = null,
    val global_sequence: Int? = null,
    val batch_sequence: Int? = null,
    val batch_total_users: Int? = null,
    val batch_id: String? = null,
    val owner: String? = null,
    val owner_id: String? = null,
    val server: String? = null,
    val time_limit: String? = null,
    val data_limit_bytes: Long? = null,
    /** محتوى رمز QR (رابط الدخول مع الكود إن وُجد، وإلا الكود وحده). */
    val qr_content: String? = null
)

/**
 * إعدادات الإخراج (§6)
 */
@Serializable
data class OutputSettings(
    val page: PageSize,
    val orientation: PrintOrientation = PrintOrientation.PORTRAIT,
    val margins: Margins = Margins(24.0, 24.0, 24.0, 24.0),
    val columns: Int = 1,
    val columnGapPt: Double = 10.0,
    val rowGapPt: Double = 10.0,
    val fillDirection: FillDirection = FillDirection.LTR
)

@Serializable
enum class PrintOrientation {
    @SerialName("portrait") PORTRAIT,
    @SerialName("landscape") LANDSCAPE
}

@Serializable
enum class FillDirection {
    @SerialName("ltr") LTR,
    @SerialName("rtl") RTL
}

@Serializable
data class PageSize(val widthPt: Double, val heightPt: Double) {
    companion object {
        val A4 = PageSize(595.28, 841.89)
        val A5 = PageSize(419.53, 595.28)
    }
}

@Serializable
data class Margins(val top: Double, val right: Double, val bottom: Double, val left: Double)

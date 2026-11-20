package ps.mgtik.hotspot.data.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import androidx.core.graphics.PathParser
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import ps.mgtik.hotspot.data.engine.TemplateStorage
import ps.mgtik.hotspot.domain.engine.VoucherResolver
import ps.mgtik.hotspot.domain.model.BackgroundType
import ps.mgtik.hotspot.domain.model.ElementType
import ps.mgtik.hotspot.domain.model.ImageFit
import ps.mgtik.hotspot.domain.model.LineStyle
import ps.mgtik.hotspot.domain.model.ShapeKind
import ps.mgtik.hotspot.domain.model.TemplateBackground
import ps.mgtik.hotspot.domain.model.TemplateDefinition
import ps.mgtik.hotspot.domain.model.TemplateElement
import ps.mgtik.hotspot.domain.model.TemplateIconDef
import ps.mgtik.hotspot.domain.model.TextAlign
import ps.mgtik.hotspot.domain.model.VoucherRow
import java.io.File

/**
 * VoucherCanvasRenderer - محرك الرسم الوحيد (§4.7) لكرت واحد على Canvas، تستخدمه المعاينة وPDF بالمسار نفسه.
 *
 * - QR حقيقي (zxing) يُرسَم متجهياً (مستطيلات) فيبقى حاداً في PDF؛ يُفعَّل بعنصر DYNAMIC متغيّره "qr".
 * - ألوان آمنة: لون تالف في القالب لا يُسقط التصدير (يُستبدل بلون افتراضي).
 * - ذاكرة مؤقتة للصور والخطوط (آلاف الكروت لا تفك الصورة نفسها آلاف المرات).
 * - نص أطول من عرض عنصره يُصغَّر تلقائياً (حتى 50%) بدل أن يتجاوزه.
 * الكائن ليس آمناً للاستخدام من خيوط متعددة (ذاكرته غير متزامنة)؛ ينشأ واحد لكل عملية تصدير/معاينة.
 */
class VoucherCanvasRenderer(
    context: Context,
    private val resolver: VoucherResolver
) {
    private val assetsDir: File = TemplateStorage.assetsDir(context)
    private val bitmaps = HashMap<String, Bitmap?>()
    private val typefaces = HashMap<String, Typeface>()
    private val qrCache = HashMap<String, Array<BooleanArray>?>()

    fun draw(canvas: Canvas, definition: TemplateDefinition, row: VoucherRow, scale: Float = 1.0f) {
        canvas.save()
        canvas.scale(scale, scale)

        drawBackground(canvas, definition.canvas.width, definition.canvas.height, definition.canvas.background)
        definition.elements
            .filter { !it.hidden }
            .sortedBy { it.zIndex }
            .forEach { drawElement(canvas, it, row, definition.iconDefs) }

        canvas.restore()
    }

    // ------------------------------------------------------------------ الخلفية

    private fun drawBackground(canvas: Canvas, w: Double, h: Double, bg: TemplateBackground?) {
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (bg?.type) {
            BackgroundType.COLOR -> paint.color = color(bg.color, Color.WHITE)
            BackgroundType.GRADIENT -> {
                val g = bg.gradient
                if (g != null) {
                    paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), color(g.from, Color.WHITE), color(g.to, Color.WHITE), Shader.TileMode.CLAMP)
                } else paint.color = Color.WHITE
            }
            BackgroundType.IMAGE -> {
                paint.color = Color.WHITE
                canvas.drawRect(rect, paint)
                val src = bg.image?.src?.removePrefix("asset:")
                val bmp = src?.let { loadBitmap(it) }
                if (bmp != null) drawBitmapFit(canvas, bmp, rect, bg.image.fit, 255)
                return
            }
            null -> paint.color = Color.WHITE
        }
        canvas.drawRect(rect, paint)
    }

    // ------------------------------------------------------------------ العناصر

    private fun drawElement(canvas: Canvas, el: TemplateElement, row: VoucherRow, iconDefs: Map<String, TemplateIconDef>) {
        canvas.save()
        if (el.rotation != 0.0) canvas.rotate(el.rotation.toFloat(), (el.x + el.width / 2).toFloat(), (el.y + el.height / 2).toFloat())
        when (el.type) {
            ElementType.DYNAMIC -> if (el.variable == "qr") drawQr(canvas, el, row) else drawText(canvas, el, row)
            ElementType.TEXT -> drawText(canvas, el, row)
            ElementType.SHAPE -> drawShape(canvas, el)
            ElementType.DIVIDER -> drawDivider(canvas, el)
            ElementType.IMAGE -> drawImage(canvas, el)
            ElementType.ICON -> drawIcon(canvas, el, iconDefs)
        }
        canvas.restore()
    }

    private fun drawText(canvas: Canvas, el: TemplateElement, row: VoucherRow) {
        val text = if (el.type == ElementType.DYNAMIC) resolver.resolve(el.variable ?: "", row)
        else resolver.resolveTemplateText(el.text ?: "", row)
        if (text.isEmpty()) return

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = color(el.color, Color.BLACK)
            textSize = (el.fontSize ?: 14.0).toFloat()
            alpha = (el.opacity * 255).toInt().coerceIn(0, 255)
            textAlign = when (el.align) {
                TextAlign.RIGHT -> Paint.Align.RIGHT
                TextAlign.CENTER -> Paint.Align.CENTER
                TextAlign.LEFT -> Paint.Align.LEFT
            }
            typeface = typeface(el.fontFamily, el.fontWeight)
        }
        // تصغير تلقائي: لا يتجاوز النص عرض عنصره
        val measured = paint.measureText(text)
        if (el.width > 0 && measured > el.width) paint.textSize = (paint.textSize * (el.width / measured)).toFloat().coerceAtLeast(paint.textSize * 0.5f)

        val tx = when (el.align) {
            TextAlign.RIGHT -> el.x + el.width
            TextAlign.CENTER -> el.x + el.width / 2
            TextAlign.LEFT -> el.x
        }
        val fm = paint.fontMetrics
        val ty = el.y + (el.height - (fm.descent - fm.ascent)) / 2 - fm.ascent
        canvas.drawText(text, tx.toFloat(), ty.toFloat(), paint)
    }

    private fun drawQr(canvas: Canvas, el: TemplateElement, row: VoucherRow) {
        val content = resolver.resolve("qr", row)
        if (content.isEmpty()) return
        val modules = qrModules(content) ?: return
        val n = modules.size
        val side = minOf(el.width, el.height).toFloat()
        val cell = side / n
        val left = (el.x + (el.width - side) / 2).toFloat()
        val top = (el.y + (el.height - side) / 2).toFloat()

        val path = Path()
        for (y in 0 until n) for (x in 0 until n) {
            if (modules[y][x]) path.addRect(left + x * cell, top + y * cell, left + (x + 1) * cell, top + (y + 1) * cell, Path.Direction.CW)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; alpha = (el.opacity * 255).toInt().coerceIn(0, 255) }
        canvas.drawPath(path, paint)
    }

    private fun qrModules(content: String): Array<BooleanArray>? = qrCache.getOrPut(content) {
        try {
            val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0)
            val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 1, 1, hints)
            Array(m.height) { y -> BooleanArray(m.width) { x -> m[x, y] } }
        } catch (e: Exception) {
            null
        }
    }.also { if (qrCache.size > QR_CACHE_LIMIT) qrCache.clear() }

    private fun drawShape(canvas: Canvas, el: TemplateElement) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = color(el.color, Color.BLACK)
            alpha = (el.opacity * 255).toInt().coerceIn(0, 255)
        }
        val rect = RectF(el.x.toFloat(), el.y.toFloat(), (el.x + el.width).toFloat(), (el.y + el.height).toFloat())
        if (el.shapeKind == ShapeKind.CIRCLE) canvas.drawOval(rect, paint)
        else canvas.drawRoundRect(rect, el.borderRadius.toFloat(), el.borderRadius.toFloat(), paint)
    }

    private fun drawDivider(canvas: Canvas, el: TemplateElement) {
        val t = (el.thickness ?: 1.0).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = color(el.color, Color.BLACK)
            strokeWidth = t
            style = Paint.Style.STROKE
            alpha = (el.opacity * 255).toInt().coerceIn(0, 255)
            if (el.lineStyle == LineStyle.DASHED) pathEffect = DashPathEffect(floatArrayOf(t * 4, t * 2), 0f)
        }
        val cy = (el.y + el.height / 2).toFloat()
        canvas.drawLine(el.x.toFloat(), cy, (el.x + el.width).toFloat(), cy, paint)
    }

    private fun drawImage(canvas: Canvas, el: TemplateElement) {
        val bmp = el.src?.removePrefix("asset:")?.let { loadBitmap(it) } ?: return
        val rect = RectF(el.x.toFloat(), el.y.toFloat(), (el.x + el.width).toFloat(), (el.y + el.height).toFloat())
        drawBitmapFit(canvas, bmp, rect, el.fit, (el.opacity * 255).toInt().coerceIn(0, 255))
    }

    private fun drawBitmapFit(canvas: Canvas, bmp: Bitmap, rect: RectF, fit: ImageFit, alpha: Int) {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha }
        val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
        when (fit) {
            ImageFit.FILL -> canvas.drawBitmap(bmp, null, rect, paint)
            ImageFit.CONTAIN, ImageFit.COVER -> {
                val s = if (fit == ImageFit.CONTAIN) minOf(rect.width() / bw, rect.height() / bh) else maxOf(rect.width() / bw, rect.height() / bh)
                val dw = bw * s; val dh = bh * s
                val dst = RectF(rect.centerX() - dw / 2, rect.centerY() - dh / 2, rect.centerX() + dw / 2, rect.centerY() + dh / 2)
                canvas.save()
                canvas.clipRect(rect)
                canvas.drawBitmap(bmp, null, dst, paint)
                canvas.restore()
            }
        }
    }

    private fun drawIcon(canvas: Canvas, el: TemplateElement, iconDefs: Map<String, TemplateIconDef>) {
        val def = iconDefs[el.iconId] ?: return
        if (def.viewBox.size < 4) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = color(el.color, Color.BLACK)
            strokeWidth = el.strokeWidth.toFloat()
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            alpha = (el.opacity * 255).toInt().coerceIn(0, 255)
        }
        canvas.save()
        canvas.translate(el.x.toFloat(), el.y.toFloat())
        val s = minOf(el.width.toFloat() / def.viewBox[2].toFloat(), el.height.toFloat() / def.viewBox[3].toFloat())
        canvas.scale(s, s)
        canvas.translate(-def.viewBox[0].toFloat(), -def.viewBox[1].toFloat())
        def.paths.forEach { pathDef ->
            try { canvas.drawPath(PathParser.createPathFromPathData(pathDef.d), paint) } catch (e: Exception) { /* مسار تالف: يُتخطى */ }
        }
        canvas.restore()
    }

    // ------------------------------------------------------------------ مساعدات

    private fun color(value: String?, fallback: Int): Int =
        try { if (value.isNullOrBlank()) fallback else Color.parseColor(value) } catch (e: IllegalArgumentException) { fallback }

    private fun loadBitmap(assetId: String): Bitmap? = bitmaps.getOrPut(assetId) {
        val f = File(assetsDir, assetId)
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    private fun typeface(family: String?, weight: String?): Typeface {
        val bold = weight == "700" || weight.equals("bold", ignoreCase = true)
        return typefaces.getOrPut("${family?.lowercase()}|$bold") {
            val style = if (bold) Typeface.BOLD else Typeface.NORMAL
            if (family?.lowercase() == "cairo") Typeface.create("sans-serif", style) else Typeface.create(Typeface.DEFAULT, style)
        }
    }

    private companion object { const val QR_CACHE_LIMIT = 2000 }
}

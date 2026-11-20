package ps.mgtik.hotspot.data.export

import android.graphics.Canvas
import ps.mgtik.hotspot.domain.export.LayoutPlanner
import ps.mgtik.hotspot.domain.model.OutputSettings
import ps.mgtik.hotspot.domain.model.TemplateDefinition
import ps.mgtik.hotspot.domain.model.VoucherRow

/**
 * VoucherPageRenderer - يرسم صفحة كاملة من الكروت بحسب خطة LayoutPlanner.
 * يُستدعى من المعاينة (Bitmap) ومن PDF (PdfDocument.Page) فلا يختلف المعروض عن المطبوع.
 */
class VoucherPageRenderer(private val renderer: VoucherCanvasRenderer) {

    fun drawPage(
        canvas: Canvas,
        plan: LayoutPlanner.LayoutPlan,
        settings: OutputSettings,
        definition: TemplateDefinition,
        rows: List<VoucherRow>,
        pageIndex: Int
    ) {
        val first = pageIndex * plan.cardsPerPage
        for (i in 0 until plan.cardsOnPage(pageIndex)) {
            val index = first + i
            val p = plan.placement(index, settings.fillDirection, settings.margins, settings.columnGapPt, settings.rowGapPt)
            canvas.save()
            canvas.translate(p.xPt.toFloat(), p.yPt.toFloat())
            canvas.clipRect(0f, 0f, plan.cardWidthPt.toFloat(), plan.cardHeightPt.toFloat())
            renderer.draw(canvas, definition, rows[index], plan.scale.toFloat())
            canvas.restore()
        }
    }
}

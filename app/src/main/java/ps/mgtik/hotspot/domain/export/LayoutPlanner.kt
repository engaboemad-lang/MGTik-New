package ps.mgtik.hotspot.domain.export

import ps.mgtik.hotspot.domain.model.CanvasSize
import ps.mgtik.hotspot.domain.model.FillDirection
import ps.mgtik.hotspot.domain.model.OutputSettings
import ps.mgtik.hotspot.domain.model.PrintOrientation
import ps.mgtik.hotspot.domain.model.TemplateConstraints
import kotlin.math.ceil
import kotlin.math.floor

/** خطأ تخطيط قابل للعرض للمستخدم (الكود يُترجم في الواجهة). */
class LayoutException(val code: String, message: String) : Exception(message) {
    companion object {
        const val INVALID_MARGINS = "ERR_LAYOUT_INVALID_MARGINS"
        const val CARD_TOO_SMALL = "ERR_LAYOUT_CARD_TOO_SMALL"
        const val CARD_TALLER_THAN_PAGE = "ERR_LAYOUT_CARD_TALLER_THAN_PAGE"
        const val INVALID_COLUMNS = "ERR_LAYOUT_INVALID_COLUMNS"
    }
}

/**
 * LayoutPlanner - خوارزمية توزيع الكروت على الصفحات (§6.2). نقية (بلا Android) ومشتركة بين المعاينة والتصدير،
 * فيطابق ما تراه ما يُطبع. القياس موحَّد النسبة (عرض وارتفاع معاً) فلا يتشوه الكرت.
 */
object LayoutPlanner {

    const val MAX_COLUMNS = 6

    data class CardPlacement(val pageIndex: Int, val xPt: Double, val yPt: Double)

    data class LayoutPlan(
        val pageWidthPt: Double,
        val pageHeightPt: Double,
        val cardWidthPt: Double,
        val cardHeightPt: Double,
        /** نسبة تكبير الرسم من لوحة القالب إلى النقاط (موحَّدة). */
        val scale: Double,
        val columns: Int,
        val rows: Int,
        val cardsPerPage: Int,
        val pageCount: Int,
        val cardCount: Int
    ) {
        /** موضع الكرت رقم [index] (من 0) دون تخزين قائمة ضخمة لآلاف الكروت. */
        fun placement(index: Int, fillDirection: FillDirection, margins: ps.mgtik.hotspot.domain.model.Margins, columnGapPt: Double, rowGapPt: Double): CardPlacement {
            val page = index / cardsPerPage
            val inPage = index % cardsPerPage
            val row = inPage / columns
            var col = inPage % columns
            if (fillDirection == FillDirection.RTL) col = columns - 1 - col
            return CardPlacement(
                pageIndex = page,
                xPt = margins.left + col * (cardWidthPt + columnGapPt),
                yPt = margins.top + row * (cardHeightPt + rowGapPt)
            )
        }

        /** عدد الكروت في الصفحة [pageIndex] (الأخيرة قد تكون جزئية). */
        fun cardsOnPage(pageIndex: Int): Int = minOf(cardsPerPage, cardCount - pageIndex * cardsPerPage).coerceAtLeast(0)
    }

    fun plan(canvas: CanvasSize, constraints: TemplateConstraints, settings: OutputSettings, count: Int): LayoutPlan {
        if (settings.columns !in 1..MAX_COLUMNS) throw LayoutException(LayoutException.INVALID_COLUMNS, "Columns must be 1..$MAX_COLUMNS")

        val landscape = settings.orientation == PrintOrientation.LANDSCAPE
        val pageW = if (landscape) settings.page.heightPt else settings.page.widthPt
        val pageH = if (landscape) settings.page.widthPt else settings.page.heightPt

        val contentW = pageW - settings.margins.left - settings.margins.right
        val contentH = pageH - settings.margins.top - settings.margins.bottom
        if (contentW <= 0 || contentH <= 0) throw LayoutException(LayoutException.INVALID_MARGINS, "Margins exceed the page")

        val cardW = (contentW - settings.columnGapPt * (settings.columns - 1)) / settings.columns
        if (cardW < constraints.minCardWidthPt) throw LayoutException(LayoutException.CARD_TOO_SMALL, "Card width $cardW < ${constraints.minCardWidthPt}")

        val scale = cardW / canvas.width
        val cardH = canvas.height * scale

        val rows = floor((contentH + settings.rowGapPt) / (cardH + settings.rowGapPt) + 1e-9).toInt()
        if (rows < 1) throw LayoutException(LayoutException.CARD_TALLER_THAN_PAGE, "Card is taller than the page")

        val perPage = rows * settings.columns
        val pages = if (count <= 0) 0 else ceil(count.toDouble() / perPage).toInt()
        return LayoutPlan(pageW, pageH, cardW, cardH, scale, settings.columns, rows, perPage, pages, count)
    }
}

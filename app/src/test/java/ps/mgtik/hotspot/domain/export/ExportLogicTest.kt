package ps.mgtik.hotspot.domain.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.engine.VoucherResolver
import ps.mgtik.hotspot.domain.model.CanvasSize
import ps.mgtik.hotspot.domain.model.Currency
import ps.mgtik.hotspot.domain.model.CurrencyPosition
import ps.mgtik.hotspot.domain.model.ElementType
import ps.mgtik.hotspot.domain.model.FillDirection
import ps.mgtik.hotspot.domain.model.Margins
import ps.mgtik.hotspot.domain.model.OutputSettings
import ps.mgtik.hotspot.domain.model.PageSize
import ps.mgtik.hotspot.domain.model.PrintOrientation
import ps.mgtik.hotspot.domain.model.TemplateConstraints
import ps.mgtik.hotspot.domain.model.VoucherRow
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

class ExportLogicTest {

    private val canvas = CanvasSize(300.0, 170.0)
    private val cons = TemplateConstraints()
    private val settings = OutputSettings(PageSize.A4, PrintOrientation.PORTRAIT, Margins(24.0, 24.0, 24.0, 24.0), columns = 2, columnGapPt = 10.0, rowGapPt = 10.0)

    private fun near(a: Double, b: Double) = abs(a - b) < 1e-6

    @Test
    fun layout_keeps_aspect_ratio_and_fits_the_page() {
        val p = LayoutPlanner.plan(canvas, cons, settings, 25)
        assertTrue(near(p.cardWidthPt, (595.28 - 48 - 10) / 2))
        assertTrue(near(p.cardHeightPt / p.cardWidthPt, 170.0 / 300.0))
        assertTrue(near(p.scale, p.cardWidthPt / 300.0))
        val rows = floor((841.89 - 48 + 10) / (p.cardHeightPt + 10) + 1e-9).toInt()
        assertEquals(rows, p.rows)
        assertEquals(ceil(25.0 / p.cardsPerPage).toInt(), p.pageCount)
        assertEquals(25 - (p.pageCount - 1) * p.cardsPerPage, p.cardsOnPage(p.pageCount - 1))
    }

    @Test
    fun cards_never_overlap_and_stay_inside_margins() {
        val p = LayoutPlanner.plan(canvas, cons, settings, 25)
        val boxes = (0 until 25).map { p.placement(it, FillDirection.LTR, settings.margins, settings.columnGapPt, settings.rowGapPt) }
        boxes.forEach {
            assertTrue(it.xPt >= settings.margins.left - 1e-6)
            assertTrue(it.xPt + p.cardWidthPt <= p.pageWidthPt - settings.margins.right + 1e-6)
            assertTrue(it.yPt + p.cardHeightPt <= p.pageHeightPt - settings.margins.bottom + 1e-6)
        }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val a = boxes[i]; val b = boxes[j]
            if (a.pageIndex != b.pageIndex) continue
            val overlap = a.xPt < b.xPt + p.cardWidthPt - 1e-6 && b.xPt < a.xPt + p.cardWidthPt - 1e-6 &&
                a.yPt < b.yPt + p.cardHeightPt - 1e-6 && b.yPt < a.yPt + p.cardHeightPt - 1e-6
            assertFalse("cards $i and $j overlap", overlap)
        }
    }

    @Test
    fun rtl_mirrors_columns_and_landscape_swaps_the_page() {
        val p = LayoutPlanner.plan(canvas, cons, settings, 10)
        val ltr = p.placement(0, FillDirection.LTR, settings.margins, 10.0, 10.0)
        val rtl = p.placement(0, FillDirection.RTL, settings.margins, 10.0, 10.0)
        assertTrue(ltr.xPt < rtl.xPt)
        val land = LayoutPlanner.plan(canvas, cons, settings.copy(orientation = PrintOrientation.LANDSCAPE), 10)
        assertTrue(near(land.pageWidthPt, 841.89) && near(land.pageHeightPt, 595.28))
    }

    @Test
    fun impossible_layouts_fail_with_a_displayable_code() {
        fun code(f: () -> Unit): String? = try { f(); null } catch (e: LayoutException) { e.code }
        assertEquals(LayoutException.INVALID_COLUMNS, code { LayoutPlanner.plan(canvas, cons, settings.copy(columns = 0), 5) })
        assertEquals(LayoutException.INVALID_COLUMNS, code { LayoutPlanner.plan(canvas, cons, settings.copy(columns = 7), 5) })
        assertEquals(LayoutException.INVALID_MARGINS, code { LayoutPlanner.plan(canvas, cons, settings.copy(margins = Margins(24.0, 400.0, 24.0, 400.0)), 5) })
        assertEquals(LayoutException.CARD_TOO_SMALL, code { LayoutPlanner.plan(canvas, cons, settings.copy(page = PageSize.A5, columns = 6), 5) })
        assertEquals(LayoutException.CARD_TALLER_THAN_PAGE, code { LayoutPlanner.plan(CanvasSize(100.0, 5000.0), cons, settings.copy(columns = 1), 5) })
        assertEquals(0, LayoutPlanner.plan(canvas, cons, settings, 0).pageCount)
    }

    @Test
    fun five_thousand_cards_paginate_correctly() {
        val p = LayoutPlanner.plan(canvas, cons, settings, 5000)
        assertTrue(p.pageCount * p.cardsPerPage >= 5000 && (p.pageCount - 1) * p.cardsPerPage < 5000)
    }

    @Test
    fun txt_format_is_stable_and_tab_safe() {
        val txt = VoucherTxtFormatter.format(
            "Batch: B-000001 | Package: Gold | Count: 2", "Username\tPassword",
            listOf(VoucherTxtFormatter.Row("12345", "12345"), VoucherTxtFormatter.Row("ab\tc", "x\ny"))
        )
        val lines = txt.split('\n')
        assertEquals(5, lines.size)
        assertEquals("Username\tPassword", lines[1])
        assertEquals("12345\t12345", lines[2])
        assertEquals("ab c\tx y", lines[3])
        assertEquals("", lines[4])
    }

    @Test
    fun qr_content_uses_login_url_when_available() {
        assertEquals("abc", VoucherQr.content("", "abc", "x"))
        assertEquals("http://wifi.lan/login?username=u1&password=p%401", VoucherQr.content("http://wifi.lan/login", "u1", "p@1"))
        assertEquals("http://wifi.lan/login?mode=1&username=u1&password=p", VoucherQr.content("http://wifi.lan/login?mode=1", "u1", "p"))
    }

    @Test
    fun resolver_substitutes_once_and_supports_qr() {
        val r = VoucherResolver(Currency("₪", "ILS", 2, CurrencyPosition.SUFFIX))
        val row = VoucherRow(username = "u1", password = "{{username}}", price = 12.5, qr_content = "http://x")
        assertEquals("P={{username}} U=u1", r.resolveTemplateText("P={{password}} U={{username}}", row))
        assertEquals("12.50 ₪", r.resolve("price_formatted", row))
        assertEquals("http://x", r.resolve("qr", row))
        assertEquals("zz", r.resolve("qr", VoucherRow(username = "zz")))
        assertEquals("ab", r.resolveTemplateText("a{{nope}}b", row))
    }

    @Test
    fun default_template_is_valid_and_fits_a4() {
        val t = DefaultVoucherTemplate.definition
        assertEquals(t.elements.size, t.elements.map { it.id }.toSet().size)
        assertTrue(t.elements.all { it.x >= 0 && it.y >= 0 && it.x + it.width <= t.canvas.width + 1e-6 && it.y + it.height <= t.canvas.height + 1e-6 })
        val vars = t.elements.filter { it.type == ElementType.DYNAMIC }.mapNotNull { it.variable }
        assertTrue(listOf("username", "password", "qr").all { it in vars })
        LayoutPlanner.plan(CanvasSize(t.canvas.width, t.canvas.height), t.constraints, settings, 10)
    }
}

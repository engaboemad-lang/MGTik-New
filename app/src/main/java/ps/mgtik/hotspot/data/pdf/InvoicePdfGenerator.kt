package ps.mgtik.hotspot.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.view.View
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.util.formatMinor
import ps.mgtik.hotspot.domain.model.InvoiceStatus
import ps.mgtik.hotspot.domain.model.PaymentStatus
import ps.mgtik.hotspot.domain.usecase.InvoiceDocument
import ps.mgtik.hotspot.ui.util.InvoiceTexts
import ps.mgtik.hotspot.ui.util.labelRes
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * مولّد PDF للفاتورة بـ android.graphics.pdf.PdfDocument (لا مكتبة جديدة).
 *  - النص عبر StaticLayout: تشكيل الحروف العربية واتجاه RTL يتولاهما Android؛ الأرقام والعملة معزولة LTR.
 *  - مرّتان بالمخطط نفسه: الأولى (بلا رسم) لعدّ الصفحات، والثانية ترسم "صفحة n من N".
 *  - القيم كلها من [InvoiceDocument] (فاتورة محفوظة + لقطة سعر الدفعات)؛ لا وصول لأي سعر حالي.
 */
@Singleton
class InvoicePdfGenerator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun generate(doc: InvoiceDocument, out: File): AppResult<File> = withContext(Dispatchers.IO) {
        try {
            val logo = doc.company.logoUrl?.let { loadLogo(it) }
            val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val pages = Painter(context, doc, logo, rtl, null, 0).run().pageCount
            val pdf = PdfDocument()
            try {
                Painter(context, doc, logo, rtl, pdf, pages).run()
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { pdf.writeTo(it) }
            } finally {
                pdf.close()
            }
            AppResult.Success(out)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Error(ApiError.mapThrowable(e))
        }
    }

    /** شعار اختياري: فشل التحميل (شبكة/صيغة) لا يمنع الفاتورة. */
    private fun loadLogo(url: String): Bitmap? = try {
        when {
            url.startsWith("http", ignoreCase = true) -> (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 4000; readTimeout = 4000
                try { inputStream.use { BitmapFactory.decodeStream(it) } } finally { disconnect() }
            }
            else -> context.contentResolver.openInputStream(android.net.Uri.parse(url))?.use { BitmapFactory.decodeStream(it) }
        }
    } catch (e: Exception) {
        null
    }

    private class Painter(
        private val ctx: Context,
        private val doc: InvoiceDocument,
        private val logo: Bitmap?,
        private val rtl: Boolean,
        private val pdf: PdfDocument?,
        private val totalPages: Int
    ) {
        var pageCount = 0
            private set
        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private var open = false
        private var y = 0f

        private val pageW = 595
        private val pageH = 842
        private val margin = 40f
        private val contentW = pageW - 2 * margin
        private val footerH = 34f
        private val bottom get() = pageH - margin - footerH

        private val ink = Color.rgb(0x1B, 0x1F, 0x24)
        private val muted = Color.rgb(0x5F, 0x68, 0x73)
        private val line = Color.rgb(0xCF, 0xD4, 0xDA)
        private val band = Color.rgb(0xEE, 0xF1, 0xF4)

        private fun paint(size: Float, bold: Boolean = false, color: Int = ink) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        }

        private val body = paint(11f)
        private val bodyBold = paint(11f, true)
        private val small = paint(9f, color = muted)
        private val title = paint(22f, true)
        private val h1 = paint(15f, true)

        private fun s(id: Int, vararg a: Any) = ctx.getString(id, *a)
        private fun ltr(t: String) = InvoiceTexts.ltr(t)
        private fun money(minor: Long) = ltr("${formatMinor(minor)} ${doc.currency}")
        private fun date(iso: String?) = InvoiceTexts.dateOnly(iso).let { if (it.isEmpty()) "" else ltr(it) }

        private fun layout(text: String, p: TextPaint, width: Float, align: Layout.Alignment): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, p, width.toInt().coerceAtLeast(1))
                .setAlignment(align)
                .setTextDirection(if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR)
                .setIncludePad(false)
                .build()

        private fun draw(l: StaticLayout, x: Float, top: Float) {
            val c = canvas ?: return
            c.save(); c.translate(x, top); l.draw(c); c.restore()
        }

        /** صندوق عمود يبدأ بعد [offset] من جهة البداية (يمين في RTL). */
        private fun boxX(offset: Float, width: Float) = if (rtl) margin + contentW - offset - width else margin + offset

        private val start = Layout.Alignment.ALIGN_NORMAL
        private val end = Layout.Alignment.ALIGN_OPPOSITE

        private fun startPage() {
            finishPage()
            pageCount++
            if (pdf != null) {
                page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageCount).create())
                canvas = page!!.canvas.also { it.drawColor(Color.WHITE) }
            }
            open = true
            y = margin
            if (pageCount > 1) {
                val l = layout("${s(R.string.inv_pdf_title)} ${ltr(doc.invoiceNumber)}", small, contentW, start)
                draw(l, margin, y); y += l.height + 8f
                rule()
            }
        }

        private fun finishPage() {
            if (!open) return
            val foot = layout(s(R.string.inv_pdf_page, pageCount, if (totalPages > 0) totalPages else pageCount), small, contentW, Layout.Alignment.ALIGN_CENTER)
            draw(foot, margin, pageH - margin - foot.height)
            canvas?.drawLine(margin, pageH - margin - footerH + 8f, margin + contentW, pageH - margin - footerH + 8f, Paint().apply { color = line; strokeWidth = 0.8f })
            page?.let { pdf?.finishPage(it) }
            page = null; canvas = null; open = false
        }

        private fun ensure(h: Float, redrawHeader: (() -> Unit)? = null) {
            if (y + h > bottom) { startPage(); redrawHeader?.invoke() }
        }

        private fun rule() {
            canvas?.drawLine(margin, y, margin + contentW, y, Paint().apply { color = line; strokeWidth = 0.8f })
            y += 8f
        }

        private fun fillBand(h: Float) {
            canvas?.drawRect(margin, y, margin + contentW, y + h, Paint().apply { color = band })
        }

        /** صف مفتاح/قيمة: المفتاح عند البداية والقيمة عند النهاية داخل [boxW]. */
        private fun keyValue(label: String, value: String, lp: TextPaint, vp: TextPaint, boxW: Float = contentW, boxOffset: Float = 0f) {
            val x = boxX(boxOffset, boxW)
            val a = layout(label, lp, boxW / 2f, start)
            val b = layout(value, vp, boxW / 2f, end)
            ensure(maxOf(a.height, b.height) + 4f)
            draw(a, x, y); draw(b, x + boxW / 2f, y)
            y += maxOf(a.height, b.height) + 4f
        }

        fun run(): Painter {
            startPage()
            drawHeader()
            drawParties()
            drawItems()
            drawTotals()
            drawPayments()
            drawNotes()
            finishPage()
            return this
        }

        private fun drawHeader() {
            val logoBox = 56f
            val textW = if (logo != null) contentW - logoBox - 12f else contentW
            val top = y
            var textY = top
            val name = doc.company.name
            if (name.isNotBlank()) { val l = layout(name, h1, textW, start); draw(l, boxX(0f, textW), textY); textY += l.height + 3f }
            listOf(doc.company.address, doc.company.phone, doc.company.whatsapp).filter { it.isNotBlank() }.forEach {
                val l = layout(if (it == doc.company.phone || it == doc.company.whatsapp) ltr(it) else it, small, textW, start)
                draw(l, boxX(0f, textW), textY); textY += l.height + 1f
            }
            logo?.let { bmp ->
                val ratio = minOf(logoBox / bmp.width, logoBox / bmp.height)
                val w = bmp.width * ratio; val h = bmp.height * ratio
                val x = if (rtl) margin else margin + contentW - w
                canvas?.drawBitmap(bmp, null, Rect(x.toInt(), top.toInt(), (x + w).toInt(), (top + h).toInt()), null)
                textY = maxOf(textY, top + h)
            }
            y = maxOf(textY, top) + 10f
            rule()

            // العنوان + الحالة
            val t = layout(s(R.string.inv_pdf_title), title, contentW * 0.55f, start)
            val statusText = s(doc.status.labelRes()) + if (doc.overdue) " • " + s(R.string.inv_status_overdue) else ""
            val st = layout(statusText, bodyBold, contentW * 0.4f, end)
            draw(t, boxX(0f, contentW * 0.55f), y)
            draw(st, boxX(contentW * 0.6f, contentW * 0.4f), y + (t.height - st.height) / 2f)
            y += t.height + 8f
        }

        private fun drawParties() {
            keyValue(s(R.string.inv_pdf_number), ltr(doc.invoiceNumber), small, bodyBold)
            date(doc.issueDateIso).takeIf { it.isNotEmpty() }?.let { keyValue(s(R.string.inv_pdf_issue_date), it, small, body) }
            date(doc.dueDateIso).takeIf { it.isNotEmpty() }?.let { keyValue(s(R.string.inv_pdf_due_date), it, small, body) }
            y += 6f
            val who = layout(s(R.string.inv_pdf_bill_to), small, contentW, start)
            draw(who, margin, y); y += who.height + 2f
            val owner = layout(doc.ownerName.ifBlank { "—" }, bodyBold, contentW, start)
            draw(owner, margin, y); y += owner.height
            if (doc.ownerBusinessId.isNotBlank()) {
                val b = layout(ltr(doc.ownerBusinessId), small, contentW, start)
                draw(b, margin, y); y += b.height
            }
            y += 12f
        }

        // أعمدة جدول البنود: الوصف | الكمية | سعر الوحدة | الإجمالي
        private val wQty = 50f; private val wUnit = 90f; private val wTot = 100f
        private val wDesc get() = contentW - wQty - wUnit - wTot

        private fun itemsHeader() {
            val h = 22f
            fillBand(h)
            fun cell(text: String, off: Float, w: Float, al: Layout.Alignment) {
                val l = layout(text, bodyBold, w - 8f, al); draw(l, boxX(off, w) + 4f, y + (h - l.height) / 2f)
            }
            cell(s(R.string.inv_pdf_col_desc), 0f, wDesc, start)
            cell(s(R.string.inv_pdf_col_qty), wDesc, wQty, Layout.Alignment.ALIGN_CENTER)
            cell(s(R.string.inv_pdf_col_unit), wDesc + wQty, wUnit, end)
            cell(s(R.string.inv_pdf_col_total), wDesc + wQty + wUnit, wTot, end)
            y += h + 2f
        }

        private fun itemRow(desc: String, qty: String, unit: String, total: String) {
            val cells = listOf(
                layout(desc, body, wDesc - 8f, start),
                layout(qty, body, wQty - 8f, Layout.Alignment.ALIGN_CENTER),
                layout(unit, body, wUnit - 8f, end),
                layout(total, bodyBold, wTot - 8f, end)
            )
            val h = cells.maxOf { it.height } + 10f
            ensure(h) { itemsHeader() }
            val xs = listOf(boxX(0f, wDesc), boxX(wDesc, wQty), boxX(wDesc + wQty, wUnit), boxX(wDesc + wQty + wUnit, wTot))
            cells.forEachIndexed { i, l -> draw(l, xs[i] + 4f, y + 5f) }
            y += h
            canvas?.drawLine(margin, y, margin + contentW, y, Paint().apply { color = line; strokeWidth = 0.5f })
        }

        private fun drawItems() {
            ensure(60f)
            itemsHeader()
            if (doc.linesComplete) {
                doc.lines.forEach {
                    itemRow(it.description.ifBlank { s(R.string.inv_pdf_item_fallback) }, ltr(it.quantity.toString()), money(it.unitMinor), money(it.totalMinor))
                }
            } else {
                // بنود تفصيلية غير متوفرة/غير مطابقة للإجمالي: سطر إجمالي واحد صادق بدل بنود مخترعة
                itemRow(s(R.string.inv_pdf_item_summary), "", "", money(doc.subtotalMinor))
            }
            y += 12f
        }

        private fun drawTotals() {
            val boxW = 250f
            ensure(110f)
            keyValue(s(R.string.inv_pdf_subtotal), money(doc.subtotalMinor), body, body, boxW, contentW - boxW)
            if (doc.discountMinor > 0) keyValue(s(R.string.inv_pdf_discount), money(-doc.discountMinor), body, body, boxW, contentW - boxW)
            keyValue(s(R.string.inv_pdf_total), money(doc.totalMinor), bodyBold, bodyBold, boxW, contentW - boxW)
            keyValue(s(R.string.inv_pdf_paid), money(doc.paidMinor), body, body, boxW, contentW - boxW)
            keyValue(s(R.string.inv_pdf_remaining), money(doc.remainingMinor), paint(13f, true), paint(13f, true), boxW, contentW - boxW)
            y += 14f
        }

        private fun paymentsHeader() {
            val h = 20f
            fillBand(h)
            val wDate = 80f; val wMethod = 100f; val wStatus = 130f; val wAmt = contentW - wDate - wMethod - wStatus
            fun cell(text: String, off: Float, w: Float, al: Layout.Alignment) {
                val l = layout(text, bodyBold, w - 8f, al); draw(l, boxX(off, w) + 4f, y + (h - l.height) / 2f)
            }
            cell(s(R.string.inv_pdf_pay_date), 0f, wDate, start)
            cell(s(R.string.inv_pdf_pay_method), wDate, wMethod, start)
            cell(s(R.string.inv_pdf_pay_status), wDate + wMethod, wStatus, start)
            cell(s(R.string.inv_pdf_pay_amount), wDate + wMethod + wStatus, wAmt, end)
            y += h + 2f
        }

        private fun drawPayments() {
            if (doc.payments.isEmpty()) return
            ensure(70f)
            val t = layout(s(R.string.inv_pdf_payments), h1, contentW, start)
            draw(t, margin, y); y += t.height + 6f
            paymentsHeader()
            val wDate = 80f; val wMethod = 100f; val wStatus = 130f; val wAmt = contentW - wDate - wMethod - wStatus
            doc.payments.forEach { p ->
                val statusText = s(InvoiceTexts.paymentStatusRes(p.status)) +
                    if (p.status == PaymentStatus.REFUNDED && p.refundedMinor > 0) " (" + money(p.refundedMinor) + ")" else ""
                val cells = listOf(
                    layout(date(p.dateIso), body, wDate - 8f, start),
                    layout(s(InvoiceTexts.methodRes(p.method)) + (p.reference?.takeIf { it.isNotBlank() }?.let { " • " + ltr(it) } ?: ""), body, wMethod - 8f, start),
                    layout(statusText, body, wStatus - 8f, start),
                    layout(money(p.amountMinor), bodyBold, wAmt - 8f, end)
                )
                val h = cells.maxOf { it.height } + 8f
                ensure(h) { paymentsHeader() }
                val xs = listOf(boxX(0f, wDate), boxX(wDate, wMethod), boxX(wDate + wMethod, wStatus), boxX(wDate + wMethod + wStatus, wAmt))
                cells.forEachIndexed { i, l -> draw(l, xs[i] + 4f, y + 4f) }
                y += h
                canvas?.drawLine(margin, y, margin + contentW, y, Paint().apply { color = line; strokeWidth = 0.5f })
            }
            y += 12f
        }

        private fun drawNotes() {
            InvoiceTexts.displayNotes(ctx, doc.notes)?.let {
                val l = layout(it, small, contentW, start); ensure(l.height + 6f); draw(l, margin, y); y += l.height + 8f
            }
            doc.footerNote?.let {
                val l = layout(it, small, contentW, Layout.Alignment.ALIGN_CENTER); ensure(l.height + 6f); draw(l, margin, y); y += l.height
            }
        }
    }
}

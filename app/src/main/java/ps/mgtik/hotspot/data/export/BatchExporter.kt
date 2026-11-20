package ps.mgtik.hotspot.data.export

import android.content.Context
import android.graphics.pdf.PdfDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.engine.VoucherResolver
import ps.mgtik.hotspot.domain.export.DefaultVoucherTemplate
import ps.mgtik.hotspot.domain.export.ExportFormat
import ps.mgtik.hotspot.domain.export.LayoutException
import ps.mgtik.hotspot.domain.export.LayoutPlanner
import ps.mgtik.hotspot.domain.export.VoucherQr
import ps.mgtik.hotspot.domain.export.VoucherTxtFormatter
import ps.mgtik.hotspot.domain.model.CanvasSize
import ps.mgtik.hotspot.domain.model.Currency
import ps.mgtik.hotspot.domain.model.CurrencyPosition
import ps.mgtik.hotspot.domain.model.Voucher
import ps.mgtik.hotspot.domain.model.OutputSettings
import ps.mgtik.hotspot.domain.model.RuntimeTemplate
import ps.mgtik.hotspot.domain.model.TemplateDefinition
import ps.mgtik.hotspot.domain.model.VoucherRow
import ps.mgtik.hotspot.domain.repository.IGenerationRepository
import ps.mgtik.hotspot.domain.repository.ISettingsRepository
import ps.mgtik.hotspot.domain.repository.ITemplateRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** ما تحتاجه الشاشة لمعاينة/تصدير دفعة: الكروت جاهزة كصفوف قالب + القالب المحمَّل. */
data class ExportSource(val rows: List<VoucherRow>, val definition: TemplateDefinition, val currencySymbol: String)

/**
 * BatchExporter - يبني ملف تصدير لدفعة كاملة (PDF أو TXT) في cacheDir/exports.
 * الدفعة كلها دائماً (لا فلترة)، ولا يُسجَّل «طُبع» على الدفعة: التصدير بلا أثر جانبي وقابل للتكرار.
 */
@Singleton
class BatchExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val generationRepository: IGenerationRepository,
    private val templateRepository: ITemplateRepository,
    private val settingsRepository: ISettingsRepository
) {

    /** يحمّل كروت الدفعة والقالب (المحدَّد أو المضمَّن) ويحوّلها لصفوف القالب. */
    suspend fun loadSource(batchId: String, template: RuntimeTemplate?, profileDisplayAr: String): AppResult<ExportSource> = withContext(Dispatchers.IO) {
        val vouchers = when (val r = generationRepository.getBatchVouchers(batchId)) {
            is AppResult.Success -> r.data
            is AppResult.Error -> return@withContext AppResult.Error(r.error)
            else -> return@withContext AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }
        if (vouchers.isEmpty()) return@withContext AppResult.Error(ApiError(ERR_NO_VOUCHERS, "No vouchers to export"))

        val company = settingsRepository.getCompanyInfo().first()
        val definition = if (template == null) DefaultVoucherTemplate.definition
        else when (val d = templateRepository.loadDefinition(template)) {
            is AppResult.Success -> d.data
            is AppResult.Error -> return@withContext AppResult.Error(ApiError(ERR_TEMPLATE_UNAVAILABLE, d.error.message))
            else -> return@withContext AppResult.Error(ApiError(ERR_TEMPLATE_UNAVAILABLE, "Template unavailable"))
        }
        val rows = vouchers.sortedBy { it.serialNumber }.mapIndexed { i, v -> v.toRow(i + 1, vouchers.size, profileDisplayAr, company.loginUrl) }
        AppResult.Success(ExportSource(rows, definition, company.currencySymbol))
    }

    /** صفحة معاينة كـ Bitmap بالمحرك نفسه المستخدم في PDF. */
    fun renderPreview(source: ExportSource, settings: OutputSettings, pageIndex: Int, pxPerPt: Float = 1.4f): android.graphics.Bitmap {
        val plan = planFor(source, settings)
        val bmp = android.graphics.Bitmap.createBitmap(
            (plan.pageWidthPt * pxPerPt).roundToInt(), (plan.pageHeightPt * pxPerPt).roundToInt(), android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.scale(pxPerPt, pxPerPt)
        pageRenderer(source).drawPage(canvas, plan, settings, source.definition, source.rows, pageIndex.coerceIn(0, (plan.pageCount - 1).coerceAtLeast(0)))
        return bmp
    }

    /** خطة التخطيط للمعاينة/التصدير؛ تُرمى LayoutException (قابلة للعرض) عند إعدادات غير ممكنة. */
    @Throws(LayoutException::class)
    fun planFor(source: ExportSource, settings: OutputSettings): LayoutPlanner.LayoutPlan =
        LayoutPlanner.plan(CanvasSize(source.definition.canvas.width, source.definition.canvas.height), source.definition.constraints, settings, source.rows.size)

    /** ينتج الملف؛ التقدم 0..100، ويُلغى بإلغاء الكوروتين. */
    suspend fun export(
        source: ExportSource,
        format: ExportFormat,
        settings: OutputSettings,
        batchNumber: String,
        txtHeader: String,
        txtColumns: String,
        onProgress: (Int) -> Unit
    ): AppResult<File> = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            purgeOld(dir)
            val file = File(dir, fileName(batchNumber, format))

            when (format) {
                ExportFormat.TXT -> {
                    val text = VoucherTxtFormatter.format(
                        txtHeader, txtColumns,
                        source.rows.map { VoucherTxtFormatter.Row(it.username.orEmpty(), it.password.orEmpty()) }
                    )
                    file.writeText(text, Charsets.UTF_8)
                    onProgress(100)
                }
                ExportFormat.PDF -> writePdf(source, settings, file, onProgress)
            }
            AppResult.Success(file)
        } catch (e: LayoutException) {
            AppResult.Error(ApiError(e.code, e.message ?: e.code))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Error(ApiError(ERR_EXPORT_FAILED, e.localizedMessage ?: "Export failed"))
        }
    }

    private suspend fun writePdf(source: ExportSource, settings: OutputSettings, file: File, onProgress: (Int) -> Unit) {
        val plan = planFor(source, settings)
        val pages = pageRenderer(source)
        val doc = PdfDocument()
        try {
            for (page in 0 until plan.pageCount) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val info = PdfDocument.PageInfo.Builder(plan.pageWidthPt.roundToInt(), plan.pageHeightPt.roundToInt(), page + 1).create()
                val p = doc.startPage(info)
                pages.drawPage(p.canvas, plan, settings, source.definition, source.rows, page)
                doc.finishPage(p)
                onProgress(((page + 1) * 100) / plan.pageCount)
            }
            file.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    private fun pageRenderer(source: ExportSource): VoucherPageRenderer {
        val resolver = VoucherResolver(Currency(source.currencySymbol, "", 2, CurrencyPosition.SUFFIX))
        return VoucherPageRenderer(VoucherCanvasRenderer(context, resolver))
    }

    private fun Voucher.toRow(seq: Int, total: Int, profileDisplayAr: String, loginUrl: String) = VoucherRow(
        username = code,
        name = code,
        password = password,
        profile = profileName,
        profile_ar = profileDisplayAr.ifBlank { profileName },
        price = priceMinor / 100.0,
        batch_sequence = seq,
        batch_total_users = total,
        batch_id = batchId,
        qr_content = VoucherQr.content(loginUrl, code, password)
    )

    private fun fileName(batchNumber: String, format: ExportFormat): String {
        val safe = batchNumber.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "batch" }
        val date = SimpleDateFormat("yyyyMMdd", Locale.ENGLISH).format(Date())
        return "batch-$safe-$date.${format.extension}"
    }

    /** ملفات التصدير المؤقتة تحوي كلمات مرور: تُحذف ما تجاوز يوماً. */
    private fun purgeOld(dir: File) {
        val limit = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        dir.listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
    }

    companion object {
        const val ERR_NO_VOUCHERS = "ERR_EXPORT_NO_VOUCHERS"
        const val ERR_TEMPLATE_UNAVAILABLE = "ERR_EXPORT_TEMPLATE_UNAVAILABLE"
        const val ERR_EXPORT_FAILED = "ERR_EXPORT_FAILED"
    }
}

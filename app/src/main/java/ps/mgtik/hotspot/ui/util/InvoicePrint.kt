package ps.mgtik.hotspot.ui.util

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** الطباعة عبر نظام الطباعة في Android (نافذته نفسها تعرض المعاينة). يرجع false إن لم تتوفر الخدمة. */
object InvoicePrint {
    fun print(context: Context, file: File, jobName: String): Boolean {
        val pm = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return false
        pm.print(jobName, FilePrintAdapter(file, jobName), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
        return true
    }

    private class FilePrintAdapter(private val file: File, private val name: String) : PrintDocumentAdapter() {
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, signal: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
            if (signal?.isCanceled == true) { cb.onLayoutCancelled(); return }
            cb.onLayoutFinished(
                PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(),
                old != new
            )
        }

        override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, signal: CancellationSignal?, cb: WriteResultCallback) {
            try {
                FileInputStream(file).use { input -> FileOutputStream(dest.fileDescriptor).use { out -> input.copyTo(out) } }
                cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                cb.onWriteFailed(e.localizedMessage)
            }
        }
    }
}

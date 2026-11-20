package ps.mgtik.hotspot.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** مشاركة وحفظ ملفات التصدير (cacheDir/exports) عبر FileProvider دون كشف المسارات. */
object ShareFiles {

    fun share(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** ينسخ الملف إلى Uri اختاره المستخدم (CreateDocument). @return نجاح النسخ */
    fun copyTo(context: Context, file: File, target: Uri): Boolean = try {
        context.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null
    } catch (e: Exception) {
        false
    }
}

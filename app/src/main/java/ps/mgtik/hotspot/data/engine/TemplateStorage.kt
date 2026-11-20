package ps.mgtik.hotspot.data.engine

import android.content.Context
import java.io.File

/** مواقع تخزين القوالب على الجهاز (مشتركة بين المزامنة والتصدير). */
object TemplateStorage {
    fun assetsDir(context: Context): File = File(context.filesDir, "template_assets").apply { mkdirs() }

    private fun definitionsDir(context: Context): File = File(context.filesDir, "template_definitions").apply { mkdirs() }

    /** تعريف القالب JSON بعد التحقق من checksum؛ الاسم يتضمن الإصدار فلا يُقرأ تعريف قديم لإصدار جديد. */
    fun definitionFile(context: Context, templateId: String, version: Int): File =
        File(definitionsDir(context), "${templateId}_v$version.json")
}

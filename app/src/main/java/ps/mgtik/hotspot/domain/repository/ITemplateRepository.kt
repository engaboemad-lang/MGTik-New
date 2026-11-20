package ps.mgtik.hotspot.domain.repository

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.*

/**
 * ITemplateRepository - عقد نظام مزامنة وطباعة القوالب الموحد (§7)
 */
interface ITemplateRepository {
    
    // --- عمليات الـ Runtime والمزامنة ---
    fun getLocalTemplates(): Flow<List<RuntimeTemplate>>

    suspend fun syncIncremental(): AppResult<Unit>
    suspend fun syncFull(): AppResult<Unit>
    suspend fun fetchPackage(templateId: String, version: Int): AppResult<TemplatePackage>

    // --- عمليات المؤلف (Authoring) ---
    suspend fun uploadAsset(assetId: String, bytes: ByteArray, mime: String): AppResult<Unit>
    suspend fun createTemplate(title: String, definition: TemplateDefinition): AppResult<RuntimeTemplate>
    suspend fun publishTemplate(templateId: String, version: Int): AppResult<Unit>
    
    /**
     * تعريف القالب للرسم: من الملف المحلي (بعد المزامنة)، وإلا من السحابة ثم يُحفظ. بلا اتصال ولا ملف ⇒ خطأ
     * (المستدعي يعود للقالب المضمَّن DefaultVoucherTemplate).
     */
    suspend fun loadDefinition(template: RuntimeTemplate): AppResult<TemplateDefinition>

    /** القالب الافتراضي المختار (null = المضمَّن). */
    fun getDefaultTemplateId(): Flow<String?>
    suspend fun setDefaultTemplate(templateId: String?)

    /**
     * رفع القالب المدمج محلياً للاختبار (Dev Tool Helper)
     */
    suspend fun uploadBundledTemplate(assetPath: String, title: String): AppResult<Unit>

    // --- إعدادات الإخراج والربط ---
    fun getOutputSettings(): Flow<OutputSettings>
    suspend fun saveOutputSettings(settings: OutputSettings): AppResult<Unit>
}

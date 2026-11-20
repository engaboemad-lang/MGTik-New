package ps.mgtik.hotspot.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.data.cloud.source.CloudTemplateDataSource
import ps.mgtik.hotspot.data.engine.SyncEngine
import ps.mgtik.hotspot.data.local.dao.TemplateDao
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.data.engine.TemplateStorage
import ps.mgtik.hotspot.data.local.entity.toDomain
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.repository.ITemplateRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TemplateRepositoryImpl - تطبيق مستودع القوالب الموحد (§7).
 */
@Singleton
class TemplateRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudDataSource: CloudTemplateDataSource,
    private val templateDao: TemplateDao,
    private val syncEngine: SyncEngine,
    private val appPreferences: ps.mgtik.hotspot.data.local.prefs.AppPreferences
) : ITemplateRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override fun getLocalTemplates(): Flow<List<RuntimeTemplate>> =
        templateDao.getAllTemplates().map { entities -> entities.map { it.toDomain() } }

    override suspend fun syncIncremental(): AppResult<Unit> = syncEngine.performFullSync()

    override suspend fun syncFull(): AppResult<Unit> = syncEngine.performFullSync()

    override suspend fun fetchPackage(templateId: String, version: Int): AppResult<TemplatePackage> =
        cloudDataSource.getTemplatePackage(templateId, version)

    override suspend fun uploadAsset(assetId: String, bytes: ByteArray, mime: String): AppResult<Unit> =
        cloudDataSource.uploadAsset(assetId, bytes, mime)

    override suspend fun createTemplate(title: String, definition: TemplateDefinition): AppResult<RuntimeTemplate> =
        cloudDataSource.createTemplate(title, definition)

    override suspend fun publishTemplate(templateId: String, version: Int): AppResult<Unit> =
        cloudDataSource.publishTemplate(templateId, version)

    override suspend fun loadDefinition(template: RuntimeTemplate): AppResult<TemplateDefinition> {
        val file = TemplateStorage.definitionFile(context, template.templateId, template.version)
        try {
            if (file.exists()) return AppResult.Success(json.decodeFromString(file.readText(Charsets.UTF_8)))
        } catch (e: Exception) {
            file.delete() // ملف تالف: يُعاد جلبه من السحابة
        }
        return when (val pkg = fetchPackage(template.templateId, template.version)) {
            is AppResult.Success -> try {
                val def = json.decodeFromString<TemplateDefinition>(pkg.data.definitionJson)
                file.writeText(pkg.data.definitionJson, Charsets.UTF_8)
                AppResult.Success(def)
            } catch (e: Exception) {
                AppResult.Error(ApiError.unknownError("Invalid template definition: ${e.localizedMessage}"))
            }
            is AppResult.Error -> AppResult.Error(pkg.error)
            else -> AppResult.Error(ApiError.unknownError("Template unavailable"))
        }
    }

    // القالب الافتراضي وإعدادات الطباعة تخزَّن في AppPreferences فقط (لا نسخة ثانية هنا)
    override fun getDefaultTemplateId(): Flow<String?> = appPreferences.defaultTemplateId

    override suspend fun setDefaultTemplate(templateId: String?) = appPreferences.setDefaultTemplateId(templateId)

    override suspend fun uploadBundledTemplate(assetPath: String, title: String): AppResult<Unit> {
        return try {
            val definitionStr = context.assets.open("$assetPath/definition.json").bufferedReader().use { it.readText() }
            val definition = json.decodeFromString<TemplateDefinition>(definitionStr)
            
            val createResult = createTemplate(title, definition)
            if (createResult is AppResult.Error) return AppResult.Error(createResult.error)
            
            val templateId = (createResult as AppResult.Success).data.templateId
            publishTemplate(templateId, 1)
        } catch (e: Exception) {
            AppResult.Error(ps.mgtik.hotspot.core.result.ApiError.unknownError("فشل الرفع: ${e.localizedMessage}"))
        }
    }

    override fun getOutputSettings(): Flow<OutputSettings> = appPreferences.outputSettings

    override suspend fun saveOutputSettings(settings: OutputSettings): AppResult<Unit> =
        appPreferences.saveOutputSettings(settings)
}

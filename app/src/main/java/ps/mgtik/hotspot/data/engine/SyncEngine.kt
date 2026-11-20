package ps.mgtik.hotspot.data.engine

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.util.HashUtils
import ps.mgtik.hotspot.data.cloud.source.CloudTemplateDataSource
import ps.mgtik.hotspot.data.local.dao.TemplateDao
import ps.mgtik.hotspot.data.local.entity.toDomain
import ps.mgtik.hotspot.data.local.entity.toEntity
import ps.mgtik.hotspot.domain.model.RuntimeTemplate
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SyncEngine - المحرك المسؤول عن تطبيق القسم 8 من مواصفات المزامنة.
 */
@Singleton
class SyncEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudDataSource: CloudTemplateDataSource,
    private val templateDao: TemplateDao
) {
    private val assetsDir = TemplateStorage.assetsDir(context)

    private companion object {
        const val ERR_TEMPLATE_SYNC_INCOMPLETE = "ERR_TEMPLATE_SYNC_INCOMPLETE"
    }

    suspend fun performFullSync(): AppResult<Unit> {
        return when (val result = cloudDataSource.getPublishedTemplatesSnapshot()) {
            is AppResult.Success -> {
                val remoteTemplates = result.data
                val seenIds = remoteTemplates.map { it.templateId }

                // فشل قالب واحد لا يوقف بقية القوالب، لكنه لا يُخفى: المزامنة لا تُعدّ ناجحة وهناك قالب لم يُنزَّل
                val failedIds = remoteTemplates.filterNot { applyUpsert(it) }.map { it.templateId }

                val localIds = templateDao.getAllIds()
                localIds.filter { it !in seenIds }.forEach { templateDao.deleteTemplate(it) }

                if (failedIds.isEmpty()) AppResult.Success(Unit)
                else AppResult.Error(
                    ApiError(
                        ERR_TEMPLATE_SYNC_INCOMPLETE,
                        "Template sync incomplete: ${failedIds.size} of ${remoteTemplates.size} failed (${failedIds.take(5).joinToString()})",
                        retryable = true
                    )
                )
            }
            is AppResult.Error -> AppResult.Error(result.error)
            else -> AppResult.Loading 
        }
    }

    /** @return true إن صار القالب محلياً بآخر نسخة (أو لم يحتج تحديثاً)؛ false إن تعذّر تنزيل حزمته/أصوله أو فشل التحقق. */
    private suspend fun applyUpsert(remote: RuntimeTemplate): Boolean {
        val local = templateDao.getTemplateById(remote.templateId).first()?.toDomain()
        
        val needsPackage = local == null || local.version != remote.version || local.checksum != remote.checksum
        
        if (needsPackage) {
            val pkg = when (val pkgResult = cloudDataSource.getTemplatePackage(remote.templateId, remote.version)) {
                is AppResult.Success -> pkgResult.data
                else -> return false
            }
            if (!verifyChecksum(pkg.definitionJson, pkg.checksum)) return false

            var allAssetsReady = true
            pkg.assets.forEach { assetRef ->
                if (!downloadAssetIfNeeded(assetRef.assetId)) {
                    allAssetsReady = false
                }
            }
            if (!allAssetsReady) return false

            // التعريف يُحفظ محلياً (كان يُتحقَّق منه ثم يُرمى) ليعمل التصدير دون اتصال
            writeAtomically(TemplateStorage.definitionFile(context, remote.templateId, remote.version), pkg.definitionJson.toByteArray(Charsets.UTF_8))
            templateDao.insertTemplate(remote.toEntity())
        } else if (local!!.updatedAt != remote.updatedAt) {
            templateDao.insertTemplate(remote.toEntity())
        }
        return true
    }

    private suspend fun downloadAssetIfNeeded(assetId: String): Boolean {
        val file = File(assetsDir, assetId)
        // الملف الموجود يُقبل فقط إن كان سليماً: اسمه هو SHA-256 لمحتواه، فنسخة تالفة (قتل العملية أثناء الكتابة) لا تُعتمد للأبد
        if (file.exists()) {
            if (HashUtils.calculateSha256(file.readBytes()) == assetId) return true
            file.delete()
        }
        
        return when (val result = cloudDataSource.downloadAsset(assetId)) {
            is AppResult.Success -> {
                if (HashUtils.calculateSha256(result.data) == assetId) {
                    writeAtomically(file, result.data)
                    true
                } else false
            }
            else -> false
        }
    }

    /** كتابة إلى ملف مؤقت ثم إعادة تسمية: انقطاع في المنتصف لا يترك ملفاً ناقصاً بالاسم النهائي. */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("Could not move ${tmp.name} into place")
        }
    }

    private fun verifyChecksum(json: String, expected: String): Boolean {
        return HashUtils.calculateSha256(json) == expected.removePrefix("sha256:")
    }
}

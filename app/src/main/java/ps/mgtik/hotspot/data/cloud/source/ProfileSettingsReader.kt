package ps.mgtik.hotspot.data.cloud.source

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import ps.mgtik.hotspot.data.mapping.DocFields
import ps.mgtik.hotspot.data.mapping.FirestoreDocFields
import ps.mgtik.hotspot.data.mapping.ProfileDocMapper
import ps.mgtik.hotspot.domain.usecase.OperationalSettingsResolver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ProfileSettingsReader - يقرأ البروفايل وتصنيفه من السحابة (المصدر المعتمد) ويحسم إعدادات المستخدم عبر
 * [OperationalSettingsResolver]. هو نقطة القراءة الوحيدة للإنشاء الفردي والتوليد الجماعي، فلا تتباين النتائج.
 * التحويل من الوثيقة إلى النموذج يمرّ عبر [ProfileDocMapper] (المحوّل الوحيد نفسه الذي تستعمله شاشات الباقات)،
 * فكل حقل تشغيلي (سرعة/جلسات/حدود/صلاحية/سياسة بداية/غير محدود) يصل إلى الحسم كما هو مخزَّن.
 * أخطاء القراءة (شبكة/صلاحيات) تُرمى كما هي ليصنّفها المستدعي؛ الحالات المنطقية تعود في [Result].
 */
@Singleton
class ProfileSettingsReader @Inject constructor(firestore: FirebaseFirestore) {
    private val profiles = firestore.collection("profiles")
    private val classifications = firestore.collection("profile_classifications")

    sealed interface Result {
        data class Ok(val settings: ps.mgtik.hotspot.domain.usecase.ResolvedUserSettings) : Result
        data class Invalid(val code: String, val detail: String) : Result
    }

    suspend fun read(profileId: String): Result {
        val profileDoc = profiles.document(profileId).get().await()
        if (!profileDoc.exists()) return Result.Invalid("ERR_USER_CREATE_PROFILE_REQUIRED", "Profile not found")
        return resolve(profileDoc)
    }

    /** للمستدعي الذي قرأ وثيقة البروفايل بنفسه (الساغا). */
    suspend fun resolve(profileDoc: DocumentSnapshot): Result {
        val profileFields = FirestoreDocFields(profileDoc)
        val classificationFields = profileFields.string("classification_key")?.takeIf { it.isNotBlank() }?.let { key ->
            classifications.document(key).get().await().takeIf { it.exists() }?.let { FirestoreDocFields(it) }
        }
        return resolveFields(profileFields, classificationFields)
    }

    companion object {
        /**
         * الحسم النقي من حقول الوثيقتين (بلا Firestore): هو ما يستدعيه [resolve]، وما تختبره الوحدات.
         * [classification] = null يعني «لم يُعثر عليه» (بروفايل مرتبط بتصنيف غائب ⇒ خطأ صريح).
         */
        fun resolveFields(profile: DocFields, classification: DocFields?): Result {
            val p = ProfileDocMapper.profile(profile, ProfileDocMapper.statusOrActive(profile))
            val c = classification?.let { ProfileDocMapper.classification(it) }
            return when (val o = OperationalSettingsResolver.resolve(p, c)) {
                is OperationalSettingsResolver.Outcome.Ok -> Result.Ok(o.settings)
                is OperationalSettingsResolver.Outcome.Invalid -> Result.Invalid(o.code, o.detail)
            }
        }
    }
}

package ps.mgtik.hotspot.data.mapping

import com.google.firebase.firestore.DocumentSnapshot
import ps.mgtik.hotspot.core.util.toSortableIso
import ps.mgtik.hotspot.domain.model.CategoryStatus
import ps.mgtik.hotspot.domain.model.OperationalSetting
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.ProfileClassification
import ps.mgtik.hotspot.domain.model.ProfileStatus

/**
 * واجهة قراءة حقول وثيقة (Firestore أو بديل اختباري). تُستعمل لتوحيد التحويل في مكان واحد وجعله قابلاً للاختبار
 * دون محاكي: [FirestoreDocFields] للإنتاج، وأي خريطة في الاختبارات.
 */
interface DocFields {
    val id: String
    fun string(key: String): String?
    fun long(key: String): Long?
    fun bool(key: String): Boolean?
    fun list(key: String): List<*>?
    /** وقت الحقل بصيغة قابلة للفرز، أو "" إن غاب. */
    fun isoTime(key: String): String
}

class FirestoreDocFields(private val doc: DocumentSnapshot) : DocFields {
    override val id: String get() = doc.id
    override fun string(key: String): String? = doc.getString(key)
    override fun long(key: String): Long? = doc.getLong(key)
    override fun bool(key: String): Boolean? = doc.getBoolean(key)
    override fun list(key: String): List<*>? = doc.get(key) as? List<*>
    override fun isoTime(key: String): String = doc.getTimestamp(key)?.toDate()?.toSortableIso() ?: ""
}

/**
 * ProfileDocMapper - **المحوّل الوحيد** من وثيقة profiles / profile_classifications إلى النموذج.
 * تستعمله شاشات الباقات (CloudProfileDataSource) وقراءة الإعدادات التشغيلية للإنشاء والتوليد (ProfileSettingsReader)،
 * فلا يفقد مسار منهما حقلاً (الصلاحية، سياسة البداية، غير المحدود...) بينما يقرؤه الآخر.
 */
object ProfileDocMapper {

    /** [status] يحدده المستدعي: الشاشات ترفض الحالة المجهولة، والحسم التشغيلي لا يعتمد عليها. */
    fun profile(f: DocFields, status: ProfileStatus): Profile = Profile(
        id = f.id,
        linkedProfileName = f.string("linked_profile_name") ?: "",
        displayNameEn = f.string("display_name_en") ?: "",
        displayNameAr = f.string("display_name_ar") ?: "",
        description = f.string("description"),
        categoryId = f.string("category_id"),
        classificationKey = f.string("classification_key"),
        status = status,
        createdAt = f.isoTime("created_at"),
        updatedAt = f.isoTime("updated_at"),
        version = f.long("version")?.toInt() ?: 1,
        rateLimitTxKbps = f.long("rate_limit_tx_kbps")?.toInt() ?: 0,
        rateLimitRxKbps = f.long("rate_limit_rx_kbps")?.toInt() ?: 0,
        priceMinor = f.long("price_minor") ?: 0L,
        validityHours = f.long("validity_hours")?.toInt() ?: 0,
        quotaMb = f.long("quota_mb")?.toInt() ?: 0,
        sharedUsers = f.long("shared_users")?.toInt() ?: 1,
        // غياب الحقل = غير مفروض (null)، لا صفر
        limitUptimeSeconds = f.long("limit_uptime_seconds"),
        limitTransferBytes = f.long("limit_transfer_bytes"),
        // غياب الحقل = بروفايل قديم (كل إعداداته خاصة به)؛ قائمة فارغة = يرث كل ما يعرّفه التصنيف
        ownSettings = f.list("own_settings")?.mapNotNull { OperationalSetting.fromWire(it as? String) }?.toSet(),
        // غياب الحقل = السلوك السابق (أول مصادقة، محدودة)
        validityStart = f.string("validity_start")?.takeIf { it == "assigned" } ?: "first_auth",
        validityUnlimited = f.bool("validity_unlimited") ?: false,
        sharedUnlimited = f.bool("shared_unlimited") ?: false
    )

    /** حالة الباقة لمسار لا يهمه الحسم بها (مثل الحسم التشغيلي): المجهولة/الغائبة تُقرأ ACTIVE كالافتراضي القديم. */
    fun statusOrActive(f: DocFields): ProfileStatus = f.string("status").toEnumOrNull<ProfileStatus>() ?: ProfileStatus.ACTIVE

    /** التصنيفات القديمة (بلا الحقول التشغيلية) تُقرأ بقيم null = غير معرَّفة، فلا يرثها أحد. */
    fun classification(f: DocFields): ProfileClassification = ProfileClassification(
        key = f.id,
        nameAr = f.string("name_ar") ?: "",
        nameEn = f.string("name_en") ?: "",
        description = f.string("description"),
        status = f.string("status").toEnumOrNull<CategoryStatus>() ?: CategoryStatus.ACTIVE,
        downloadKbps = f.long("download_kbps")?.toInt(),
        uploadKbps = f.long("upload_kbps")?.toInt(),
        sharedUsers = f.long("shared_users")?.toInt(),
        limitUptimeSeconds = f.long("limit_uptime_seconds"),
        limitTransferBytes = f.long("limit_transfer_bytes")
    )
}

package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.domain.model.FieldChange
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.ProfileUsage

/**
 * قواعد نقية لسجل تعديلات الفئات التجارية (يكتبه Firestore داخل معاملة التعديل نفسها فلا يُسجَّل تغيير لم يحدث ولا يضيع تغيير حدث)
 * ولعدّ الارتباطات. لا تلمس أي سعر ولا إعداد MikroTik.
 */
object CategoryHistoryRules {
    /** الحقول التجارية المتتبَّعة على مستند الفئة. */
    val TRACKED_FIELDS = listOf("retail_price_minor", "wholesale_price_minor", "currency")

    /** قيمة موحدة نصياً للمقارنة والتخزين: الأرقام صحيحة بلا كسور، null يبقى null. */
    fun normalize(v: Any?): String? = when (v) {
        null -> null
        is Number -> v.toLong().toString()
        is Boolean -> v.toString()
        else -> v.toString().ifBlank { null }
    }

    /**
     * فروق الحقول المتتبَّعة بين الحالة السابقة ([old] = null للإنشاء) واللاحقة. الحقل الغائب من [new] لا يُعدّ تغييراً
     * (التعديل بالدمج لا يلمسه). ترتيب الناتج = ترتيب [TRACKED_FIELDS].
     */
    fun diff(old: Map<String, Any?>?, new: Map<String, Any?>): List<FieldChange> =
        TRACKED_FIELDS.mapNotNull { f ->
            if (!new.containsKey(f)) return@mapNotNull null
            val before = normalize(old?.get(f))
            val after = normalize(new[f])
            if (before == after) null else FieldChange(f, before, after)
        }

    fun toWire(changes: List<FieldChange>): List<Map<String, Any?>> =
        changes.map { mapOf("field" to it.field, "old" to it.oldValue, "new" to it.newValue) }

    fun fromWire(raw: Any?): List<FieldChange> =
        (raw as? List<*>)?.mapNotNull { m ->
            val map = m as? Map<*, *> ?: return@mapNotNull null
            val field = map["field"] as? String ?: return@mapNotNull null
            FieldChange(field, map["old"] as? String, map["new"] as? String)
        }.orEmpty()
}

/** تجميع أعداد الاستخدام على مستوى التصنيف أو الفئة من أعداد البروفايلات الفعلية. */
object ProfileUsageRules {
    /**
     * مجموع استخدام بروفايلات مجموعة. إن غاب عدّ أحدها (فشل قراءته) تكون النتيجة null كاملة:
     * لا يُعرض مجموع ناقص كأنه صحيح.
     */
    fun sum(profiles: Collection<Profile>, usage: Map<String, ProfileUsage>): ProfileUsage? {
        var users = 0; var subs = 0; var batches = 0
        for (p in profiles) {
            val u = usage[p.id] ?: return null
            users += u.normalUsers; subs += u.subscriptions; batches += u.batches
        }
        return ProfileUsage(users, subs, batches)
    }
}

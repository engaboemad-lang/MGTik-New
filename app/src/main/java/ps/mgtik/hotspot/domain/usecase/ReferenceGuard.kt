package ps.mgtik.hotspot.domain.usecase

/**
 * قرارات سلامة الارتباطات (نقية) تستعملها معاملات Firestore في CloudProfileDataSource/CloudGateway
 * وتعكسها قواعد Firestore (firestore/firestore.rules). المبدأ: لا استعلامات داخل معاملة Firestore، لذا
 * الارتباطات تُحفظ كعلامات على الوثيقة نفسها وتُقرأ/تُكتب ضمن المعاملة ⇒ التعارض المتزامن يُعاد/يُرفض.
 *  - profiles/{id}.in_use: يصبح true بنفس معاملة إنشاء أول دفعة/اشتراك، ولا يعود false.
 *  - profile_categories/{id}.profile_count: عدّاد يُعدَّل بنفس معاملة إنشاء/نقل/حذف الباقة.
 */
object ReferenceGuard {
    const val ERR_PROFILE_IN_USE = "ERR_PROFILE_IN_USE"
    const val ERR_CATEGORY_NOT_EMPTY = "ERR_CATEGORY_NOT_EMPTY"
    const val ERR_CATEGORY_INACTIVE = "ERR_CATEGORY_INACTIVE"
    const val ERR_INTEGRITY_NOT_READY = "ERR_INTEGRITY_NOT_READY"

    enum class Verdict { OK, BLOCKED, NOT_READY }

    /** inUse = قيمة العلامة؛ null = غير موجودة (بيانات قديمة لم تُعالَج بعد). */
    fun profileDeletable(inUse: Boolean?): Verdict = when (inUse) {
        null -> Verdict.NOT_READY
        true -> Verdict.BLOCKED
        false -> Verdict.OK
    }

    fun categoryDeletable(profileCount: Long?): Verdict = when {
        profileCount == null -> Verdict.NOT_READY
        profileCount > 0L -> Verdict.BLOCKED
        else -> Verdict.OK
    }

    /** الفئة تقبل إسناد باقات إليها فقط إن كانت موجودة ونشطة (غياب الحقل = نشطة). */
    fun categoryAssignable(exists: Boolean, statusRaw: String?): Boolean =
        exists && (statusRaw == null || statusRaw.equals("active", ignoreCase = true))
}

package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.domain.model.LocalUserRecord
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.ProfileCategory
import ps.mgtik.hotspot.domain.model.SystemClass

/**
 * قواعد التصنيف النظامي (نقية، تستعملها الطبقات كلها ولا تُكرَّر):
 *  • الفئة تحمل التصنيف (ثابت)، والباقة ترثه من فئتها ولا تخزّنه (فلا يمكن أن تخالف فئتها).
 *  • سجل المستخدم يحفظ لقطة ثابتة من التصنيف وقت إنشائه.
 */
object SystemClassRules {
    const val ERR_CATEGORY_CLASS_REQUIRED = "ERR_CATEGORY_CLASS_REQUIRED"
    const val ERR_CATEGORY_CLASS_IMMUTABLE = "ERR_CATEGORY_CLASS_IMMUTABLE"
    const val ERR_PROFILE_CATEGORY_REQUIRED = "ERR_PROFILE_CATEGORY_REQUIRED"
    const val ERR_CATEGORY_UNCLASSIFIED = "ERR_CATEGORY_UNCLASSIFIED"
    const val ERR_PROFILE_CLASS_MISMATCH = "ERR_PROFILE_CLASS_MISMATCH"
    const val ERR_PROFILE_MOVE_CLASS_MISMATCH = "ERR_PROFILE_MOVE_CLASS_MISMATCH"
    const val ERR_USER_SUBSCRIPTION_MANAGED = "ERR_USER_SUBSCRIPTION_MANAGED"

    /** تصنيف الباقة الموروث؛ null = بلا فئة أو فئتها قديمة غير مصنّفة (لا نخمّن). */
    fun classOf(profile: Profile, categories: List<ProfileCategory>): SystemClass? =
        profile.categoryId?.let { id -> categories.firstOrNull { it.id == id }?.systemClass }

    /** إنشاء باقة جديدة: يجب أن تتبع فئة موجودة ومصنّفة نظامياً. */
    fun checkCategoryForNewProfile(hasCategoryId: Boolean, category: ProfileCategory?): String? = when {
        !hasCategoryId -> ERR_PROFILE_CATEGORY_REQUIRED
        category?.systemClass == null -> ERR_CATEGORY_UNCLASSIFIED
        else -> null
    }

    /**
     * رمز الخطأ إن كانت الباقة لا تصلح لإنشاء مستخدم من النوع [required]، وإلا null.
     * [category] = فئة الباقة كما هي في السحابة الآن (null = غير موجودة).
     */
    fun checkProfileFor(required: SystemClass, hasCategoryId: Boolean, category: ProfileCategory?): String? =
        checkCategoryForNewProfile(hasCategoryId, category)
            ?: if (category?.systemClass != required) ERR_PROFILE_CLASS_MISMATCH else null

    /**
     * عمليات المستخدم العادي (تعطيل/أرشفة/تعديل/حذف/إعادة تصدير/مزامنة/تجديد باقة) ممنوعة على المشترك الشهري:
     * دورة حياته وراوتره يملكهما الاشتراك (S28). تُستعمل في Repository وفي Flow وعند فحص السجل السحابي (المصدر الحاكم).
     */
    fun normalOpsError(record: LocalUserRecord): String? =
        if (record.systemClass == SystemClass.SUBSCRIPTION) ERR_USER_SUBSCRIPTION_MANAGED else null

    /** نقل باقة بين فئتين: مسموح فقط إن لم يتغير التصنيف النظامي (الفئة غير المصنّفة لا تقيّد). */
    fun moveAllowed(from: SystemClass?, to: SystemClass?): Boolean = from == null || to == null || from == to

    /** تغيير تصنيف فئة محفوظ مرفوض؛ الفئة القديمة غير المصنّفة (stored = null) تُعيَّن مرة واحدة. null = مسموح. */
    fun checkClassImmutable(stored: SystemClass?, requested: SystemClass?): String? =
        if (stored != null && requested != null && requested != stored) ERR_CATEGORY_CLASS_IMMUTABLE else null
}

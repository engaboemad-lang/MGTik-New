package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.domain.model.PricingRule
import ps.mgtik.hotspot.domain.model.ProfileCategory
import ps.mgtik.hotspot.domain.model.SaleContext

/** سطر واحد في «دفتر أسعار الوكيل»: سعره الخاص مقابل سعر الفئة الأساسي للسياق نفسه. */
data class OwnerPriceRow(
    val ruleId: String,
    /** فارغ = سعر قديم على مستوى الباقة (لا فئة له). */
    val categoryId: String,
    val categoryName: String,
    val context: SaleContext,
    val specialMinor: Long,
    /** سعر الفئة الأساسي لهذا السياق؛ null = غير محدد أو سعر قديم بلا فئة. */
    val baseMinor: Long?,
    /** false = السعر الخاص معطّل فلا يُطبَّق على العمليات الجديدة. */
    val active: Boolean,
    val currency: String,
    /** هل يطبَّق فعلاً على العمليات الجديدة (نشط وصالح)؟ */
    val applied: Boolean,
    /** سعر قديم على مستوى الباقة. */
    val legacy: Boolean
) {
    /** الفرق عن الأساسي (سالب = خصم للوكيل)؛ null إن لم يُعرف الأساسي. */
    val deltaMinor: Long? get() = baseMinor?.let { specialMinor - it }
}

/** استثناءات الوكيل السعرية كما هي في السحابة؛ كل ما عداها يُباع بسعر الفئة الأساسي. */
data class OwnerPriceBook(val rows: List<OwnerPriceRow>, val activeCategoriesCount: Int) {
    val appliedCount: Int get() = rows.count { it.applied }
    val disabledCount: Int get() = rows.count { !it.active }
}

/** يبني [OwnerPriceBook] من الفئات وقواعد التسعير (نقي: بلا Android/Firebase). */
object OwnerPriceBookBuilder {
    fun build(ownerId: String, categories: List<ProfileCategory>, rules: List<PricingRule>): OwnerPriceBook {
        val byId = categories.associateBy { it.id }
        val rows = rules
            .filter { it.ownerId == ownerId }
            .map { r ->
                val cat = byId[r.categoryId]
                val base = cat?.let { if (r.context == SaleContext.WHOLESALE) it.wholesalePriceMinor else it.retailPriceMinor }
                OwnerPriceRow(
                    ruleId = r.id,
                    categoryId = r.categoryId,
                    categoryName = cat?.nameAr?.ifBlank { cat.nameEn } ?: "",
                    context = r.context,
                    specialMinor = r.retailPriceMinor,
                    baseMinor = base,
                    active = r.active,
                    currency = r.currency.ifBlank { cat?.currency.orEmpty() },
                    applied = r.active && PricingPolicy.isPriceValid(r.retailPriceMinor),
                    legacy = r.categoryId.isBlank()
                )
            }
            .sortedWith(compareBy<OwnerPriceRow> { it.legacy }.thenBy { it.categoryName.lowercase() }.thenBy { it.context.ordinal })
        return OwnerPriceBook(rows, activeCategoriesCount = categories.count { it.status == ps.mgtik.hotspot.domain.model.CategoryStatus.ACTIVE })
    }
}

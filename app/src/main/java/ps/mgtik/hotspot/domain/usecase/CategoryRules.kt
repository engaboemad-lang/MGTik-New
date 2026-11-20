package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.domain.model.CategoryStatus
import ps.mgtik.hotspot.domain.model.ProfileCategory
import ps.mgtik.hotspot.domain.model.SystemClass

/**
 * قواعد فئة الباقة التجارية (نقية). النوع النظامي ([SystemClassRules]) والعملة ثابتان بعد الإنشاء؛
 * الأسعار تتعدّل بحرية لكنها لا تمسّ أي سجل سابق (اللقطات التاريخية في الدفعات/الاشتراكات).
 */
object CategoryRules {
    const val ERR_CURRENCY_REQUIRED = "ERR_CATEGORY_CURRENCY_REQUIRED"
    const val ERR_CURRENCY_IMMUTABLE = "ERR_CATEGORY_CURRENCY_IMMUTABLE"
    const val ERR_PRICE_INVALID = "ERR_CATEGORY_PRICE_INVALID"
    const val ERR_ARCHIVED_READONLY = "ERR_CATEGORY_ARCHIVED"

    /** حقل ← رمز الخطأ. الحقول: name, systemClass, currency, retailPrice, wholesalePrice. */
    fun validate(c: ProfileCategory, isNew: Boolean): Map<String, String> = buildMap {
        if (c.nameAr.isBlank()) put("name", "ERR_CATEGORY_NAME_REQUIRED")
        if (isNew && c.systemClass == null) put("systemClass", SystemClassRules.ERR_CATEGORY_CLASS_REQUIRED)
        if (isNew && c.currency.isNullOrBlank()) put("currency", ERR_CURRENCY_REQUIRED)
        c.retailPriceMinor?.let { if (!PricingPolicy.isPriceValid(it)) put("retailPrice", ERR_PRICE_INVALID) }
        c.wholesalePriceMinor?.let { if (!PricingPolicy.isPriceValid(it)) put("wholesalePrice", ERR_PRICE_INVALID) }
    }

    /** العملة ثابتة بعد تعيينها (تغييرها يعيد تقييم أسعار قائمة بصمت)؛ الفئة القديمة بلا عملة تُعيَّن مرة واحدة. */
    fun checkCurrencyImmutable(stored: String?, requested: String?): String? =
        if (!stored.isNullOrBlank() && !requested.isNullOrBlank() && stored != requested) ERR_CURRENCY_IMMUTABLE else null

    /** الفئة المؤرشفة لا تُسند إليها بروفايلات ولا تُنشأ لها أسعار خاصة جديدة. */
    fun checkAcceptsNewLinks(c: ProfileCategory?): String? = when {
        c == null -> ReferenceGuard.ERR_CATEGORY_INACTIVE
        c.status != CategoryStatus.ACTIVE -> ReferenceGuard.ERR_CATEGORY_INACTIVE
        else -> null
    }

    /** هل سعر السياق الحالي معرّف؟ (للمؤشرات في الواجهة؛ لا يُستعمل لاختيار السعر). */
    fun hasCommercialPrices(c: ProfileCategory): Boolean = c.retailPriceMinor != null || c.wholesalePriceMinor != null

    /** توافق نوع النظام بين فئة وعملية؛ null = متوافق. */
    fun checkSystemTypeCompatible(required: SystemClass, category: ProfileCategory?): String? =
        SystemClassRules.checkProfileFor(required, hasCategoryId = category != null, category = category)

    /**
     * للعرض فقط: سعر التجزئة الحالي للباقة من فئتها (0 = غير مسعّرة). الفئة بلا أسعار تجارية تبقى على سعر الباقة القديم.
     * السعر الفعلي للاشتراك يُحسب ويُثبَّت عند الإنشاء في CreateSubscriptionUseCase.
     */
    fun withRetailDisplay(p: ps.mgtik.hotspot.domain.model.Profile, categories: List<ProfileCategory>): ps.mgtik.hotspot.domain.model.Profile {
        val c = categories.firstOrNull { it.id == p.categoryId } ?: return p
        return if (hasCommercialPrices(c)) p.copy(priceMinor = c.retailPriceMinor ?: 0L) else p
    }
}

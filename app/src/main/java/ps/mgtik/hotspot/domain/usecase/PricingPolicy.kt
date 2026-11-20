package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.domain.model.PricingRule
import ps.mgtik.hotspot.domain.model.ProfileCategory
import ps.mgtik.hotspot.domain.model.SaleContext

/**
 * PricingPolicy - المصدر الوحيد لقاعدة اختيار السعر (نقي بلا Android/Firebase).
 * يستدعيه محرك التوليد (CloudGateway) وشاشة التوليد (التقدير) والمستودع، فلا تختلف النتيجة بين الشاشات.
 *
 * الترتيب: (1) سعر خاص نشط وصالح للوكيل نفسه ← (2) السعر الأساسي للباقة ← (3) لا سعر صالح = منع العملية.
 * سياسة الصفر الصريحة: لا سعر مجاني؛ الصفر والسالب غير صالحين ([ALLOW_ZERO_PRICE] = false).
 */
object PricingPolicy {

    const val ALLOW_ZERO_PRICE = false
    const val ERR_PRICE_UNAVAILABLE = "ERR_PRICE_UNAVAILABLE"
    const val ERR_PRICE_INVALID = "ERR_PRICE_INVALID"

    enum class Source { SPECIAL, BASE }

    data class ResolvedPrice(val unitMinor: Long, val source: Source, val ruleId: String?)

    fun isPriceValid(minor: Long): Boolean = minor > 0L || (ALLOW_ZERO_PRICE && minor == 0L)

    /**
     * @param baseMinor السعر الأساسي (Profile.priceMinor)
     * @param ownerId الوكيل في سياق العملية (فارغ = بلا وكيل ⇒ الأساسي فقط)
     * @param rules قواعد البروفايل؛ التي تخص وكيلاً آخر أو المعطّلة أو غير الصالحة تُتجاهل
     * @return null إن لم يوجد سعر صالح (تُمنع العملية)
     */
    fun resolve(baseMinor: Long, ownerId: String?, rules: List<PricingRule>): ResolvedPrice? {
        if (!ownerId.isNullOrBlank()) {
            val special = rules
                .filter { it.ownerId == ownerId && it.active && isPriceValid(it.retailPriceMinor) }
                .maxByOrNull { it.effectiveFrom.toLongOrNull() ?: 0L }
            if (special != null) return ResolvedPrice(special.retailPriceMinor, Source.SPECIAL, special.id)
        }
        return if (isPriceValid(baseMinor)) ResolvedPrice(baseMinor, Source.BASE, null) else null
    }

    /** معرّف المستند الثابت للسعر الخاص: يمنع تكرار (وكيل، بروفايل) على مستوى البنية نفسها. */
    fun specialPriceDocId(profileId: String, ownerId: String): String = "${profileId}__$ownerId"

    /** معرّف المستند الثابت للسعر الخاص على مستوى الفئة: يمنع تكرار (فئة، وكيل، سياق). */
    fun categoryPriceDocId(categoryId: String, ownerId: String, context: SaleContext): String =
        "${categoryId}__${ownerId}__${context.wire}"

    /**
     * هل للباقة سعر صالح تُباع به؟ فئة لها أسعار تجارية ⇒ يكفي أن يكون أحدها صالحاً (السعر الناقص لسياق ما يمنع
     * ذلك السياق وقت العملية)؛ فئة بلا أسعار (قديمة) أو بلا فئة ⇒ سعر البروفايل القديم.
     */
    fun profilePriceReady(category: ProfileCategory?, legacyProfileMinor: Long): Boolean =
        if (category != null && (category.retailPriceMinor != null || category.wholesalePriceMinor != null)) {
            listOfNotNull(category.retailPriceMinor, category.wholesalePriceMinor).any { isPriceValid(it) }
        } else isPriceValid(legacyProfileMinor)

    /** توحيد اسم الفئة للمقارنة: قص + دمج المسافات + حالة أحرف موحّدة. */
    fun normalizeName(name: String): String =
        name.trim().replace(Regex("\\s+"), " ").lowercase()

    // ───────────── التسعير المركزي حسب الفئة والسياق ─────────────
    // كل مسار بيع/توليد/اشتراك يستدعي [resolveFor] ولا يختار سعره بنفسه. [resolve] أعلاه يبقى للمسار القديم (سعر البروفايل).

    const val ERR_PRICE_CURRENCY_MISMATCH = "ERR_PRICE_CURRENCY_MISMATCH"

    /** مصدر السعر النهائي (يُخزَّن في لقطة العملية). */
    enum class PriceSource { OWNER_SPECIAL, LEGACY_OWNER_SPECIAL, WHOLESALE, RETAIL, LEGACY_PROFILE }

    /** السعر النهائي مع كل ما يلزم لعرضه وتثبيته تاريخياً. */
    data class Quote(
        val unitMinor: Long,
        val currency: String,
        val source: PriceSource,
        val context: SaleContext,
        val ownerId: String?,
        val ruleId: String?,
        val categoryId: String?
    ) {
        /** لقطة السعر كما تُكتب في وثيقة الدفعة/الاشتراك (أسماء Firestore). وقت الاعتماد يضيفه الكاتب بطابع الخادم. */
        fun snapshotFields(profileId: String): Map<String, Any?> = linkedMapOf(
            "price_snapshot_minor" to unitMinor,
            "price_currency" to currency,
            "price_source" to source.name.lowercase(),
            "pricing_type" to context.wire,
            "price_category_id" to categoryId,
            "price_profile_id" to profileId,
            "price_owner_id" to ownerId,
            "price_rule_id" to ruleId
        )
    }

    private fun currencyCompatible(ruleCurrency: String, categoryCurrency: String): Boolean =
        ruleCurrency.isBlank() || categoryCurrency.isBlank() || ruleCurrency == categoryCurrency

    /** عند التساوي في الصلاحية: الأحدث ثم المعرّف (نتيجة حتمية عند تعارض القواعد، لا تتغير بترتيب القراءة). */
    private fun List<PricingRule>.pickRule(): PricingRule? =
        sortedWith(compareByDescending<PricingRule> { it.effectiveFrom.toLongOrNull() ?: 0L }.thenBy { it.id }).firstOrNull()

    /**
     * ترتيب تحديد السعر:
     *  1. سعر خاص نشط وصالح لهذا المالك/الوكيل في **هذا السياق** وبعملة متوافقة (قاعدة فئة، ثم قاعدة بروفايل قديمة).
     *  2. سعر الفئة حسب السياق: جملة ← wholesalePriceMinor، تجزئة ← retailPriceMinor. غيابه = null (لا نسخ من سعر آخر).
     *  3. فئة بلا أي سعر تجاري (قديمة): سعر البروفايل القديم [legacyProfileMinor] إن كان صالحاً.
     *  4. غير ذلك null = تُمنع العملية.
     * @param category فئة الباقة كما هي الآن؛ null = بروفايل بلا فئة (يمر على المسار القديم فقط).
     * @param fallbackCurrency عملة الشركة، تُستعمل حين لا تحمل الفئة عملة.
     */
    fun resolveFor(
        context: SaleContext,
        ownerId: String?,
        category: ProfileCategory?,
        legacyProfileMinor: Long,
        rules: List<PricingRule>,
        fallbackCurrency: String
    ): Quote? {
        val currency = category?.currency?.takeIf { it.isNotBlank() } ?: fallbackCurrency
        val catId = category?.id?.takeIf { it.isNotBlank() }

        if (!ownerId.isNullOrBlank()) {
            fun usable(r: PricingRule) = r.ownerId == ownerId && r.active && r.context == context &&
                isPriceValid(r.retailPriceMinor) && currencyCompatible(r.currency, currency)
            val forCategory = catId?.let { id -> rules.filter { it.categoryId == id && usable(it) }.pickRule() }
            if (forCategory != null) {
                return Quote(forCategory.retailPriceMinor, currency, PriceSource.OWNER_SPECIAL, context, ownerId, forCategory.id, catId)
            }
            val legacy = rules.filter { it.categoryId.isBlank() && usable(it) }.pickRule()
            if (legacy != null) {
                return Quote(legacy.retailPriceMinor, currency, PriceSource.LEGACY_OWNER_SPECIAL, context, ownerId, legacy.id, catId)
            }
        }

        if (category != null && (category.retailPriceMinor != null || category.wholesalePriceMinor != null)) {
            val (price, source) = when (context) {
                SaleContext.WHOLESALE -> category.wholesalePriceMinor to PriceSource.WHOLESALE
                SaleContext.RETAIL -> category.retailPriceMinor to PriceSource.RETAIL
            }
            return if (price != null && isPriceValid(price)) Quote(price, currency, source, context, ownerId?.takeIf { it.isNotBlank() }, null, catId) else null
        }

        return if (isPriceValid(legacyProfileMinor)) {
            Quote(legacyProfileMinor, currency, PriceSource.LEGACY_PROFILE, context, ownerId?.takeIf { it.isNotBlank() }, null, catId)
        } else null
    }
}

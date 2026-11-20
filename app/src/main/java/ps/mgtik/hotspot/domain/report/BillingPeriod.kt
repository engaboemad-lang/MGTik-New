package ps.mgtik.hotspot.domain.report

import java.time.LocalDate

/** الفترات الجاهزة للوحة Billing. THIS_MONTH هي الافتراضية فتبدأ اللوحة من جديد مع أول يوم في كل شهر تلقائياً. */
enum class BillingPreset { TODAY, THIS_MONTH, LAST_MONTH, LAST_30_DAYS, ALL_TIME }

/**
 * فترة لوحة Billing. تُخزَّن كاختيار (preset) لا كتواريخ، وتُحسب حدودها من «اليوم» عند كل تحديث:
 * لذلك «هذا الشهر» تنتقل وحدها إلى الشهر الجديد دون أي إجراء من المستخدم.
 * preset == null ⇒ نطاق مخصص [custom] (yyyy-MM-dd شاملاً الطرفين). النتيجة null = كل الفترات.
 */
data class BillingPeriod(
    val preset: BillingPreset? = BillingPreset.THIS_MONTH,
    val custom: Pair<String, String>? = null
) {
    fun range(today: LocalDate): Pair<String, String>? = when (preset) {
        null -> custom
        BillingPreset.TODAY -> today to today
        BillingPreset.THIS_MONTH -> today.withDayOfMonth(1) to today
        BillingPreset.LAST_MONTH -> {
            val first = today.withDayOfMonth(1).minusMonths(1)
            first to first.withDayOfMonth(first.lengthOfMonth())
        }
        BillingPreset.LAST_30_DAYS -> today.minusDays(29) to today
        BillingPreset.ALL_TIME -> null
    }?.let { (s, e) -> s.toString() to e.toString() }
}

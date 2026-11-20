package ps.mgtik.hotspot.domain.report

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** الفترات الجاهزة في محدد تاريخ التقارير. */
enum class ReportPreset { LAST_7_DAYS, LAST_30_DAYS, THIS_MONTH, LAST_MONTH }

/**
 * منطق الفترات والمقارنة (نقي بلا Android، قابل للاختبار). كل التواريخ yyyy-MM-dd محلية شاملة الطرفين،
 * وتحويلها إلى حدود UTC يتم في [ps.mgtik.hotspot.core.util.isWithinDateRange] وحده.
 */
object ReportPeriods {

    fun preset(preset: ReportPreset, today: LocalDate): Pair<String, String> = when (preset) {
        ReportPreset.LAST_7_DAYS -> today.minusDays(6) to today
        ReportPreset.LAST_30_DAYS -> today.minusDays(29) to today
        ReportPreset.THIS_MONTH -> today.withDayOfMonth(1) to today
        ReportPreset.LAST_MONTH -> {
            val first = today.withDayOfMonth(1).minusMonths(1)
            first to first.withDayOfMonth(first.lengthOfMonth())
        }
    }.let { (s, e) -> s.toString() to e.toString() }

    /** الفترة السابقة المباشرة بالطول نفسه (عدد الأيام شاملاً)، أو null إذا كان التاريخان غير مقروءين أو معكوسين. */
    fun previousRange(start: String, end: String): Pair<String, String>? = try {
        val s = LocalDate.parse(start)
        val e = LocalDate.parse(end)
        if (e.isBefore(s)) null else {
            val days = ChronoUnit.DAYS.between(s, e) + 1
            s.minusDays(days).toString() to s.minusDays(1).toString()
        }
    } catch (ex: java.time.format.DateTimeParseException) {
        null
    }

    /** نسبة التغيّر المئوية؛ null عند غياب أساس للمقارنة (الفترة السابقة صفر) فلا نخترع نسبة. */
    fun deltaPercent(current: Long, previous: Long): Int? =
        if (previous == 0L) null else ((current - previous) * 100.0 / previous).roundToInt()

    /** القيمة الرقمية لحقل تقرير (Int أو Long) أو null. */
    fun numeric(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        else -> null
    }
}

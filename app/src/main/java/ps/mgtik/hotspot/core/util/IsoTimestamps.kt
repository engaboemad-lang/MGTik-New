package ps.mgtik.hotspot.core.util

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Date

/** UTC بعرض ثابت (ملّي ثانية دائماً): الترتيب النصي يطابق الترتيب الزمني تماماً. */
private val SORTABLE_ISO: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/**
 * التنسيق الوحيد لتواريخ Firestore المخزَّنة نصاً في الـ domain وRoom (مثال 2026-10-08T09:21:00.000Z).
 * Instant.toString يحذف الكسر حين يكون صفراً فيكسر الترتيب النصي، وDate.toString يعتمد اليوم الأسبوعي؛
 * كلاهما مرفوض لأن created_at يُرتَّب به في استعلامات Room.
 */
fun Date.toSortableIso(): String = SORTABLE_ISO.format(toInstant())

/** الصيغة التي يطابقها هذا التنسيق، لاكتشاف الصفوف القديمة في الكاش (GLOB في SQLite). */
const val SORTABLE_ISO_GLOB = "[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]Z"

/**
 * حدّا مدى أيام محلية [startDate, endDate] (شاملين، yyyy-MM-dd بتوقيت [zone]) بصيغة [toSortableIso] (UTC):
 * بداية أول يوم (شاملة) ثم بداية اليوم التالي لآخر يوم (حصرية). هذه الدالة المصدر الوحيد لحدود الأيام في كل الشاشات
 * (Overview والتقارير والمخزون)، فيتطابق رقم اليوم الواحد أينما عُرض بغض النظر عن فرق التوقيت (+2/+3 في فلسطين).
 * تعيد null إذا كان أحد التاريخين غير مقروء.
 */
fun localDayBoundsIso(startDate: String, endDate: String, zone: ZoneId = ZoneId.systemDefault()): Pair<String, String>? = try {
    val start = LocalDate.parse(startDate).atStartOfDay(zone).toInstant()
    val endExclusive = LocalDate.parse(endDate).plusDays(1).atStartOfDay(zone).toInstant()
    Date.from(start).toSortableIso() to Date.from(endExclusive).toSortableIso()
} catch (e: DateTimeParseException) {
    null
}

/**
 * هل التاريخ النصي [iso] (بصيغة toSortableIso، UTC) ضمن مدى الأيام المحلية [startDate, endDate] شاملاً اليومين؟
 * التاريخان بصيغة yyyy-MM-dd بتوقيت [zone] (الافتراضي توقيت الجهاز). القيم الفارغة/غير المقروءة لا تُحتسب ضمن أي مدى.
 * المقارنة نصية لأن الصيغة المرتَّبة تطابق الترتيب الزمني؛ والحدّان محوَّلان إلى UTC عبر [localDayBoundsIso].
 */
fun isWithinDateRange(iso: String, startDate: String, endDate: String, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    if (iso.length < 10) return false
    val (start, endExclusive) = localDayBoundsIso(startDate, endDate, zone) ?: return false
    return iso >= start && iso < endExclusive
}

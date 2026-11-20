package ps.mgtik.hotspot.ui.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val DISPLAY_DATE_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/**
 * يعرض تاريخاً نصياً مخزَّناً بصيغة ISO-8601 (UTC) بتوقيت الجهاز بدل النص الخام "2026-11-02T06:18:00.000Z".
 * قيمة فارغة أو غير مقروءة تُعاد كما هي (لا نخمّن تاريخاً).
 */
fun formatIsoDateTime(iso: String): String {
    if (iso.isBlank()) return iso
    return try {
        DISPLAY_DATE_TIME.format(Instant.parse(iso))
    } catch (e: DateTimeParseException) {
        iso
    }
}

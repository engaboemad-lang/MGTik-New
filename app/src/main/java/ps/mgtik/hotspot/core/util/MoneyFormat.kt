package ps.mgtik.hotspot.core.util

/**
 * تنسيق مبلغ بالوحدة الصغرى (1/100) بأعداد صحيحة فقط: "12345" → "123.45".
 * كان التنسيق يمرّ بـ Double (minor / 100.0) وهو تمثيل ثنائي غير دقيق للمبالغ المالية.
 * أرقام لاتينية دائماً وبلا فاصل آلاف (كالسلوك السابق)، والسالب بعلامة ناقص قبل الرقم.
 */
fun formatMinor(amountMinor: Long): String {
    val negative = amountMinor < 0
    // abs(Long.MIN_VALUE) يفيض؛ لا مبلغ واقعي يقترب منه لكن نستعمل الحساب على الباقي لتفاديه
    val units = amountMinor / 100
    val cents = amountMinor % 100
    val unitsText = if (negative && units == 0L) "0" else kotlin.math.abs(units).toString()
    val centsText = kotlin.math.abs(cents).toString().padStart(2, '0')
    return "${if (negative) "-" else ""}$unitsText.$centsText"
}

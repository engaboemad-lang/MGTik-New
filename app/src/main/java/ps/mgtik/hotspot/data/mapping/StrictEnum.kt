package ps.mgtik.hotspot.data.mapping

/**
 * تحليل صارم لقيمة enum مخزَّنة نصاً (سحابة أو Room): غير حساس لحالة الأحرف،
 * ويعيد null للقيمة الناقصة أو غير المعروفة. لا قيمة افتراضية هنا عمداً:
 * تحويل قيمة مجهولة إلى حالة "صحيحة" (ACTIVE/DRAFT...) يخفي خطأ البيانات.
 * يقرّر المستدعي: رفض السجل وتسجيل SYNC_ERROR عبر [SyncErrorReporter]،
 * أو حالة آمنة صريحة عبر [toEnumOrFailSafe] حيث يسمح بذلك طبيعة الكيان.
 */
inline fun <reified T : Enum<T>> String?.toEnumOrNull(): T? {
    val v = this?.trim()
    if (v.isNullOrEmpty()) return null
    return enumValues<T>().firstOrNull { it.name.equals(v, ignoreCase = true) }
}

/**
 * مسموح فقط حين يكون [failSafe] حالة فشل/إيقاف لا تفتح صلاحية ولا تدّعي نجاحاً
 * (مثل FAILED لحالات التنفيذ). لا يُستخدم مع ACTIVE/DRAFT/PAID وأشباهها.
 */
inline fun <reified T : Enum<T>> String?.toEnumOrFailSafe(failSafe: T): T = toEnumOrNull<T>() ?: failSafe

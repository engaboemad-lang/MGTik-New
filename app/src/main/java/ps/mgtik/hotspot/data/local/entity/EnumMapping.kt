package ps.mgtik.hotspot.data.local.entity

/**
 * تحويل آمن لقيم enum المخزَّنة نصاً (غير حساس لحالة الأحرف) يُرجع null عند قيمة غير معروفة.
 * هذا هو المسار الافتراضي: المستدعي يتخطّى الصف (toDomainOrNull) بدل اختلاق حالة.
 */
internal inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
    enumValues<T>().firstOrNull { it.name.equals(this, ignoreCase = true) }

/**
 * تحويل مع قيمة افتراضية. يُسمح به **فقط** عندما تكون القيمة الافتراضية حالة فشل/غياب آمنة
 * (FAILED, MISSING, INFO…) أي لا تفتح صلاحية ولا تُظهر بيانات غير معروفة كأنها صحيحة.
 * ممنوع استخدامه مع ACTIVE / DRAFT / SYNCED / GENERATED وما يشبهها؛ استخدم [toEnumOrNull] هناك.
 */
internal inline fun <reified T : Enum<T>> String.toEnumOr(default: T): T =
    toEnumOrNull<T>() ?: default

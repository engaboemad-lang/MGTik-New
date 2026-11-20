package ps.mgtik.hotspot.domain.report

/**
 * تصدير نتيجة تقرير إلى CSV (نقي بلا Android، قابل للاختبار).
 * الأعمدة: القسم، البند، المؤشر، القيمة. القيم المفردة قسمها فارغ؛ قوائم التفاصيل (طرق الدفع، الوكلاء، الباقات)
 * يكون قسمها اسم القائمة وبندها اسم الصف. يبدأ الملف بعلامة UTF-8 BOM ليفتحه Excel بالعربية صحيحاً.
 */
object ReportCsv {
    private const val BOM = "\uFEFF"

    /** يهرّب الخلية: اقتباس عند الفاصلة/الاقتباس/السطر الجديد، ومنع تنفيذ الصيغ في Excel لنصوص يدخلها المستخدمون. */
    fun cell(raw: String, guardFormula: Boolean = false): String {
        var t = raw
        if (guardFormula && t.isNotEmpty() && t[0] in "=+-@\t\r") t = "'$t"
        return if (t.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + t.replace("\"", "\"\"") + "\"" else t
    }

    fun build(
        data: Map<String, Any?>,
        header: List<String>,
        keyLabel: (String) -> String,
        valueText: (String, Any?) -> String,
        rowLabel: (sectionKey: String, raw: String) -> String = { _, raw -> raw }
    ): String = buildString {
        append(BOM)
        appendLine(header.joinToString(",") { cell(it) })
        data.forEach { (key, value) ->
            if (value is List<*>) {
                val section = keyLabel(key)
                value.filterIsInstance<Map<*, *>>().forEach { row ->
                    val label = rowLabel(key, row["label"]?.toString().orEmpty())
                    row.entries.filter { it.key != "label" }.forEach { (k, v) ->
                        val kk = k.toString()
                        appendLine(listOf(cell(section), cell(label, guardFormula = true), cell(keyLabel(kk)), cell(valueText(kk, v))).joinToString(","))
                    }
                }
            } else {
                appendLine(listOf("", "", cell(keyLabel(key)), cell(valueText(key, value))).joinToString(","))
            }
        }
    }
}

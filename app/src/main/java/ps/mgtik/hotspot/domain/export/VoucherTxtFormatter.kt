package ps.mgtik.hotspot.domain.export

/**
 * VoucherTxtFormatter - صيغة TXT الثابتة لتصدير دفعة (UTF-8، فواصل أسطر \n):
 *
 *     <رأسية الدفعة>
 *     <عنوانا العمودين>
 *     username<TAB>password
 *     ...
 *
 * سطر لكل كرت وبترتيب الأرقام التسلسلية؛ القيم تُنظَّف من TAB وفواصل الأسطر كي لا تكسر الأعمدة عند الاستيراد.
 */
object VoucherTxtFormatter {

    data class Row(val username: String, val password: String)

    fun format(header: String, columns: String, rows: List<Row>): String {
        val sb = StringBuilder(rows.size * 24 + header.length + columns.length + 4)
        sb.append(clean(header)).append('\n')
        sb.append(columns).append('\n')
        rows.forEach { sb.append(clean(it.username)).append('\t').append(clean(it.password)).append('\n') }
        return sb.toString()
    }

    private fun clean(value: String): String = value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
}

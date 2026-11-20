package ps.mgtik.hotspot.domain.engine

import ps.mgtik.hotspot.domain.model.Currency
import ps.mgtik.hotspot.domain.model.CurrencyPosition
import ps.mgtik.hotspot.domain.model.VoucherRow
import java.util.Locale

/**
 * VoucherResolver - تنفيذ عقد ربط البيانات (§5).
 * تم نقله إلى موديول Domain لضمان إتاحة منطق حل البيانات للـ UI والـ Export بشكل شرعي.
 */
class VoucherResolver(private val currency: Currency) {

    fun resolve(token: String, row: VoucherRow): String {
        return when (token) {
            "username" -> row.username ?: row.name ?: ""
            "name" -> row.name ?: row.username ?: ""
            "password" -> row.password ?: ""
            "comment" -> row.comment ?: ""
            "profile" -> row.profile ?: ""
            "profile_ar" -> row.profile_ar ?: row.profile_en ?: row.profile ?: ""
            "profile_en" -> row.profile_en ?: row.profile_ar ?: row.profile ?: ""
            "price" -> resolvePriceRaw(row)
            "price_formatted" -> resolvePriceFormatted(row)
            "global_sequence" -> row.global_sequence?.toString() ?: "-"
            "batch_sequence" -> row.batch_sequence?.toString() ?: "-"
            "batch_total_users" -> row.batch_total_users?.toString() ?: "-"
            "batch_id" -> row.batch_id ?: "-"
            "owner" -> row.owner ?: "-"
            "owner_id" -> row.owner_id ?: "-"
            "server" -> row.server ?: "all"
            "time_limit" -> row.time_limit ?: ""
            "data_limit" -> resolveDataLimit(row.data_limit_bytes)
            "qr" -> row.qr_content ?: row.username ?: row.name ?: ""
            else -> ""
        }
    }

    private fun resolvePriceRaw(row: VoucherRow): String {
        val price = row.retail_price ?: row.price ?: row.invoiced_unit_price ?: return ""
        return String.format(Locale.US, "%.2f", price)
    }

    private fun resolvePriceFormatted(row: VoucherRow): String {
        val price = row.retail_price ?: row.price ?: row.invoiced_unit_price ?: return ""
        val formattedNumber = String.format(Locale.US, "%,.${currency.decimals}f", price)
        return if (currency.position == CurrencyPosition.PREFIX) {
            "${currency.icon}$formattedNumber"
        } else {
            "$formattedNumber ${currency.icon}"
        }
    }

    private fun resolveDataLimit(bytes: Long?): String {
        if (bytes == null || bytes <= 0) return ""
        return if (bytes >= 1024L * 1024 * 1024) {
            String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
        } else {
            String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024))
        }
    }

    /**
     * استبدال كافة التوكنز داخل نص ثابت (§5.3)
     */
    fun resolveTemplateText(text: String, row: VoucherRow): String =
        // تمريرة واحدة: القيم المُدخَلة لا تُفسَّر ثانية (قيمة تحتوي {{password}} لا تُوسَّع)
        TOKEN_REGEX.replace(text) { match -> resolve(match.groupValues[1].trim(), row) }

    private companion object {
        val TOKEN_REGEX = "\\{\\{(.+?)\\}\\}".toRegex()
    }
}

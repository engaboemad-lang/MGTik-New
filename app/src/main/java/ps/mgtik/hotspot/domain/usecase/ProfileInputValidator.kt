package ps.mgtik.hotspot.domain.usecase

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * ProfileInputValidator - تحقق وتحويل نقي (بلا Android) لمدخلات شاشة الباقات (S17).
 * المصدر الوحيد لقواعد: اسم الباقة على الراوتر، السعر، وسرعة rate-limit.
 */
object ProfileInputValidator {

    data class RateLimit(val rxKbps: Int, val txKbps: Int) {
        val isUnlimited: Boolean get() = rxKbps == 0 && txKbps == 0
    }

    private val RATE_TOKEN = Regex("^(\\d{1,9})([kKmMgG]?)$")
    private const val MAX_KBPS = 10_000_000L // 10 Gbps
    private val MAX_PRICE: BigDecimal = BigDecimal("10000000")

    /** اسم الباقة على الراوتر: أحرف وأرقام و - و _ فقط (بلا مسافات). */
    fun isRouterNameValid(name: String): Boolean =
        name.isNotBlank() && name.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** نص السعر (مثل "12.5") إلى وحدات صغرى (1250)؛ null إن كان فارغاً أو غير صالح أو سالباً. */
    fun parsePriceMinor(raw: String): Long? {
        val value = raw.trim().toBigDecimalOrNull() ?: return null
        if (value.signum() < 0 || value > MAX_PRICE) return null
        return value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong()
    }

    /**
     * يحلّل rate-limit بصيغة RouterOS المبسّطة: "10M/10M" أو "10M" (للاتجاهين) أو "512k/1M".
     * k=1000، M=1000000، G=1000000000 (نظام RouterOS العشري). رقم بلا لاحقة = bps.
     * نص فارغ = بلا حد (0/0). غير صالح = null.
     */
    fun parseRateLimit(raw: String): RateLimit? {
        val text = raw.trim()
        if (text.isEmpty()) return RateLimit(0, 0)
        val parts = text.split('/')
        if (parts.size > 2) return null
        val rx = tokenToKbps(parts[0]) ?: return null
        val tx = if (parts.size == 2) (tokenToKbps(parts[1]) ?: return null) else rx
        return RateLimit(rx, tx)
    }

    private fun tokenToKbps(token: String): Int? {
        val match = RATE_TOKEN.matchEntire(token.trim()) ?: return null
        val number = match.groupValues[1].toLong()
        val kbps = when (match.groupValues[2].lowercase()) {
            "k" -> number
            "m" -> number * 1_000
            "g" -> number * 1_000_000
            else -> if (number == 0L) 0L else maxOf(1L, number / 1_000) // bps -> kbps
        }
        return if (kbps in 0..MAX_KBPS) kbps.toInt() else null
    }

    /** يُنسّق السرعة بصيغة RouterOS (10M/10M أو 512k/1M)؛ null إن كانت بلا حد. */
    fun formatRateLimit(rxKbps: Int, txKbps: Int): String? {
        if (rxKbps <= 0 && txKbps <= 0) return null
        return "${formatKbps(rxKbps)}/${formatKbps(txKbps)}"
    }

    private fun formatKbps(kbps: Int): String = when {
        kbps <= 0 -> "0"
        kbps % 1_000 == 0 -> "${kbps / 1_000}M"
        else -> "${kbps}k"
    }
}

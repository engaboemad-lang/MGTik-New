package ps.mgtik.hotspot.ui.util

import androidx.annotation.StringRes
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.core.result.ApiError

/**
 * نصوص أسباب فشل/انقطاع اتصال الراوتر (مصدر واحد لشاشة الإعداد والحوار المركزي وشريط الحالة).
 * تُعاد رسالة سبب محددة فقط عند توفر رمز موثوق؛ غير ذلك رسالة عامة بدل تخمين سبب.
 */
object RouterErrorTexts {
    @StringRes
    fun reasonRes(code: String?): Int = when (code) {
        ApiError.CODE_ROUTER_AUTH_FAILED -> R.string.router_error_auth_failed
        ApiError.CODE_ROUTER_REST_UNAVAILABLE -> R.string.router_error_rest_unavailable
        ApiError.CODE_ROUTER_SSL_UNTRUSTED -> R.string.router_error_ssl_untrusted
        ApiError.CODE_ROUTER_REJECTED -> R.string.ERR_ROUTER_REJECTED
        ApiError.CODE_TIMEOUT -> R.string.router_error_timeout
        ApiError.CODE_NETWORK_UNAVAILABLE -> R.string.router_error_connection_failed
        ApiError.CODE_ROUTER_HOST_UNREACHABLE -> R.string.router_error_unreachable
        ApiError.CODE_ROUTER_PORT_CLOSED -> R.string.router_error_port_closed
        ApiError.CODE_ROUTER_PERMISSION_DENIED -> R.string.router_error_permission_denied
        ApiError.CODE_ROUTER_OFFLINE -> R.string.router_error_unreachable
        null, "" -> R.string.router_test_failed_generic
        else -> ErrorTranslator.resIdOrNull(code) ?: R.string.router_test_failed_generic
    }

    /** تلميح علاج عملي لكل سبب (S2/السبلاش/الحوار)؛ null = لا تلميح أدق من النص نفسه. */
    @StringRes
    fun hintRes(code: String?): Int? = when (code) {
        ApiError.CODE_ROUTER_HOST_UNREACHABLE, ApiError.CODE_ROUTER_OFFLINE -> R.string.router_hint_unreachable
        ApiError.CODE_ROUTER_PORT_CLOSED -> R.string.router_hint_port_closed
        ApiError.CODE_ROUTER_AUTH_FAILED -> R.string.router_hint_auth_failed
        ApiError.CODE_ROUTER_PERMISSION_DENIED -> R.string.router_hint_permission_denied
        ApiError.CODE_TIMEOUT -> R.string.router_hint_timeout
        ApiError.CODE_NETWORK_UNAVAILABLE -> R.string.router_hint_connection_failed
        ApiError.CODE_ROUTER_REST_UNAVAILABLE -> R.string.router_hint_rest_unavailable
        else -> null
    }
}

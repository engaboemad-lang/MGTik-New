package ps.mgtik.hotspot.core.result

import com.google.firebase.firestore.FirebaseFirestoreException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.*
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/**
 * ApiError - تمثيل موحد للأخطاء عبر كافة طبقات النظام.
 * تم تحديثه ليشمل معالجة أخطاء التحويل (Serialization) وأخطاء Ktor HTTP.
 */
data class ApiError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val fieldErrors: Map<String, String>? = null,
    val referenceId: String? = null
) {
    companion object {
        const val CODE_NETWORK_UNAVAILABLE = "ERR_NETWORK_UNAVAILABLE"
        const val CODE_TIMEOUT = "ERR_TIMEOUT"
        const val CODE_UNEXPECTED_CLIENT = "ERR_UNEXPECTED_CLIENT"
        const val CODE_AUTH_INVALID_CREDENTIALS = "ERR_AUTH_INVALID_CREDENTIALS"
        const val CODE_AUTH_USER_DISABLED = "ERR_AUTH_USER_DISABLED"
        const val CODE_AUTH_TOO_MANY_REQUESTS = "ERR_AUTH_TOO_MANY_REQUESTS"
        const val CODE_AUTH_OPERATION_NOT_ALLOWED = "ERR_AUTH_OPERATION_NOT_ALLOWED"
        /** عطل في خدمة الدخول السحابية نفسها (ليس خطأ في بيانات المستخدم). */
        const val CODE_AUTH_SERVICE_UNAVAILABLE = "ERR_AUTH_SERVICE_UNAVAILABLE"
        const val CODE_VALIDATION_FAILED = "ERR_VALIDATION_FAILED"
        const val CODE_SERIALIZATION_ERROR = "ERR_DATA_PARSE_FAILED"
        const val CODE_HTTP_ERROR = "ERR_HTTP_FAILED"
        const val CODE_UNKNOWN = "ERR_UNKNOWN"
        const val CODE_NOT_IMPLEMENTED = "ERR_NOT_IMPLEMENTED"
        const val CODE_ROUTER_AUTH_FAILED = "ERR_ROUTER_AUTH_FAILED"
        const val CODE_ROUTER_REST_UNAVAILABLE = "ERR_ROUTER_REST_UNAVAILABLE"
        const val CODE_ROUTER_SSL_UNTRUSTED = "ERR_ROUTER_SSL_UNTRUSTED"
        /** رفض الراوتر لأمر بعينه (قيمة غير صالحة/عنصر مكرر)؛ رسالته في ApiError.message. */
        const val CODE_ROUTER_REJECTED = "ERR_ROUTER_REJECTED"
        /** الراوتر غير متاح تشغيلياً الآن (منقطع/وضع غير متصل): رُفض الطلب قبل إرساله. قابل لإعادة المحاولة لاحقاً. */
        const val CODE_ROUTER_OFFLINE = "ERR_ROUTER_OFFLINE"
        const val CODE_ROUTER_NOT_CONFIGURED = "ERR_ROUTER_NOT_CONFIGURED"
        /** لا مسار للمضيف (عنوان خاطئ/خارج الشبكة/اسم غير معروف). */
        const val CODE_ROUTER_HOST_UNREACHABLE = "ERR_ROUTER_HOST_UNREACHABLE"
        /** المضيف رد برفض الاتصال: المنفذ مغلق أو الخدمة (api/www) معطّلة. */
        const val CODE_ROUTER_PORT_CLOSED = "ERR_ROUTER_PORT_CLOSED"
        /** المصادقة نجحت لكن صلاحيات المستخدم لا تكفي لهذه العملية. */
        const val CODE_ROUTER_PERMISSION_DENIED = "ERR_ROUTER_PERMISSION_DENIED"

        /** أكواد تدل على فقد القدرة على الوصول للراوتر نفسه (لا رفض أمر بعينه ولا خطأ بيانات). */
        private val ROUTER_CONNECTIVITY_CODES = setOf(
            CODE_NETWORK_UNAVAILABLE,
            CODE_TIMEOUT,
            CODE_ROUTER_AUTH_FAILED,
            CODE_ROUTER_REST_UNAVAILABLE,
            CODE_ROUTER_SSL_UNTRUSTED
        )

        fun isRouterConnectivityCode(code: String): Boolean = code in ROUTER_CONNECTIVITY_CODES

        fun routerOffline(): ApiError = ApiError(
            code = CODE_ROUTER_OFFLINE,
            message = "Router is not connected; operation was not sent",
            retryable = true
        )

        fun routerNotConfigured(): ApiError = ApiError(
            code = CODE_ROUTER_NOT_CONFIGURED,
            message = "Router connection is not configured",
            retryable = false
        )

        fun networkUnavailable(referenceId: String? = null): ApiError = ApiError(
            code = CODE_NETWORK_UNAVAILABLE,
            message = "Network connection failed",
            retryable = true,
            referenceId = referenceId
        )

        fun timeout(referenceId: String? = null): ApiError = ApiError(
            code = CODE_TIMEOUT,
            message = "Server request timed out",
            retryable = true,
            referenceId = referenceId
        )

        fun serializationError(e: Throwable, referenceId: String? = null): ApiError = ApiError(
            code = CODE_SERIALIZATION_ERROR,
            message = "Data parsing error: ${e.localizedMessage}",
            retryable = false,
            referenceId = referenceId
        )

        fun httpError(code: Int, message: String, referenceId: String? = null): ApiError = ApiError(
            code = "$CODE_HTTP_ERROR ($code)",
            message = message,
            retryable = code in 500..599,
            referenceId = referenceId
        )

        fun validationError(message: String, fieldErrors: Map<String, String>? = null, referenceId: String? = null): ApiError = ApiError(
            code = CODE_VALIDATION_FAILED,
            message = message,
            retryable = false,
            fieldErrors = fieldErrors,
            referenceId = referenceId
        )

        /** خطأ غير مصنَّف بنص صريح (حالات لا يملك المستدعي لها رمزاً أدق). */
        fun unknownError(message: String, referenceId: String? = null): ApiError = ApiError(
            code = CODE_UNKNOWN,
            message = message,
            retryable = false,
            referenceId = referenceId
        )

        fun unexpectedClientError(e: Throwable, referenceId: String? = null): ApiError = ApiError(
            code = CODE_UNEXPECTED_CLIENT,
            message = e.localizedMessage ?: "Internal application error",
            retryable = false,
            referenceId = referenceId
        )

        fun routerAuthFailed(referenceId: String? = null): ApiError = ApiError(
            code = CODE_ROUTER_AUTH_FAILED,
            message = "Router rejected the credentials",
            retryable = false,
            referenceId = referenceId
        )

        fun routerPermissionDenied(referenceId: String? = null): ApiError = ApiError(
            code = CODE_ROUTER_PERMISSION_DENIED,
            message = "Router user lacks the required permissions",
            retryable = false,
            referenceId = referenceId
        )

        fun routerHostUnreachable(referenceId: String? = null): ApiError = ApiError(
            code = CODE_ROUTER_HOST_UNREACHABLE,
            message = "Router host is unreachable",
            retryable = true,
            referenceId = referenceId
        )

        fun routerPortClosed(referenceId: String? = null): ApiError = ApiError(
            code = CODE_ROUTER_PORT_CLOSED,
            message = "Connection refused: router port is closed",
            retryable = true,
            referenceId = referenceId
        )

        fun routerRestUnavailable(referenceId: String? = null): ApiError = ApiError(
            code = CODE_ROUTER_REST_UNAVAILABLE,
            message = "Router REST API endpoint not found",
            retryable = false,
            referenceId = referenceId
        )

        /** رمز عمل صريح: رسالة استثناء بصيغة UPPER_SNAKE (مثل ERR_INSUFFICIENT_CREDIT) في سلسلة الأسباب. */
        private val BUSINESS_CODE = Regex("^[A-Z][A-Z0-9_]{3,}$")

        private fun businessCodeOf(e: Throwable): String? =
            generateSequence(e) { it.cause }.take(6).mapNotNull { it.message?.trim() }.firstOrNull { BUSINESS_CODE.matches(it) }

        private fun fromFirestore(e: FirebaseFirestoreException, referenceId: String?): ApiError =
            // runTransaction قد يغلّف استثناء العمل (ERR_INSUFFICIENT_CREDIT...) داخل FirebaseFirestoreException
            businessCodeOf(e)?.let { ApiError(code = it, message = it, retryable = false, referenceId = referenceId) } ?: when (e.code) {
            // أعطال مؤقتة: تستحق إعادة المحاولة
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.ABORTED,
            FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED -> networkUnavailable(referenceId)
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> timeout(referenceId)
            else -> ApiError(
                code = "ERR_FIRESTORE_${e.code.name}",
                message = e.localizedMessage ?: e.code.name,
                retryable = false,
                referenceId = referenceId
            )
        }

        fun mapThrowable(e: Throwable, referenceId: String? = null): ApiError = when (e) {
            is RuleViolation -> ApiError(code = e.code, message = e.message.orEmpty(), referenceId = referenceId)
            is FirebaseFirestoreException -> fromFirestore(e, referenceId)
            is SSLException -> ApiError(
                code = CODE_ROUTER_SSL_UNTRUSTED,
                message = "TLS handshake failed: ${e.localizedMessage}",
                retryable = false,
                referenceId = referenceId
            )
            is TimeoutException,
            is SocketTimeoutException,
            is ConnectTimeoutException,
            is HttpRequestTimeoutException -> timeout(referenceId)
            // تمييز أسباب فشل الاتصال المختلفة (S2): رفض الاتصال = منفذ مغلق، لا مسار = مضيف غير قابل للوصول
            is java.net.NoRouteToHostException, is java.net.UnknownHostException,
            is java.net.PortUnreachableException -> routerHostUnreachable(referenceId)
            is java.net.ConnectException ->
                if (e.message?.contains("refused", ignoreCase = true) == true) routerPortClosed(referenceId)
                else if (e.message?.contains("unreachable", ignoreCase = true) == true) routerHostUnreachable(referenceId)
                else networkUnavailable(referenceId)
            is IOException -> networkUnavailable(referenceId)
            is SerializationException -> serializationError(e, referenceId)
            is ResponseException -> httpError(e.response.status.value, e.localizedMessage, referenceId)
            else -> businessCodeOf(e)?.let { ApiError(code = it, message = it, retryable = false, referenceId = referenceId) }
                ?: unexpectedClientError(e, referenceId)
        }
    }
}

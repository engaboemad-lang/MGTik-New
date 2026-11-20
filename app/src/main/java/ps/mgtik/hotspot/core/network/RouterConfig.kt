package ps.mgtik.hotspot.core.network

/**
 * منافذ RouterOS الافتراضية.
 * - REST (RouterOS 7.1+): خدمتا www (HTTP) و www-ssl (HTTPS) في /ip service.
 * - API الثنائي (RouterOS 6.x و7.x): خدمتا api (8728) و api-ssl (8729).
 */
object RouterPorts {
    const val HTTP = 80
    const val HTTPS = 443
    const val API = 8728
    const val API_SSL = 8729
    const val MIN = 1
    const val MAX = 65535

    fun defaultFor(useSsl: Boolean): Int = if (useSsl) HTTPS else HTTP
    fun apiDefaultFor(useSsl: Boolean): Int = if (useSsl) API_SSL else API
}

/**
 * طريقة الاتصال بالراوتر.
 * - [AUTO]: تجربة REST أولاً (RouterOS 7.1+) ثم العودة إلى API الثنائي (RouterOS 6.x) إن لم يتوفر REST.
 * - [REST]: RouterOS 7.1+ فقط.
 * - [API]: بروتوكول API الثنائي، يعمل على RouterOS 6.x و7.x.
 */
enum class RouterConnectionType { AUTO, REST, API }

/**
 * RouterConfig - إعدادات الاتصال براوتر MikroTik (REST و/أو API)
 * تم نقله إلى وحدة core ليكون متاحاً لعميل الشبكة والمستودعات.
 */
data class RouterConfig(
    val host: String = "192.168.88.1",
    /** منفذ REST (www / www-ssl). */
    val port: Int = RouterPorts.HTTP,
    val username: String = "admin",
    val password: String = "",
    val useSsl: Boolean = false,
    val autoSyncIntervalSeconds: Int = 30,
    val isConnected: Boolean = true,
    /** منفذ API الثنائي (api / api-ssl) لـ RouterOS 6.x. */
    val apiPort: Int = RouterPorts.API,
    val connectionType: RouterConnectionType = RouterConnectionType.AUTO
)

/**
 * هل يشير الإعدادان إلى نفس نقطة الاتصال؟ (العنوان/المنافذ/المصادقة/SSL/نوع النقل).
 * حقول مثل [RouterConfig.isConnected] و[RouterConfig.autoSyncIntervalSeconds] لا تغيّر الاتصال؛ كانت المقارنة
 * بـ == تعدّها فيُسقط الاتصال الصالح ويُعاد فتحه كلما تبدّل الإعداد بين نسخة الفحص ونسخة السحابة.
 */
fun RouterConfig.sameEndpoint(other: RouterConfig?): Boolean =
    other != null &&
        host == other.host &&
        port == other.port &&
        apiPort == other.apiPort &&
        username == other.username &&
        password == other.password &&
        useSsl == other.useSsl &&
        connectionType == other.connectionType

package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.core.network.RouterConfig
import ps.mgtik.hotspot.core.network.RouterConnectionType
import ps.mgtik.hotspot.core.network.RouterPorts
import ps.mgtik.hotspot.core.result.ApiError

/**
 * RouterInputValidator - تحقق نقي (بلا Android) من مدخلات شاشة إعداد الراوتر (§2).
 */
object RouterInputValidator {

    private val IPV4 = Regex(
        "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$"
    )
    // اسم مضيف: مقاطع من حروف/أرقام/شرطة مفصولة بنقاط (مثل router.local)
    private val HOSTNAME = Regex(
        "^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$"
    )

    /** منفذا API الثنائي؛ لا يعملان مع REST (يعملان مع نمط API / AUTO عبر حقل منفذ API). */
    private val BINARY_API_PORTS = setOf(RouterPorts.API, RouterPorts.API_SSL)

    /** عنوان IPv4 أو اسم مضيف فقط: بلا بروتوكول أو منفذ أو مسار أو مسافات. */
    fun isHostValid(host: String): Boolean {
        val h = host.trim()
        if (h.isEmpty()) return false
        return IPV4.matches(h) || (!h.all { it.isDigit() || it == '.' } && HOSTNAME.matches(h))
    }

    /**
     * تحقق من إعداد جاهز للحفظ (العنوان والمنفذ)؛ null إن كان صالحاً. منفذا API الثنائي لا يُرفضان هنا
     * (الشاشة تنبّه إليهما فقط) فلا يتغير سلوك ما كان يُحفظ سابقاً.
     */
    fun validate(config: RouterConfig): ApiError? {
        val errors = linkedMapOf<String, String>()
        if (!isHostValid(config.host)) errors["host"] = ERR_HOST_INVALID
        // منفذ REST لا معنى له في نمط API وحده، ومنفذ API لا معنى له في نمط REST وحده
        if (config.connectionType != RouterConnectionType.API && config.port !in RouterPorts.MIN..RouterPorts.MAX) {
            errors["port"] = ERR_PORT_INVALID
        }
        if (config.connectionType != RouterConnectionType.REST && config.apiPort !in RouterPorts.MIN..RouterPorts.MAX) {
            errors["apiPort"] = ERR_API_PORT_INVALID
        }
        return if (errors.isEmpty()) null
        else ApiError.validationError("Invalid router connection settings", errors)
    }

    const val ERR_HOST_INVALID = "ERR_ROUTER_HOST_INVALID"
    const val ERR_PORT_INVALID = "ERR_ROUTER_PORT_INVALID"
    const val ERR_API_PORT_INVALID = "ERR_ROUTER_API_PORT_INVALID"

    fun parsePort(raw: String): Int? =
        raw.toIntOrNull()?.takeIf { it in RouterPorts.MIN..RouterPorts.MAX }

    fun isBinaryApiPort(port: Int?): Boolean = port != null && port in BINARY_API_PORTS

    /**
     * عند تبديل SSL نغيّر المنفذ فقط إذا كان فارغاً أو أحد الافتراضيين (80/443)،
     * كي لا نُلغي منفذاً مخصصاً كتبه المستخدم.
     */
    fun portAfterSslToggle(currentPort: String, useSsl: Boolean): String {
        val p = currentPort.toIntOrNull()
        val isDefaultOrEmpty = currentPort.isBlank() || p == RouterPorts.HTTP || p == RouterPorts.HTTPS
        return if (isDefaultOrEmpty) RouterPorts.defaultFor(useSsl).toString() else currentPort
    }

    /** نفس منطق [portAfterSslToggle] لمنفذ API (8728 ↔ 8729). */
    fun apiPortAfterSslToggle(currentPort: String, useSsl: Boolean): String {
        val p = currentPort.toIntOrNull()
        val isDefaultOrEmpty = currentPort.isBlank() || p == RouterPorts.API || p == RouterPorts.API_SSL
        return if (isDefaultOrEmpty) RouterPorts.apiDefaultFor(useSsl).toString() else currentPort
    }
}

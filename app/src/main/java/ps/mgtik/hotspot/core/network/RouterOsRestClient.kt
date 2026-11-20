package ps.mgtik.hotspot.core.network

import android.annotation.SuppressLint
import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.request.header
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import ps.mgtik.hotspot.core.result.ApiError
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal val routerJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}

/**
 * RouterOsRestClient - العميل الموحد للاتصال بـ MikroTik REST API (§3)
 *
 * - محرّك OkHttp واحد مشترك (Shared Engine) لمنع تسريب الموارد.
 * - HTTPS: راوترات MikroTik تستخدم شهادة ذاتية التوقيع افتراضياً، وهذا العميل مخصص حصراً
 *   للراوتر المحلي الذي يُدخل المستخدم عنوانه؛ لذلك تُقبل شهادة الراوتر دون تحقق من السلسلة
 *   (يبقى الاتصال مشفراً). الشاشة S2 تُعلِم المستخدم بذلك صراحةً.
 * - [createClient] يُنتج عميلاً مشتقاً مضبوطاً لراوتر محدد (العنوان + Basic Auth)،
 *   وإغلاقه لا يُغلق المحرّك المشترك.
 */
@Singleton
class RouterOsRestClient @Inject constructor() {

    private val baseClient: HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                val trustManager = TrustRouterCertificate()
                val sslContext = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf(trustManager), SecureRandom())
                }
                sslSocketFactory(sslContext.socketFactory, trustManager)
                hostnameVerifier { _, _ -> true }
            }
        }

        install(ContentNegotiation) {
            json(routerJson)
        }

        install(HttpTimeout) {
            requestTimeoutMillis = NetworkConfig.NETWORK_TIMEOUT_MS
            connectTimeoutMillis = NetworkConfig.NETWORK_TIMEOUT_MS
            socketTimeoutMillis = NetworkConfig.NETWORK_TIMEOUT_MS
        }

        // نفحص رمز الحالة يدوياً عبر requireRouterSuccess ليصلنا 401 كخطأ مصنَّف
        expectSuccess = false
    }

    /** عميل مشتق مضبوط للراوتر: المسارات النسبية مثل "/rest/system/resource" تعمل مباشرة. */
    fun createClient(config: RouterConfig): HttpClient = baseClient.config {
        defaultRequest {
            url {
                protocol = if (config.useSsl) URLProtocol.HTTPS else URLProtocol.HTTP
                host = config.host
                port = config.port
            }
            header(HttpHeaders.Authorization, basicAuth(config.username, config.password))
            contentType(ContentType.Application.Json)
        }
    }

    private fun basicAuth(username: String, password: String): String =
        "Basic " + Base64.encodeToString("$username:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}

/** يقبل شهادة الراوتر المحلي (ذاتية التوقيع) — انظر توثيق RouterOsRestClient. */
@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
internal class TrustRouterCertificate : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * يتحقق من نجاح الاستجابة، ويحوّل أخطاء RouterOS الشائعة إلى ApiError مصنَّف:
 * 401/403 ← فشل المصادقة، 404 ← خدمة REST غير متاحة (www / www-ssl غير مفعّلة أو إصدار < 7.1).
 */
suspend fun HttpResponse.requireRouterSuccess(): HttpResponse {
    if (status.value in 200..299) return this
    throw RouterApiException(
        when (status) {
            HttpStatusCode.Unauthorized -> ApiError.routerAuthFailed()
            HttpStatusCode.Forbidden -> ApiError.routerPermissionDenied()
            HttpStatusCode.NotFound -> ApiError.routerRestUnavailable()
            else -> ApiError.httpError(status.value, "Router returned HTTP ${status.value}")
        }
    )
}

/**
 * RouterOS REST يعيد كل القيم كنصوص ("cpu-load":"3")؛ لذلك نقرأ JsonElement لا Map<String, Any?>.
 */
suspend fun HttpResponse.asJsonObject(): JsonObject =
    routerJson.parseToJsonElement(bodyAsText()) as? JsonObject
        ?: throw RouterApiException(ApiError.httpError(status.value, "Unexpected router response shape"))

suspend fun HttpResponse.asJsonArray(): JsonArray =
    routerJson.parseToJsonElement(bodyAsText()) as? JsonArray
        ?: throw RouterApiException(ApiError.httpError(status.value, "Unexpected router response shape"))

/** قراءة آمنة لقيمة نصية من كائن RouterOS. */
fun JsonObject.string(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()

fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()

fun JsonObject.bool(key: String): Boolean = string(key) == "true"

/**
 * RouterOS REST يقبل JSON فقط؛ Map<String, Any?> غير قابلة للتسلسل بـ kotlinx.serialization،
 * لذا تُحوَّل إلى JsonObject (القيم غير الأولية تُرسَل كنص كما يتوقع RouterOS).
 */
fun Map<String, Any?>.toRouterJson(): JsonObject = JsonObject(
    mapValues { (_, v) ->
        when (v) {
            null -> JsonNull
            is JsonElement -> v
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    }
)

package ps.mgtik.hotspot.core.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import ps.mgtik.hotspot.core.result.ApiError
import kotlin.coroutines.coroutineContext

/**
 * RestRouterApi - RouterOS 7.1+ عبر REST (www / www-ssl). المسارات تُسبق بـ /rest.
 */
internal class RestRouterApi(
    private val client: HttpClient,
    override val port: Int
) : RouterApi {

    override val kind: RouterConnectionType = RouterConnectionType.REST

    /** مهلة الطلب حسب نوع العملية الجارية (سياق الكوروتين)؛ null = مهلة العميل الافتراضية. */
    private suspend fun opTimeoutMs(): Long? = coroutineContext[RouterOpTimeout]?.kind?.readTimeoutMs

    private fun HttpRequestBuilder.withOpTimeout(ms: Long?) {
        if (ms != null) timeout { requestTimeoutMillis = ms; socketTimeoutMillis = ms }
    }

    override suspend fun print(path: String, query: Map<String, String>, props: List<String>): List<JsonObject> {
        val t = opTimeoutMs()
        val response = client.get("/rest$path") {
            withOpTimeout(t)
            query.forEach { (k, v) -> parameter(k, v) }
            if (props.isNotEmpty()) parameter(".proplist", props.joinToString(","))
        }.requireRouterSuccess()
        // المسارات المفردة (system/resource) تعيد كائناً، والقوائم تعيد مصفوفة
        return when (val element = routerJson.parseToJsonElement(response.bodyAsText())) {
            is JsonArray -> element.map { it.jsonObject }
            is JsonObject -> listOf(element)
            else -> throw RouterApiException(ApiError.httpError(response.status.value, "Unexpected router response shape"))
        }
    }

    override suspend fun add(path: String, attrs: Map<String, Any?>) {
        val t = opTimeoutMs()
        client.put("/rest$path") { withOpTimeout(t); setBody(attrs.toRouterJson()) }.requireWriteSuccess()
    }

    override suspend fun set(path: String, ref: String, attrs: Map<String, Any?>) {
        val t = opTimeoutMs()
        client.patch("/rest$path/$ref") { withOpTimeout(t); setBody(attrs.toRouterJson()) }.requireWriteSuccess()
    }

    override suspend fun setSingle(path: String, attrs: Map<String, Any?>) {
        val t = opTimeoutMs()
        client.patch("/rest$path") { withOpTimeout(t); setBody(attrs.toRouterJson()) }.requireWriteSuccess()
    }

    override suspend fun remove(path: String, ref: String): Boolean {
        val t = opTimeoutMs()
        val response = client.delete("/rest$path/$ref") { withOpTimeout(t) }
        if (response.status == HttpStatusCode.NotFound) return false
        response.requireRouterSuccess()
        return true
    }

    override suspend fun command(path: String, attrs: Map<String, Any?>) {
        val t = opTimeoutMs()
        client.post("/rest$path") { withOpTimeout(t); setBody(attrs.toRouterJson()) }.requireRouterSuccess()
    }

    override suspend fun action(path: String, action: String, ref: String?) {
        val t = opTimeoutMs()
        client.post("/rest$path/$action") {
            withOpTimeout(t)
            if (ref != null) setBody(mapOf<String, Any?>("numbers" to ref).toRouterJson())
        }.requireRouterSuccess()
    }

    override fun close() = client.close()

    /**
     * كتابة (add/set): 401/403 مصادقة، 404 خدمة REST، 5xx عطل خادم (قابل لإعادة المحاولة)،
     * وأي 4xx آخر رفض لهذا الأمر بنص الراوتر (مثل «already have such user»).
     */
    private suspend fun HttpResponse.requireWriteSuccess() {
        val code = status.value
        if (code in 200..299) return
        when {
            status == HttpStatusCode.Unauthorized -> throw RouterApiException(ApiError.routerAuthFailed())
            // 403: المستخدم صادق لكن صلاحياته لا تكفي (سياسة المجموعة)
            status == HttpStatusCode.Forbidden -> throw RouterApiException(ApiError.routerPermissionDenied())
            status == HttpStatusCode.NotFound -> throw RouterApiException(ApiError.routerRestUnavailable())
            code in 500..599 -> throw RouterApiException(ApiError.httpError(code, "Router returned HTTP $code"))
            else -> throw RouterCommandRejected(runCatching { bodyAsText() }.getOrDefault("").take(160).ifBlank { "HTTP $code" })
        }
    }
}

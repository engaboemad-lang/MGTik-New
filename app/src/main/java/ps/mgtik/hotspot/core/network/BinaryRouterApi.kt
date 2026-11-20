package ps.mgtik.hotspot.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ps.mgtik.hotspot.core.result.ApiError
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * BinaryRouterApi - بروتوكول API الثنائي (المنفذ 8728 / 8729 TLS) المدعوم في RouterOS 6.x و7.x.
 * هو المسار الوحيد للإصدار 6 (لا REST فيه). اتصال واحد لكل راوتر، والأوامر تُنفَّذ على التوالي (قفل).
 *
 * - تسجيل الدخول: الصيغة الحديثة (6.43+) بالاسم وكلمة المرور، ثم التحدّي القديم (MD5) تلقائياً للإصدارات الأقدم.
 * - TLS: نفس سياسة REST (شهادة الراوتر المحلي ذاتية التوقيع تُقبل)، ويتطلب أن تكون لخدمة api-ssl شهادة على الراوتر.
 * - أي خطأ في منتصف تبادل يُغلق المقبس (ردود متبقية قد تُفسد التزامن)؛ يُعاد الاتصال في الطلب التالي.
 */
internal class BinaryRouterApi private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: BufferedOutputStream,
    override val port: Int
) : RouterApi {

    override val kind: RouterConnectionType = RouterConnectionType.API

    private val lock = Mutex()

    private class Reply(val rows: List<Map<String, String>>, val ret: String?, val trap: String?)

    override fun isUsable(): Boolean = !socket.isClosed && socket.isConnected

    override suspend fun print(path: String, query: Map<String, String>, props: List<String>): List<JsonObject> {
        val words = ArrayList<String>()
        words += "$path/print"
        if (props.isNotEmpty()) words += "=.proplist=${props.joinToString(",")}"
        query.forEach { (k, v) -> words += "?$k=$v" }
        val reply = exchange(words)
        reply.trap?.let { throw mapTrap(it) }
        return reply.rows.map { row -> JsonObject(row.mapValues { JsonPrimitive(it.value) }) }
    }

    override suspend fun add(path: String, attrs: Map<String, Any?>) {
        val reply = exchange(listOf("$path/add") + attributeWords(attrs))
        reply.trap?.let { throw mapTrap(it) }
    }

    override suspend fun set(path: String, ref: String, attrs: Map<String, Any?>) {
        val id = resolveId(path, ref) ?: throw RouterCommandRejected("no such item")
        val reply = exchange(listOf("$path/set", "=.id=$id") + attributeWords(attrs))
        reply.trap?.let { throw mapTrap(it) }
    }

    override suspend fun setSingle(path: String, attrs: Map<String, Any?>) {
        val reply = exchange(listOf("$path/set") + attributeWords(attrs))
        reply.trap?.let { throw mapTrap(it) }
    }

    override suspend fun remove(path: String, ref: String): Boolean {
        val id = resolveId(path, ref) ?: return false
        val reply = exchange(listOf("$path/remove", "=.id=$id"))
        val trap = reply.trap ?: return true
        if (trap.contains("no such item", ignoreCase = true)) return false
        throw mapTrap(trap)
    }

    override suspend fun command(path: String, attrs: Map<String, Any?>) {
        val reply = exchange(listOf(path) + attributeWords(attrs))
        reply.trap?.let { throw mapTrap(it) }
    }

    override suspend fun action(path: String, action: String, ref: String?) {
        val words = ArrayList<String>()
        words += "$path/$action"
        if (ref != null) {
            val id = resolveId(path, ref) ?: throw RouterCommandRejected("no such item")
            words += "=numbers=$id"
        }
        val reply = exchange(words)
        reply.trap?.let { throw mapTrap(it) }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    /** الاسم ← .id (المعرّف يبدأ بـ *): أوامر set/remove في API تقبل .id فقط. */
    private suspend fun resolveId(path: String, ref: String): String? {
        if (ref.startsWith("*")) return ref
        return print(path, query = mapOf("name" to ref), props = listOf(".id"))
            .firstOrNull()?.get(".id")?.let { (it as? JsonPrimitive)?.content }
    }

    private fun attributeWords(attrs: Map<String, Any?>): List<String> =
        attrs.mapNotNull { (k, v) ->
            when (v) {
                null -> null
                is Boolean -> "=$k=${if (v) "yes" else "no"}"
                else -> "=$k=$v"
            }
        }

    private fun mapTrap(message: String): Exception =
        if (message.contains("not enough permissions", ignoreCase = true) ||
            message.contains("permission", ignoreCase = true)
        ) RouterApiException(ApiError.routerPermissionDenied())
        else if (message.contains("cannot log in", ignoreCase = true) ||
            message.contains("invalid user", ignoreCase = true)
        ) RouterApiException(ApiError.routerAuthFailed())
        else RouterCommandRejected(message.take(160))

    /** يرسل جملة ويقرأ الردود حتى !done. !trap لا يقطع القراءة (يتبعه !done دائماً)، و!fatal يقطع الاتصال. */
    private suspend fun exchange(words: List<String>): Reply = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                // مهلة القراءة حسب نوع العملية (نبض 5ث / عادية 8ث / ضخمة 30ث)؛ تُضبط داخل القفل فلا تتداخل العمليات
                val readMs = coroutineContext[RouterOpTimeout]?.kind?.readTimeoutMs ?: NetworkConfig.NETWORK_TIMEOUT_MS
                socket.soTimeout = readMs.toInt()
                RouterApiCodec.writeSentence(output, words)
                val rows = ArrayList<Map<String, String>>()
                var ret: String? = null
                var trap: String? = null
                while (true) {
                    val sentence = RouterApiCodec.readSentence(input)
                    if (sentence.isEmpty()) continue
                    when (sentence[0]) {
                        "!re" -> rows += RouterApiCodec.attributes(sentence)
                        // RouterOS 7.18+: أمر بلا بيانات يرد بـ !empty ثم !done؛ لا صفوف ولا خطأ (v6 وما قبل 7.18 لا يرسلانه)
                        "!empty" -> Unit
                        "!done" -> {
                            ret = RouterApiCodec.attributes(sentence)["ret"]
                            break
                        }
                        "!trap" -> if (trap == null) trap = RouterApiCodec.attributes(sentence)["message"] ?: "trap"
                        "!fatal" -> throw RouterApiException(ApiError.networkUnavailable())
                    }
                }
                Reply(rows, ret, trap)
            } catch (t: Throwable) {
                close()
                throw t
            }
        }
    }

    private suspend fun login(username: String, password: String) {
        // الحديث (6.43+ و7.x): اسم وكلمة مرور في أمر واحد
        val first = exchange(listOf("/login", "=name=$username", "=password=$password"))
        if (first.trap != null) throw RouterApiException(ApiError.routerAuthFailed())
        val challenge = first.ret ?: return
        // القديم (قبل 6.43): تجاهل المعاملات وأعاد تحدّياً ← رد MD5
        val second = exchange(listOf("/login", "=name=$username", "=response=${RouterApiCodec.legacyLoginResponse(password, challenge)}"))
        if (second.trap != null) throw RouterApiException(ApiError.routerAuthFailed())
    }

    companion object {
        suspend fun connect(config: RouterConfig): BinaryRouterApi = withContext(Dispatchers.IO) {
            val plain = Socket()
            try {
                plain.connect(InetSocketAddress(config.host, config.apiPort), NetworkConfig.NETWORK_TIMEOUT_MS.toInt())
                plain.soTimeout = NetworkConfig.NETWORK_TIMEOUT_MS.toInt()
                val socket: Socket = if (config.useSsl) {
                    val trust = TrustRouterCertificate()
                    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
                    (context.socketFactory.createSocket(plain, config.host, config.apiPort, true) as SSLSocket).apply {
                        soTimeout = NetworkConfig.NETWORK_TIMEOUT_MS.toInt()
                        startHandshake()
                    }
                } else plain
                val api = BinaryRouterApi(
                    socket,
                    DataInputStream(BufferedInputStream(socket.getInputStream())),
                    BufferedOutputStream(socket.getOutputStream()),
                    config.apiPort
                )
                api.login(config.username, config.password)
                api
            } catch (t: Throwable) {
                runCatching { plain.close() }
                throw t
            }
        }
    }
}

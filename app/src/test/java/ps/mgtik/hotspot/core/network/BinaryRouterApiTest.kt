package ps.mgtik.hotspot.core.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ps.mgtik.hotspot.core.result.ApiError
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * اختبار بروتوكول API الثنائي (RouterOS 6.x) على خادم محاكٍ محلي يتبع شكل الردود الفعلي:
 * !re / !done / !trap، تسجيل الدخول الحديث والتحدّي القديم، حل الاسم إلى .id.
 */
class BinaryRouterApiTest {

    private val server = ServerSocket(0)
    private var worker: Thread? = null

    @After
    fun tearDown() {
        runCatching { server.close() }
        worker?.interrupt()
    }

    private fun config(password: String = "pw") = RouterConfig(
        host = "127.0.0.1", apiPort = server.localPort, username = "admin", password = password,
        connectionType = RouterConnectionType.API
    )

    /** ينفّذ سيناريو الخادم على خيط منفصل: [script] يستقبل المدخل/المخرج ويرد على الجمل بالترتيب. */
    private fun serve(script: (DataInputStream, BufferedOutputStream) -> Unit) {
        worker = thread(isDaemon = true) {
            server.accept().use { s ->
                script(DataInputStream(s.getInputStream().buffered()), BufferedOutputStream(s.getOutputStream()))
            }
        }
    }

    private fun reply(out: BufferedOutputStream, vararg sentences: List<String>) =
        sentences.forEach { RouterApiCodec.writeSentence(out, it) }

    @Test
    fun modernLoginThenPrintReturnsRowsAsStrings() = runBlocking {
        serve { input, out ->
            val login = RouterApiCodec.readSentence(input)
            assertEquals(listOf("/login", "=name=admin", "=password=pw"), login)
            reply(out, listOf("!done"))
            val print = RouterApiCodec.readSentence(input)
            assertEquals("/system/resource/print", print[0])
            reply(out, listOf("!re", "=cpu-load=7", "=version=6.49.10 (long-term)", "=uptime=1w2d"), listOf("!done"))
        }
        val api = BinaryRouterApi.connect(config())
        val row: JsonObject = api.printSingle("/system/resource")
        assertEquals("7", row.string("cpu-load"))
        assertEquals("6.49.10 (long-term)", row.string("version"))
        assertEquals(RouterConnectionType.API, api.kind)
        assertEquals(server.localPort, api.port)
        api.close()
    }

    @Test
    fun legacyChallengeLoginIsAnsweredWithMd5Response() = runBlocking {
        val challenge = "00112233445566778899aabbccddeeff"
        serve { input, out ->
            RouterApiCodec.readSentence(input)                       // الصيغة الحديثة تُتجاهَل في RouterOS < 6.43
            reply(out, listOf("!done", "=ret=$challenge"))
            val second = RouterApiCodec.readSentence(input)
            assertEquals("/login", second[0])
            assertEquals("=name=admin", second[1])
            assertEquals("=response=" + RouterApiCodec.legacyLoginResponse("secret", challenge), second[2])
            reply(out, listOf("!done"))
        }
        BinaryRouterApi.connect(config("secret")).close()
    }

    @Test
    fun wrongPasswordIsClassifiedAsAuthFailure() = runBlocking {
        serve { input, out ->
            RouterApiCodec.readSentence(input)
            reply(out, listOf("!trap", "=message=invalid user name or password (6)"), listOf("!done"))
        }
        try {
            BinaryRouterApi.connect(config())
            fail("expected auth failure")
        } catch (e: RouterApiException) {
            assertEquals(ApiError.CODE_ROUTER_AUTH_FAILED, e.error.code)
        }
    }

    @Test
    fun addTrapBecomesRejectedWithRouterMessage() = runBlocking {
        serve { input, out ->
            RouterApiCodec.readSentence(input); reply(out, listOf("!done"))
            val add = RouterApiCodec.readSentence(input)
            assertEquals("/user-manager/user/add", add[0])
            assertTrue(add.contains("=name=dup"))
            assertTrue(add.contains("=disabled=no"))          // Boolean ⇒ yes/no
            reply(out, listOf("!trap", "=message=failure: already have user with this name for this server"), listOf("!done"))
        }
        val api = BinaryRouterApi.connect(config())
        try {
            api.add("/user-manager/user", mapOf("name" to "dup", "disabled" to false))
            fail("expected rejection")
        } catch (e: RouterCommandRejected) {
            assertTrue(e.routerMessage.contains("already have", ignoreCase = true))
        }
        assertTrue("trap لا يُسقط الاتصال", api.isUsable())
        api.close()
    }

    @Test
    fun removeByNameResolvesIdAndMissingNameReturnsFalse() = runBlocking {
        serve { input, out ->
            RouterApiCodec.readSentence(input); reply(out, listOf("!done"))
            // remove "u1": print ?name=u1 ثم remove بالمعرّف
            val find = RouterApiCodec.readSentence(input)
            assertEquals(listOf("/user-manager/user/print", "=.proplist=.id", "?name=u1"), find)
            reply(out, listOf("!re", "=.id=*7"), listOf("!done"))
            assertEquals(listOf("/user-manager/user/remove", "=.id=*7"), RouterApiCodec.readSentence(input))
            reply(out, listOf("!done"))
            // remove "ghost": لا صفوف ⇒ false دون أمر remove
            RouterApiCodec.readSentence(input)
            reply(out, listOf("!done"))
        }
        val api = BinaryRouterApi.connect(config())
        assertTrue(api.remove("/user-manager/user", "u1"))
        assertFalse(api.remove("/user-manager/user", "ghost"))
        api.close()
    }

    @Test
    fun emptyReplyWordFromRouterOs718YieldsNoRowsAndNoError() = runBlocking {
        serve { input, out ->
            RouterApiCodec.readSentence(input); reply(out, listOf("!done"))
            RouterApiCodec.readSentence(input)
            // RouterOS 7.18+: جدول بلا صفوف يرد !empty ثم !done
            reply(out, listOf("!empty"), listOf("!done"))
            // والاتصال ما زال متزامناً للأمر التالي
            RouterApiCodec.readSentence(input)
            reply(out, listOf("!re", "=.id=*1", "=name=u1"), listOf("!done"))
        }
        val api = BinaryRouterApi.connect(config())
        assertTrue(api.print("/user-manager/user").isEmpty())
        assertEquals("u1", api.print("/user-manager/user").single().string("name"))
        assertTrue(api.isUsable())
        api.close()
    }

    @Test
    fun rebootActionHasNoNumbersArgument() = runBlocking {
        serve { input, out ->
            RouterApiCodec.readSentence(input); reply(out, listOf("!done"))
            assertEquals(listOf("/system/reboot"), RouterApiCodec.readSentence(input))
            reply(out, listOf("!done"))
        }
        val api = BinaryRouterApi.connect(config())
        api.action("/system", "reboot")
        api.close()
    }
}

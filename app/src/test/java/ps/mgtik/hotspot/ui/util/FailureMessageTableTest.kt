package ps.mgtik.hotspot.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.core.result.ApiError
import java.io.File
import java.lang.reflect.Modifier

/**
 * المهمة 6: جدول يربط كل سيناريو فشل (S1/S2/S32) برسالته المتوقعة، ويربط كل ApiError.code برسالته،
 * ويتحقق أن النصوص موجودة بالعربية والإنجليزية، وأن لا رسالة عامة حين يكون السبب معروفاً.
 */
class FailureMessageTableTest {

    private fun idOf(name: String): Int = R.string::class.java.getField(name).getInt(null)

    private data class Case(val scenario: String, val code: String, val expectedKey: String)

    private val s1 = listOf(
        Case("S1 كلمة مرور/بريد خاطئ", ApiError.CODE_AUTH_INVALID_CREDENTIALS, "ERR_AUTH_INVALID_CREDENTIALS"),
        Case("S1 حساب معطل", ApiError.CODE_AUTH_USER_DISABLED, "ERR_AUTH_USER_DISABLED"),
        Case("S1 كثرة المحاولات", ApiError.CODE_AUTH_TOO_MANY_REQUESTS, "ERR_AUTH_TOO_MANY_REQUESTS"),
        Case("S1 عطل خدمة الدخول (ليس بيانات)", ApiError.CODE_AUTH_SERVICE_UNAVAILABLE, "ERR_AUTH_SERVICE_UNAVAILABLE"),
        Case("S1 لا إنترنت قبل الإرسال", ApiError.CODE_NETWORK_UNAVAILABLE, "ERR_NETWORK_UNAVAILABLE")
    )

    private val s2 = listOf(
        Case("S2 مضيف غير قابل للوصول", ApiError.CODE_ROUTER_HOST_UNREACHABLE, "router_error_unreachable"),
        Case("S2 منفذ مغلق", ApiError.CODE_ROUTER_PORT_CLOSED, "router_error_port_closed"),
        Case("S2 فشل مصادقة", ApiError.CODE_ROUTER_AUTH_FAILED, "router_error_auth_failed"),
        Case("S2 فشل اتصال", ApiError.CODE_NETWORK_UNAVAILABLE, "router_error_connection_failed"),
        Case("S2 انتهاء مهلة", ApiError.CODE_TIMEOUT, "router_error_timeout"),
        Case("S2 صلاحيات غير كافية", ApiError.CODE_ROUTER_PERMISSION_DENIED, "router_error_permission_denied")
    )

    @Test fun s1ScenarioMessages() {
        s1.forEach { assertEquals(it.scenario, idOf(it.expectedKey), ErrorTranslator.resIdOrNull(it.code)) }
    }

    @Test fun s2ScenarioMessages() {
        s2.forEach { assertEquals(it.scenario, idOf(it.expectedKey), RouterErrorTexts.reasonRes(it.code)) }
    }

    @Test fun s2KnownCausesNeverUseTheGenericMessage() {
        val generic = R.string.router_test_failed_generic
        s2.forEach { assertNotEquals(it.scenario, generic, RouterErrorTexts.reasonRes(it.code)) }
    }

    @Test fun s2EveryCauseHasPracticalHint() {
        s2.forEach { assertNotNull(it.scenario, RouterErrorTexts.hintRes(it.code)) }
    }

    @Test fun everyApiErrorCodeHasAMessage() {
        val codes = ApiError::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.name.startsWith("CODE_") && it.type == String::class.java }
            .onEach { it.isAccessible = true }
            .map { it.get(null) as String }
        assertTrue("no CODE_ constants found", codes.size > 10)
        codes.forEach { code -> assertNotNull("لا رسالة للرمز $code", ErrorTranslator.resIdOrNull(code)) }
    }

    @Test fun s32ReadNamesExist() {
        listOf("um_read_packages", "um_read_limitations", "um_read_users", "um_read_sessions", "um_read_failed_fmt", "um_read_ok_fmt")
            .forEach { idOf(it) }
    }

    // ── العربية والإنجليزية ──
    private fun keys(path: String): Map<String, String> {
        val xml = listOf("src/main/res/$path", "app/src/main/res/$path").map(::File).first { it.exists() }.readText()
        return Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test fun newFailureAndConnectionStringsExistInBothLanguages() {
        val ar = keys("values/strings.xml")
        val en = keys("values-en-rUS/strings.xml")
        val required = (s1.map { it.expectedKey } + s2.map { it.expectedKey } + listOf(
            "router_hint_unreachable", "router_hint_port_closed", "router_hint_auth_failed", "router_hint_permission_denied",
            "router_hint_timeout", "router_hint_connection_failed", "router_hint_rest_unavailable",
            "startup_router_failed_title", "router_untested_save_message", "router_untested_save_cancel", "router_untested_save_confirm",
            "splash_router_timeout", "splash_router_failed", "splash_action_router_settings",
            "conn_notif_channel", "conn_notif_connected_title", "conn_notif_reconnecting_text", "conn_notif_disconnected_title",
            "conn_notice_unstable", "conn_notice_off_router", "conn_diag_title", "settings_notif_connection",
            "um_read_title", "um_read_packages", "um_read_limitations", "um_read_users", "um_read_sessions"
        )).distinct()
        required.forEach {
            assertTrue("ar: $it", !ar[it].isNullOrBlank())
            assertTrue("en: $it", !en[it].isNullOrBlank())
        }
    }
}

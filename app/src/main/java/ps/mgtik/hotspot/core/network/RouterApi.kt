package ps.mgtik.hotspot.core.network

import kotlinx.serialization.json.JsonObject

/**
 * رفض الراوتر لأمر بعينه (قيمة غير صالحة، عنصر مكرر، ...). يختلف عن [RouterApiException] الذي يمثل فشل
 * اتصال/مصادقة/خدمة. لا يُسقط الاتصال المخزَّن، ويحمل نص الراوتر كما وصل لتصنيفه عند المستدعي.
 */
class RouterCommandRejected(val routerMessage: String) : Exception(routerMessage)

/**
 * RouterApi - واجهة موحّدة لأوامر RouterOS بصيغة المسارات (مثل "/ip/hotspot/user")، منفصلة عن وسيلة النقل:
 * REST على RouterOS 7.1+، أو API الثنائي على RouterOS 6.x و7.x. طبقة البيانات لا تعرف أيهما يعمل.
 *
 * كل القيم المرجعة نصوص كما يعيدها RouterOS في الحالتين (مثل "cpu-load" = "3" و"running" = "true").
 */
interface RouterApi : AutoCloseable {
    val kind: RouterConnectionType
    /** المنفذ الفعلي المستعمل لهذا الاتصال. */
    val port: Int

    /** هل ما زال الاتصال صالحاً لإعادة الاستخدام؟ (المقبس المغلق لا يُعاد استعماله). */
    fun isUsable(): Boolean = true

    /** print: صفوف المسار (مسار مفرد مثل /system/resource يعيد صفاً واحداً). */
    suspend fun print(
        path: String,
        query: Map<String, String> = emptyMap(),
        props: List<String> = emptyList()
    ): List<JsonObject>

    /** add: إضافة عنصر. الرفض يرمي [RouterCommandRejected]. */
    suspend fun add(path: String, attrs: Map<String, Any?>)

    /** set: تعديل عنصر بالاسم أو بمعرّفه (.id يبدأ بـ *). */
    suspend fun set(path: String, ref: String, attrs: Map<String, Any?>)

    /**
     * set على مسار مفرد بلا عناصر (مثل /user-manager في RouterOS 7): تعديل إعداداته مباشرة دون .id.
     * REST: PATCH على المسار نفسه. API الثنائي: `<path>/set` بلا .id.
     */
    suspend fun setSingle(path: String, attrs: Map<String, Any?>) {
        throw UnsupportedOperationException("setSingle() is not supported by ${this::class.simpleName}")
    }

    /** remove: حذف عنصر بالاسم أو المعرّف. يعيد false إن لم يكن موجوداً أصلاً. */
    suspend fun remove(path: String, ref: String): Boolean

    /** أمر على المسار (مثل reset-counters على عنصر، أو reboot على /system بلا عنصر). */
    suspend fun action(path: String, action: String, ref: String? = null)

    /** ينفّذ أمراً كاملاً بخصائص (مثل create-and-activate-profile في UserMan v6): المسار هو المسار الكامل للأمر. */
    suspend fun command(path: String, attrs: Map<String, Any?> = emptyMap()) {
        throw UnsupportedOperationException("command() is not supported by ${this::class.simpleName}")
    }
}

/** صف واحد من مسار مفرد (مثل /system/resource)؛ يرمي استثناءً مصنَّفاً إن جاء الرد فارغاً. */
suspend fun RouterApi.printSingle(path: String): JsonObject =
    print(path).firstOrNull() ?: throw RouterApiException(
        ps.mgtik.hotspot.core.result.ApiError.httpError(200, "Empty router response for $path")
    )

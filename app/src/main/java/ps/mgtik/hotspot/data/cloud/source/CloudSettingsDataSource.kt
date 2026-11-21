package ps.mgtik.hotspot.data.cloud.source

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import ps.mgtik.hotspot.core.network.RouterConfig
import ps.mgtik.hotspot.core.network.RouterConnectionType
import ps.mgtik.hotspot.core.network.RouterPorts
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.safeApiCall
import ps.mgtik.hotspot.data.mapping.FirestoreDocFields
import ps.mgtik.hotspot.domain.model.NetworkCompanyInfo
import ps.mgtik.hotspot.domain.model.UserManConfig
import ps.mgtik.hotspot.domain.model.UserManMode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CloudSettingsDataSource - مصدر بيانات الإعدادات السحابية (§24)
 * تم الإصلاح: توحيد استخدام RouterConfig من core.network.
 */
@Singleton
class CloudSettingsDataSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val settingsDoc = firestore.collection("settings").document("company_info")
    private val routerDoc = firestore.collection("settings").document("router_config")
    private val userManDoc = firestore.collection("settings").document("userman_config")

    fun getCompanyInfo(): Flow<NetworkCompanyInfo> {
        return settingsDoc.snapshots().map { snap ->
            if (snap.exists()) {
                NetworkCompanyInfo(
                    companyName = snap.getString("company_name") ?: "",
                    networkName = snap.getString("network_name") ?: "",
                    logoUrl = snap.getString("logo_url") ?: "",
                    address = snap.getString("address") ?: "",
                    phone = snap.getString("phone") ?: "",
                    whatsapp = snap.getString("whatsapp") ?: "",
                    email = snap.getString("email") ?: "",
                    website = snap.getString("website") ?: "",
                    footerNotes = snap.getString("footer_notes") ?: "",
                    currencySymbol = snap.getString("currency_symbol") ?: "₪",
                    loginUrl = snap.getString("login_url") ?: ""
                )
            } else {
                NetworkCompanyInfo()
            }
        }
    }

    /**
     * يحفظ بيانات الشركة بنهج حفظ إعداد الراوتر نفسه ([writeConfirmed]): مهلة لكل خطوة، فحص وصول إلى الخادم قبل الكتابة،
     * ثم قراءة من الخادم ومطابقة كل الحقول. لا يبقى الحفظ معلّقاً دون اتصال، ولا يُعلن نجاحاً قبل رؤية القيم مخزَّنة.
     * merge: حقول الوثيقة غير المعروفة للنموذج (أو المضافة لاحقاً) لا تُمحى عند الحفظ.
     */
    suspend fun saveCompanyInfo(info: NetworkCompanyInfo, idempotencyKey: String): AppResult<Unit> = safeApiCall {
        val data = HashMap<String, Any?>(CompanyInfoWire.fields(info)).apply {
            put("idempotency_key", idempotencyKey)
            put("updated_at", com.google.firebase.Timestamp.now())
        }
        writeConfirmed(settingsDoc, data, merge = true, what = "company info") { stored ->
            CompanyInfoWire.matches(FirestoreDocFields(stored), info)
        }
    }

    /** اختبار حقيقي لوصول Firestore: قراءة من الخادم نفسه (لا الكاش) بمهلة؛ يعيد زمن الاستجابة بالمللي ثانية. */
    suspend fun pingCloud(): AppResult<Long> = safeApiCall {
        val start = System.currentTimeMillis()
        withTimeoutOrNull(SAVE_STEP_TIMEOUT_MS) { settingsDoc.get(Source.SERVER).await() }
            ?: throw java.util.concurrent.TimeoutException("Cloud unreachable")
        System.currentTimeMillis() - start
    }

    fun getRouterConfig(): Flow<RouterConfig> {
        return routerDoc.snapshots().map { snap ->
            if (snap.exists()) {
                val useSsl = snap.getBoolean("use_ssl") ?: false
                RouterConfig(
                    host = snap.getString("host") ?: "",
                    port = snap.getLong("port")?.toInt() ?: RouterPorts.defaultFor(useSsl),
                    username = snap.getString("username") ?: "admin",
                    password = snap.getString("password") ?: "",
                    useSsl = useSsl,
                    isConnected = snap.getBoolean("is_connected") ?: false,
                    apiPort = snap.getLong("api_port")?.toInt() ?: RouterPorts.apiDefaultFor(useSsl),
                    // مستندات قديمة بلا الحقل = AUTO (REST ثم API)
                    connectionType = snap.getString("connection_type")
                        ?.let { name -> RouterConnectionType.values().firstOrNull { it.name == name } }
                        ?: RouterConnectionType.AUTO
                )
            } else {
                // لم يُحفظ أي إعداد بعد: host فارغ يعني «غير مُهيّأ» ويوجّه السبلاش إلى S2
                RouterConfig(host = "")
            }
        }
    }

    /**
     * يحفظ إعداد الراوتر ويتحقق من استرجاعه فعلاً من الخادم.
     *
     * - `set().await()` على Firestore لا يكتمل إلا بتأكيد الخادم، فيعلّق بلا نهاية دون اتصال؛ لذلك كل خطوة بمهلة،
     *   و`withTimeoutOrNull` (لا `withTimeout`: استثناؤه CancellationException فيُبتلع ويبقى «جاري الحفظ» للأبد).
     * - فحص وصول إلى الخادم قبل الكتابة: دون اتصال لا تُترك كتابة معلّقة تُطبَّق لاحقاً بعد أن أُعلن الفشل.
     * - بعد الكتابة تُقرأ الوثيقة من الخادم وتُطابَق الحقول: نجاح الحفظ = قابلية الاسترجاع بعد إعادة التشغيل.
     * فشل أي خطوة قبل الكتابة يترك الإعداد السابق كما هو.
     */
    suspend fun saveRouterConfig(config: RouterConfig, idempotencyKey: String): AppResult<Unit> = safeApiCall {
        val data = hashMapOf(
            "idempotency_key" to idempotencyKey,
            "host" to config.host,
            "port" to config.port,
            "username" to config.username,
            "password" to config.password,
            "use_ssl" to config.useSsl,
            "api_port" to config.apiPort,
            "connection_type" to config.connectionType.name,
            "updated_at" to com.google.firebase.Timestamp.now()
        )
        writeConfirmed(routerDoc, data, merge = false, what = "router config") { stored ->
            stored.getString("host") == config.host &&
                stored.getLong("port")?.toInt() == config.port &&
                stored.getLong("api_port")?.toInt() == config.apiPort &&
                stored.getString("username") == config.username &&
                stored.getString("password") == config.password &&
                stored.getBoolean("use_ssl") == config.useSsl &&
                stored.getString("connection_type") == config.connectionType.name
        }
    }

    /** إعداد User Manager: وثيقة settings/userman_config. غياب الوثيقة أو أي حقل ← قيمه الافتراضية. */
    fun getUserManConfig(): Flow<UserManConfig> = userManDoc.snapshots().map { snap ->
        if (!snap.exists()) UserManConfig()
        else UserManConfig(
            mode = snap.getString("mode")?.let { n -> UserManMode.values().firstOrNull { it.name == n } } ?: UserManMode.AUTO,
            customer = snap.getString("customer")?.takeIf { it.isNotBlank() } ?: UserManConfig.DEFAULT_CUSTOMER,
            radiusAddress = snap.getString("radius_address")?.takeIf { it.isNotBlank() } ?: UserManConfig.DEFAULT_RADIUS_ADDRESS,
            radiusSecret = snap.getString("radius_secret") ?: "",
            nasName = snap.getString("nas_name")?.takeIf { it.isNotBlank() } ?: UserManConfig.DEFAULT_NAS_NAME
        )
    }

    /** نفس نهج حفظ إعداد الراوتر: مهلة لكل خطوة، فحص وصول قبل الكتابة، ثم قراءة من الخادم ومطابقة الحقول. */
    suspend fun saveUserManConfig(config: UserManConfig): AppResult<Unit> = safeApiCall {
        val data = hashMapOf(
            "mode" to config.mode.name,
            "customer" to config.customer,
            "radius_address" to config.radiusAddress,
            "radius_secret" to config.radiusSecret,
            "nas_name" to config.nasName,
            "updated_at" to com.google.firebase.Timestamp.now()
        )
        writeConfirmed(userManDoc, data, merge = false, what = "user-manager config") { stored ->
            stored.getString("mode") == config.mode.name &&
                stored.getString("customer") == config.customer &&
                stored.getString("radius_address") == config.radiusAddress &&
                stored.getString("radius_secret") == config.radiusSecret &&
                stored.getString("nas_name") == config.nasName
        }
    }

    /**
     * كتابة مؤكَّدة لوثيقة إعدادات (المنهج الوحيد لكل وثائق مجموعة settings):
     * - `set().await()` على Firestore لا يكتمل إلا بتأكيد الخادم، فيعلّق بلا نهاية دون اتصال؛ لذلك كل خطوة بمهلة،
     *   و`withTimeoutOrNull` (لا `withTimeout`: استثناؤه CancellationException فيُبتلع ويبقى «جاري الحفظ» للأبد).
     * - فحص وصول إلى الخادم قبل الكتابة: دون اتصال لا تُترك كتابة معلّقة تُطبَّق لاحقاً بعد أن أُعلن الفشل.
     * - بعد الكتابة تُقرأ الوثيقة من الخادم ويُطابَق ما خُزِّن عبر [verify]: نجاح الحفظ = قابلية الاسترجاع بعد إعادة التشغيل.
     * فشل أي خطوة قبل الكتابة يترك الإعداد السابق كما هو. الأخطاء تُرمى لتصنّفها safeApiCall (مهلة ← ERR_TIMEOUT، عدم تطابق ← رمز التحقق).
     */
    private suspend fun writeConfirmed(
        ref: DocumentReference,
        data: Map<String, Any?>,
        merge: Boolean,
        what: String,
        verify: (DocumentSnapshot) -> Boolean
    ) {
        withTimeoutOrNull(SAVE_STEP_TIMEOUT_MS) { ref.get(Source.SERVER).await() }
            ?: throw java.util.concurrent.TimeoutException("Cloud unreachable before saving $what")
        withTimeoutOrNull(SAVE_STEP_TIMEOUT_MS) {
            (if (merge) ref.set(data, SetOptions.merge()) else ref.set(data)).await()
        } ?: throw java.util.concurrent.TimeoutException("Cloud did not confirm $what write")
        val stored = withTimeoutOrNull(SAVE_STEP_TIMEOUT_MS) { ref.get(Source.SERVER).await() }
            ?: throw java.util.concurrent.TimeoutException("Cloud did not return the saved $what")
        if (!stored.exists() || !verify(stored)) throw IllegalStateException(ERR_SETTINGS_VERIFY_FAILED)
    }

    companion object {
        private const val SAVE_STEP_TIMEOUT_MS = 10_000L
        /** رمز واحد لعدم تأكّد أي حفظ إعدادات (النص المعروض في ErrorTranslator عام لكل الإعدادات). */
        const val ERR_SETTINGS_VERIFY_FAILED = "ERR_ROUTER_CONFIG_VERIFY_FAILED"
    }
}

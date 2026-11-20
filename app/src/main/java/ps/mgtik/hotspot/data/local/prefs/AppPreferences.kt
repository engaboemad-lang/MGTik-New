package ps.mgtik.hotspot.data.local.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.OutputSettings
import ps.mgtik.hotspot.domain.model.PageSize
import javax.inject.Inject
import javax.inject.Singleton

/** مظهر التطبيق. SYSTEM يتبع إعداد الجهاز. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** لغة واجهة التطبيق. SYSTEM يتبع لغة الجهاز. */
enum class AppLanguage(val tag: String) { SYSTEM("system"), AR("ar"), EN("en") }

/**
 * AppPreferences - المخزن المحلي المركزي الوحيد لتفضيلات الجهاز (SharedPreferences).
 *
 * يملك: المظهر، اللغة، القالب الافتراضي، إعدادات الطباعة/التصدير، تفعيل أنواع الإشعارات.
 * لا يملك: إعدادات الشركة والعملة والراوتر (Cloud/Firestore) ولا حالات التشغيل المؤقتة (RouterStateManager).
 * القيم الافتراضية معرّفة هنا فقط ([DEFAULT_OUTPUT] وبقية الثوابت).
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init { migrateLegacyExportPrefs(context) }

    /**
     * آخر حساب سحابي دخل هذا الجهاز (uid). يبقى بعد الخروج عمداً: كاش البيانات لا يُمسح عند الخروج، بل فقط إن دخل حساب مختلف
     * (فلا يرى حسابٌ بيانات غيره). لا يحمل أي سر.
     */
    fun lastSignedInUid(): String? = prefs.getString(KEY_LAST_UID, null)
    fun setLastSignedInUid(uid: String) { prefs.edit().putString(KEY_LAST_UID, uid).apply() }

    /**
     * آخر إعدادات توليد مستعملة (نص GenerationDefaults.encode): لكل باقة، و[profileId] = null ⇒ آخر إعدادات عامة.
     * محلية للجهاز ولا تمسّ الباقة؛ لا تحمل أسراراً (لا وكيل ولا كلمات مرور).
     */
    fun generationDefaults(profileId: String?): String? = prefs.getString(genKey(profileId), null)
    fun setGenerationDefaults(profileId: String?, encoded: String) { prefs.edit().putString(genKey(profileId), encoded).apply() }
    private fun genKey(profileId: String?) = if (profileId == null) KEY_GEN_LAST else KEY_GEN_PREFIX + profileId

    private val _themeMode = MutableStateFlow(readEnum(KEY_THEME, ThemeMode.SYSTEM))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _language = MutableStateFlow(readLanguage(prefs))
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    private val _defaultTemplateId = MutableStateFlow(prefs.getString(KEY_DEFAULT_TEMPLATE, null))
    val defaultTemplateId: StateFlow<String?> = _defaultTemplateId.asStateFlow()

    private val _outputSettings = MutableStateFlow(readOutput())
    val outputSettings: StateFlow<OutputSettings> = _outputSettings.asStateFlow()

    private val _notifyBilling = MutableStateFlow(prefs.getBoolean(KEY_NOTIFY_BILLING, true))
    val notifyBilling: StateFlow<Boolean> = _notifyBilling.asStateFlow()

    private val _notifyGeneration = MutableStateFlow(prefs.getBoolean(KEY_NOTIFY_GENERATION, true))
    val notifyGeneration: StateFlow<Boolean> = _notifyGeneration.asStateFlow()

    /** الإشعار الدائم لحالة الاتصال: معطّل افتراضياً ويُفعَّل بخيار صريح فقط. */
    private val _connectionNotification = MutableStateFlow(prefs.getBoolean(KEY_CONNECTION_NOTIFICATION, false))
    val connectionNotification: StateFlow<Boolean> = _connectionNotification.asStateFlow()

    fun setConnectionNotification(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONNECTION_NOTIFICATION, enabled).apply()
        _connectionNotification.value = enabled
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    fun setLanguage(language: AppLanguage) {
        // commit(): اللغة تُقرأ في attachBaseContext بعد إعادة إنشاء الـ Activity مباشرة
        prefs.edit().putString(KEY_LANGUAGE, language.tag).commit()
        _language.value = language
    }

    fun setDefaultTemplateId(templateId: String?) {
        prefs.edit().apply { if (templateId == null) remove(KEY_DEFAULT_TEMPLATE) else putString(KEY_DEFAULT_TEMPLATE, templateId) }.apply()
        _defaultTemplateId.value = templateId
    }

    /** يتحقق من القيم قبل الحفظ؛ الإعداد السابق يبقى كما هو عند الرفض. */
    fun saveOutputSettings(settings: OutputSettings): AppResult<Unit> {
        val m = settings.margins
        val valid = settings.columns in MIN_COLUMNS..MAX_COLUMNS &&
            settings.columnGapPt >= 0 && settings.rowGapPt >= 0 &&
            m.top >= 0 && m.right >= 0 && m.bottom >= 0 && m.left >= 0 &&
            settings.page.widthPt > 0 && settings.page.heightPt > 0
        if (!valid) return AppResult.Error(ApiError.validationError("Invalid print settings"))
        prefs.edit().putString(KEY_OUTPUT, json.encodeToString(OutputSettings.serializer(), settings)).apply()
        _outputSettings.value = settings
        return AppResult.Success(Unit)
    }

    fun resetOutputSettings() {
        prefs.edit().remove(KEY_OUTPUT).apply()
        _outputSettings.value = DEFAULT_OUTPUT
    }

    fun setNotifyBilling(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY_BILLING, enabled).apply()
        _notifyBilling.value = enabled
    }

    fun setNotifyGeneration(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY_GENERATION, enabled).apply()
        _notifyGeneration.value = enabled
    }

    /** استعادة الافتراضيات المحلية (المظهر/اللغة/الإشعارات/الطباعة). القالب الافتراضي لا يُمس. */
    fun resetToDefaults() {
        setThemeMode(ThemeMode.SYSTEM)
        setLanguage(AppLanguage.SYSTEM)
        setNotifyBilling(true)
        setNotifyGeneration(true)
        setConnectionNotification(false)
        resetOutputSettings()
    }

    private fun readOutput(): OutputSettings =
        prefs.getString(KEY_OUTPUT, null)
            ?.let { runCatching { json.decodeFromString<OutputSettings>(it) }.getOrNull() }
            ?: DEFAULT_OUTPUT

    private inline fun <reified E : Enum<E>> readEnum(key: String, default: E): E =
        prefs.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    /** نقل مرة واحدة من ملف التخزين القديم الذي كان يملكه TemplateRepositoryImpl، ثم حذف المفاتيح القديمة. */
    private fun migrateLegacyExportPrefs(context: Context) {
        val legacy = context.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) return
        val edit = prefs.edit()
        legacy.getString(KEY_DEFAULT_TEMPLATE, null)?.let { if (!prefs.contains(KEY_DEFAULT_TEMPLATE)) edit.putString(KEY_DEFAULT_TEMPLATE, it) }
        legacy.getString(LEGACY_KEY_OUTPUT, null)?.let { if (!prefs.contains(KEY_OUTPUT)) edit.putString(KEY_OUTPUT, it) }
        edit.commit()
        legacy.edit().clear().commit()
    }

    companion object {
        const val FILE = "mgtik_app_prefs"
        private const val LEGACY_FILE = "mgtik_export"
        private const val LEGACY_KEY_OUTPUT = "output_settings"
        private const val KEY_OUTPUT = "output_settings"
        private const val KEY_DEFAULT_TEMPLATE = "default_template_id"
        private const val KEY_LAST_UID = "last_signed_in_uid"
        private const val KEY_GEN_LAST = "gen_defaults_last"
        private const val KEY_GEN_PREFIX = "gen_defaults_p_"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_NOTIFY_BILLING = "notify_billing"
        private const val KEY_NOTIFY_GENERATION = "notify_generation"
        private const val KEY_CONNECTION_NOTIFICATION = "notify_connection_persistent"
        const val MIN_COLUMNS = 1
        const val MAX_COLUMNS = ps.mgtik.hotspot.domain.export.LayoutPlanner.MAX_COLUMNS

        /**
         * القيمة الافتراضية الوحيدة لإعدادات الطباعة: عمودان على A4 (هذا ما كانت تعرضه الواجهة فعلياً عند أول تشغيل).
         * القيمة columns = 1 داخل OutputSettings تبقى فقط لفك ترميز JSON قديم حُفظ دون المفتاح؛ لا تُستعمل افتراضياً.
         */
        val DEFAULT_OUTPUT = OutputSettings(PageSize.A4, columns = 2)

        /** تُستدعى من attachBaseContext قبل حقن Hilt، لذلك تقرأ الملف مباشرة. */
        fun readLanguage(prefs: android.content.SharedPreferences): AppLanguage =
            prefs.getString(KEY_LANGUAGE, null)?.let { tag -> AppLanguage.values().firstOrNull { it.tag == tag } } ?: AppLanguage.SYSTEM

        fun readLanguage(context: Context): AppLanguage =
            readLanguage(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}

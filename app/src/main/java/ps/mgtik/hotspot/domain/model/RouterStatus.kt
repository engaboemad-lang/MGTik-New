package ps.mgtik.hotspot.domain.model

/**
 * حالات اتصال التطبيق بالراوتر (مصدر وحيد للحقيقة لكل الشاشات).
 * هذه حالة اتصال الهاتف بـ RouterOS مباشرة؛ أما قراءة/حفظ الإعدادات من/إلى السحابة (Firestore) فتُعرض منفصلة في
 * [ConfigLoadState] كي لا يُفسَّر عطل السحابة على أنه عطل في الراوتر أو العكس.
 */
enum class RouterConnectionState {
    /** لم يُحفظ إعداد اتصال بعد. */
    NOT_CONFIGURED,
    /** الراوتر غير متصل (انقطاع بعد اتصال ناجح، أو لم يُجرَّ اتصال بعد). */
    DISCONNECTED,
    /** محاولة اتصال جارية. */
    CONNECTING,
    CONNECTED,
    /** فشلت محاولة اتصال طلبها التطبيق أو المستخدم. */
    CONNECTION_FAILED,
    /** تجري محاولة استعادة اتصال كان سليماً. */
    RECONNECTING,
    /** يجري فحص إعدادات جديدة أو حفظها. */
    CONFIGURING
}

/** سبب انقطاع الاتصال (يُعرض مختلفاً: شبكة الهاتف ≠ الراوتر ≠ إعادة تشغيل ≠ خارج شبكة الراوتر). */
enum class RouterInterruption { NETWORK_LOST, ROUTER_UNREACHABLE, ROUTER_REBOOTED, OFF_ROUTER_NETWORK }

/** جودة الاتصال المحسوبة من نافذة نبضات (لا من نبضة واحدة). */
enum class LinkQualityLevel { GOOD, SLOW, INTERMITTENT }

/** حدث في سجل الاتصال التشخيصي (آخر 50). لا يحمل أي بيانات حساسة. */
data class ConnectionLogEntry(
    val atMillis: Long,
    val state: RouterConnectionState,
    val reasonCode: String?,
    /** مدة الاتصال/الانقطاع الذي انتهى بهذا الحدث (ms). */
    val durationMs: Long?,
    /** نتيجة محاولة إعادة الاتصال: "ok" أو "fail". */
    val reconnectResult: String?
)

/** حالة تحميل إعدادات الاتصال المحفوظة من السحابة. */
enum class ConfigLoadState { LOADING, LOADED, UNAVAILABLE }

/**
 * RouterStatus - لقطة الحالة المركزية للراوتر.
 *
 * @property attentionRequired يتطلب قراراً من المستخدم (إعادة محاولة / ضبط الإعدادات / تخطي)؛ مفعّل مرة واحدة لكل انقطاع
 *   فلا تتراكم تنبيهات، ويُصفَّر عند نجاح الاتصال أو التخطي.
 * @property offlineMode المستخدم اختار تخطي الاتصال: التطبيق يعمل دون الوظائف التي تتطلب الراوتر.
 */
data class RouterStatus(
    val state: RouterConnectionState = RouterConnectionState.DISCONNECTED,
    val config: RouterConfig = RouterConfig(host = ""),
    val configLoad: ConfigLoadState = ConfigLoadState.LOADING,
    val info: RouterInfo? = null,
    val health: RouterHealth = RouterHealth(),
    /** رمز آخر فشل (ApiError.code) عند توفره؛ null إن لم يوجد سبب موثوق. */
    val errorCode: String? = null,
    val attentionRequired: Boolean = false,
    val offlineMode: Boolean = false,
    val lastCheckedAtMillis: Long = 0L,
    /**
     * المستخدم سجّل الخروج من الراوتر صراحةً: الجلسة مفصولة والإعدادات محفوظة، ولا اتصال تلقائي حتى يطلبه (إعادة محاولة/حفظ).
     * مختلف عن الانقطاع (لا تنبيه ولا حوار قرار) وعن «تخطي الاتصال» [offlineMode].
     */
    val loggedOut: Boolean = false,
    /** بداية الاتصال الحالي بـ SystemClock.elapsedRealtime (لا تتأثر بتغيير ساعة الهاتف)؛ null = غير متصل. */
    val connectedSince: Long? = null,
    /** وقت آخر انقطاع مؤكَّد بـ elapsedRealtime؛ null = لم يحدث. */
    val lastDisconnectAt: Long? = null,
    /** عدد مرات التعافي/إعادة الاتصال في هذه الجلسة. */
    val reconnectCount: Int = 0,
    val interruption: RouterInterruption? = null,
    /** تذبذب متكرر (انقطاع وعودة): تظهر رسالة «الاتصال غير مستقر» مرة واحدة. */
    val unstable: Boolean = false,
    /** رقم محاولة إعادة الاتصال الجارية (0 = لا محاولات). */
    val reconnectAttempt: Int = 0,
    /** موعد المحاولة التالية بـ elapsedRealtime؛ null إن لم تُجدول. */
    val nextRetryAt: Long? = null,
    val rttMs: Long? = null,
    val quality: LinkQualityLevel? = null
) {
    val isConnected: Boolean get() = state == RouterConnectionState.CONNECTED
    val isConfigured: Boolean get() = config.host.isNotBlank()
    /** إعدادات الاتصال لم تُقرأ من السحابة (عطل سحابة/شبكة) وليست «غير مُهيّأة». */
    val isCloudConfigUnavailable: Boolean get() = configLoad == ConfigLoadState.UNAVAILABLE
    /** الحالة تنتظر نتيجة (لا تُعرض كفشل ولا كنجاح). */
    val isBusy: Boolean get() = state == RouterConnectionState.CONNECTING ||
        state == RouterConnectionState.RECONNECTING ||
        state == RouterConnectionState.CONFIGURING
}

/** نتيجة حفظ الإعدادات ثم اختبار الاتصال بها؛ نجاح الحفظ وحده لا يعني نجاح الاتصال. */
sealed interface RouterSaveOutcome {
    /** لم يُحفظ شيء؛ الإعداد السابق سليم كما كان. */
    data class SaveFailed(val error: ps.mgtik.hotspot.core.result.ApiError) : RouterSaveOutcome
    data class SavedConnected(val info: RouterInfo) : RouterSaveOutcome
    /** حُفظ الإعداد لكن الاتصال به فشل. */
    data class SavedNotConnected(val error: ps.mgtik.hotspot.core.result.ApiError) : RouterSaveOutcome
}

sealed interface RouterStateEvent {
    /** اكتُشفت إعادة تشغيل الراوتر (انخفض uptime بين نبضتين). */
    data object RouterRebooted : RouterStateEvent

    /** عاد الاتصال بعد انقطاع (وليس أول اتصال). */
    data object Reconnected : RouterStateEvent
}

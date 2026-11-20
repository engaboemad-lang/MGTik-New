package ps.mgtik.hotspot.domain.model

/**
 * حالة الراوتر كما تُعرض في لوحة المعلومات (S3).
 * المصدر الوحيد: [RouterStatus] المركزية (اتصال RouterOS الفعلي) — لا علاقة لها بتدفقات Firestore.
 */
enum class RouterLinkState {
    /** لا إعداد اتصال محفوظ للراوتر. */
    NOT_CONFIGURED,
    /** محاولة اتصال/إعادة اتصال/تحميل إعدادات جارية: النتيجة النهائية غير معروفة بعد. */
    CONNECTING,
    CONNECTED,
    DISCONNECTED
}

/**
 * لقطة عرض لحالة الراوتر. حقول القياس والمعلومات nullable عمداً: null = «غير متاح» وليس صفراً.
 * لا تُملأ إلا مع اتصال حقيقي ناجح، فلا يُعرض CPU=0 أو ذاكرة=0 عند الانقطاع أو أثناء الفحص.
 */
data class RouterOverview(
    val link: RouterLinkState = RouterLinkState.CONNECTING,
    /** موديل الراوتر (routerboard model أو board-name)؛ null إن لم يُقرأ. */
    val model: String? = null,
    /** عنوان الراوتر المُهيَّأ (يُعرض مستقلاً عن الموديل). */
    val host: String = "",
    /** إصدار RouterOS كما قرأه الاتصال الناجح؛ null إن لم يتوفر. */
    val osVersion: String? = null,
    val cpuLoadPercent: Int? = null,
    val freeMemoryMb: Long? = null
)

/** يشتق لقطة العرض من الحالة المركزية للراوتر دون أي مصدر آخر. */
fun RouterStatus.toOverview(): RouterOverview {
    val link = when {
        isConnected -> RouterLinkState.CONNECTED
        // اتصال/إعادة اتصال/ضبط جارٍ، أو إعدادات السحابة قيد التحميل: لا تُقرأ كانقطاع
        isBusy || configLoad == ConfigLoadState.LOADING -> RouterLinkState.CONNECTING
        // تعذّر قراءة الإعداد من السحابة ليس «غير مُهيّأ» (انظر ConfigLoadState.UNAVAILABLE)
        state == RouterConnectionState.NOT_CONFIGURED ||
            (!isConfigured && configLoad == ConfigLoadState.LOADED) -> RouterLinkState.NOT_CONFIGURED
        else -> RouterLinkState.DISCONNECTED
    }
    val live = link == RouterLinkState.CONNECTED
    // القياسات من نبضة/اتصال ناجح فقط؛ health.isOnline يمنع قراءة قيم افتراضية قبل أول قياس
    val measured = live && health.isOnline
    return RouterOverview(
        link = link,
        model = if (live) info?.name?.trim()?.takeIf { it.isNotEmpty() } else null,
        host = config.host.trim(),
        osVersion = if (live) info?.osMajor?.trim()?.takeIf { it.isNotEmpty() } else null,
        cpuLoadPercent = if (measured) health.cpuLoadPercent else null,
        freeMemoryMb = if (measured) health.freeMemoryMb else null
    )
}

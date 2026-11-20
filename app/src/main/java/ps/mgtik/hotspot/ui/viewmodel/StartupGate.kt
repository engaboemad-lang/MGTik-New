package ps.mgtik.hotspot.ui.viewmodel

import ps.mgtik.hotspot.domain.model.ConfigLoadState
import ps.mgtik.hotspot.domain.model.RouterConnectionState

/** نتيجة فحص User Manager ضمن سلسلة الإقلاع (UNKNOWN = لم يُفحص بعد في الاتصال الحالي). */
enum class StartupUserMan { UNKNOWN, READY, NOT_READY }

/**
 * مرحلة سلسلة الإقلاع بعد نجاح مصادقة Firebase:
 * CHECKING (قيد الفحص) ← ROUTER_FAILED (S2) أو USERMAN_NOT_READY (S32) أو READY (S3).
 */
enum class StartupStage { CHECKING, ROUTER_FAILED, USERMAN_NOT_READY, READY }

/**
 * دالة نقية تشتق مرحلة الإقلاع من الحالة المركزية للراوتر ونتيجة فحص User Manager.
 * - [offlineMode]: قرار صريح من المستخدم («متابعة دون اتصال») فيُعدّ الفحص منتهياً.
 * - DISCONNECTED بلا رمز خطأ ولا طلب انتباه = لم تبدأ المحاولة بعد (CHECKING) وليس فشلاً.
 * - لا تُخفي فشل الاتصال ولا عدم جاهزية User Manager.
 */
internal fun deriveStartupStage(
    configLoad: ConfigLoadState,
    host: String,
    state: RouterConnectionState,
    errorCode: String?,
    attentionRequired: Boolean,
    loggedOut: Boolean,
    offlineMode: Boolean,
    userMan: StartupUserMan
): StartupStage = when {
    offlineMode -> StartupStage.READY
    configLoad == ConfigLoadState.LOADING -> StartupStage.CHECKING
    configLoad == ConfigLoadState.UNAVAILABLE -> StartupStage.ROUTER_FAILED
    host.isBlank() -> StartupStage.READY // غير مهيأ: resolveStartRoute يوجّه إلى S2
    else -> when (state) {
        RouterConnectionState.NOT_CONFIGURED -> StartupStage.READY
        RouterConnectionState.CONNECTING,
        RouterConnectionState.RECONNECTING,
        RouterConnectionState.CONFIGURING -> StartupStage.CHECKING
        RouterConnectionState.CONNECTION_FAILED -> StartupStage.ROUTER_FAILED
        RouterConnectionState.DISCONNECTED ->
            if (errorCode != null || attentionRequired || loggedOut) StartupStage.ROUTER_FAILED else StartupStage.CHECKING
        RouterConnectionState.CONNECTED -> when (userMan) {
            StartupUserMan.UNKNOWN -> StartupStage.CHECKING
            StartupUserMan.READY -> StartupStage.READY
            StartupUserMan.NOT_READY -> StartupStage.USERMAN_NOT_READY
        }
    }
}

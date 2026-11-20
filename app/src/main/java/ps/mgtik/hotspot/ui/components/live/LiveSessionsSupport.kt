package ps.mgtik.hotspot.ui.components.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.ui.viewmodel.LiveDataSource
import ps.mgtik.hotspot.ui.viewmodel.LiveSessionsUiState
import ps.mgtik.hotspot.ui.viewmodel.LiveSessionsViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * يشغّل التحديث الدوري للجلسات الحية ما دامت الشاشة مرئية فقط (ON_START..ON_STOP): الخروج منها أو ذهاب التطبيق
 * للخلفية يوقف الدورة والطلب الجاري (توفير بطارية وبيانات). الاستحواذ معدود فتتشارك شاشتان المصدر نفسه بلا تعارض.
 */
@Composable
fun LiveSessionsPolling(viewModel: LiveSessionsViewModel) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, viewModel) {
        var held = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!held) { held = true; viewModel.acquire() }
                Lifecycle.Event.ON_STOP -> if (held) { held = false; viewModel.release() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (held) viewModel.release()
        }
    }
}

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

fun formatClock(millis: Long): String = TIME_FMT.format(Instant.ofEpochMilli(millis))

/** مدة جلسة للعرض: hh:mm:ss، ومع أيام «1d 02:03:04»؛ null = غير متاحة (لا صفر مضلل). */
fun formatSessionDuration(seconds: Long?): String {
    if (seconds == null) return "—"
    val d = seconds / 86_400
    val h = seconds % 86_400 / 3_600
    val m = seconds % 3_600 / 60
    val s = seconds % 60
    val clock = String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s)
    return if (d > 0) "${d}d $clock" else clock
}

/** نص حالة موثوقية البيانات لبطاقة/شاشة: مباشر + وقت، أو مخزنة + وقت آخر نجاح، أو سبب الفشل. */
@Composable
fun liveStatusText(state: LiveSessionsUiState): String = when {
    state.source == LiveDataSource.LIVE && state.lastSuccessAt != null ->
        stringResource(R.string.live_status_live_fmt, formatClock(state.lastSuccessAt))
    state.source == LiveDataSource.CACHED && state.lastSuccessAt != null ->
        stringResource(R.string.live_status_cached_fmt, formatClock(state.lastSuccessAt))
    state.errorResId != null ->
        stringResource(if (state.errorIsConnectivity) R.string.live_err_router else R.string.live_err_data)
    else -> stringResource(R.string.common_loading)
}

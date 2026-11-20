package ps.mgtik.hotspot.ui.components.router

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.model.ConfigLoadState
import ps.mgtik.hotspot.domain.model.RouterConnectionState
import ps.mgtik.hotspot.domain.model.RouterStatus
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.theme.LocalMgtikExtendedColors
import ps.mgtik.hotspot.ui.util.RouterErrorTexts

/** هل يجب إظهار حوار القرار الإلزامي (إعادة المحاولة / ضبط الإعدادات / تخطي الاتصال)؟ */
fun RouterStatus.needsDecisionDialog(): Boolean =
    attentionRequired && !offlineMode &&
        (state == RouterConnectionState.DISCONNECTED || state == RouterConnectionState.CONNECTION_FAILED ||
            state == RouterConnectionState.RECONNECTING)

/** هل يظهر شريط الحالة؟ (لا يظهر أثناء تحميل أول إعداد حتى لا يومض «غير متصل» قبل أن نعرف شيئاً). */
fun RouterStatus.shouldShowBanner(): Boolean = configLoad != ConfigLoadState.LOADING

/**
 * شريط حالة الراوتر الموحّد: مكوّن واحد لكل التطبيق (لا تنبيه اتصال خاص بكل شاشة).
 * يعرض معلومات الراوتر عند النجاح، وسبب الانقطاع فقط عند توفر رمز موثوق، ووضع «غير متصل» مع الوظائف المعطّلة.
 */
@Composable
fun RouterStatusBanner(
    status: RouterStatus,
    onRetry: () -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
    /** يفتح شاشة تشخيص الاتصال (S35)؛ null = لا زر تفاصيل. */
    onOpenDiagnostics: (() -> Unit)? = null
) {
    val extended = LocalMgtikExtendedColors.current
    val colors = MaterialTheme.colorScheme

    data class Look(val container: androidx.compose.ui.graphics.Color, val content: androidx.compose.ui.graphics.Color)

    val look = when (status.state) {
        RouterConnectionState.CONNECTED -> Look(colors.surfaceVariant, extended.success)
        RouterConnectionState.CONNECTING,
        RouterConnectionState.RECONNECTING,
        RouterConnectionState.CONFIGURING -> Look(colors.secondaryContainer, colors.onSecondaryContainer)
        RouterConnectionState.NOT_CONFIGURED -> Look(colors.tertiaryContainer, colors.onTertiaryContainer)
        RouterConnectionState.DISCONNECTED,
        RouterConnectionState.CONNECTION_FAILED -> Look(colors.errorContainer, colors.onErrorContainer)
    }

    val title: String = when (status.state) {
        RouterConnectionState.CONNECTED -> stringResource(
            R.string.router_status_connected_fmt,
            status.info?.name ?: "MikroTik",
            status.config.host
        )
        RouterConnectionState.CONNECTING -> stringResource(R.string.router_status_connecting)
        RouterConnectionState.RECONNECTING -> stringResource(R.string.router_status_reconnecting)
        RouterConnectionState.CONFIGURING -> stringResource(R.string.router_status_configuring)
        RouterConnectionState.NOT_CONFIGURED -> stringResource(R.string.router_status_not_configured)
        RouterConnectionState.DISCONNECTED -> stringResource(if (status.loggedOut) R.string.router_status_logged_out else R.string.router_status_disconnected)
        RouterConnectionState.CONNECTION_FAILED -> stringResource(R.string.router_status_failed)
    }

    Surface(color = look.container, contentColor = look.content, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = Dimens.ScreenHorizontalPadding, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXXS)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status.isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = look.content)
                    Spacer(Modifier.width(Dimens.SpaceSM))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                val failed = status.state == RouterConnectionState.DISCONNECTED ||
                    status.state == RouterConnectionState.CONNECTION_FAILED
                if (onOpenDiagnostics != null && (failed || status.state == RouterConnectionState.RECONNECTING || status.unstable)) {
                    TextButton(onClick = onOpenDiagnostics) { Text(stringResource(R.string.conn_action_details)) }
                }
                if (failed || status.state == RouterConnectionState.NOT_CONFIGURED) {
                    if (failed) {
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.router_action_retry_connection)) }
                    }
                    TextButton(onClick = onConfigure) { Text(stringResource(R.string.router_action_configure)) }
                }
            }
            if (status.isCloudConfigUnavailable) {
                Text(stringResource(R.string.router_status_cloud_unavailable), style = MaterialTheme.typography.labelSmall)
            } else if (status.state == RouterConnectionState.DISCONNECTED || status.state == RouterConnectionState.CONNECTION_FAILED) {
                // سبب موثوق فقط: لا يُعرض نص تخمين عند غياب الرمز
                status.errorCode?.let {
                    Text(
                        stringResource(R.string.router_alert_reason_fmt, stringResource(RouterErrorTexts.reasonRes(it))),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            if (status.offlineMode) {
                Text(stringResource(R.string.router_status_offline_mode), style = MaterialTheme.typography.labelSmall)
            } else {
                // مدة الاتصال/آخر انقطاع/إعادة الاتصال/التنبيهات: نفس مصدر الإشعار الدائم فلا تناقض بينهما
                ConnectionStatusLines(status)
            }
        }
    }
}

@Composable
private fun durationText(d: DurationParts): String = when {
    d.days > 0 -> stringResource(R.string.conn_dur_d_h_fmt, d.days, d.hours)
    d.hours > 0 -> stringResource(R.string.conn_dur_h_m_fmt, d.hours, d.minutes)
    d.minutes > 0 -> stringResource(R.string.conn_dur_m_fmt, d.minutes)
    else -> stringResource(R.string.conn_dur_lt_min)
}

/**
 * سطور الاتصال الموحّدة: «متصل منذ 2س 14د · آخر انقطاع قبل 3س»، تقدّم إعادة الاتصال، وإشعارات السبب/الجودة (واحد لكل نوع).
 * تُستعمل في الشريط الموحّد وفي شاشة التشخيص. المدد بـ elapsedRealtime فلا تتأثر بتغيير ساعة الهاتف.
 */
@Composable
fun ConnectionStatusLines(status: RouterStatus, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val tickMs = if (status.state == RouterConnectionState.RECONNECTING) 1_000L else 30_000L
    LaunchedEffect(status.state, tickMs) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(tickMs)
        }
    }
    val p = ConnectionPresenter.present(status, now)
    val style = MaterialTheme.typography.labelSmall
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXXS)) {
        val since = p.connectedFor
        val outage = p.lastOutageAgo
        if (since != null) {
            val head = stringResource(R.string.conn_status_connected_since_fmt, durationText(since))
            val line = if (outage != null) head + " · " + stringResource(R.string.conn_status_last_outage_fmt, durationText(outage)) else head
            Text(line, style = style)
        } else if (outage != null && p.state != RouterConnectionState.NOT_CONFIGURED) {
            Text(stringResource(R.string.conn_status_last_outage_fmt, durationText(outage)), style = style)
        }
        p.reconnect?.let { r ->
            Text(
                if (r.retryInSeconds != null) stringResource(R.string.conn_status_reconnect_fmt, r.attempt, r.retryInSeconds)
                else stringResource(R.string.conn_status_reconnect_attempt_fmt, r.attempt),
                style = style
            )
        }
        p.notices.forEach { notice ->
            when (notice) {
                ConnectionNotice.UNSTABLE -> Text(stringResource(R.string.conn_notice_unstable), style = style)
                ConnectionNotice.ROUTER_REBOOTED -> Text(stringResource(R.string.conn_notice_rebooted), style = style)
                ConnectionNotice.NETWORK_LOST -> Text(stringResource(R.string.conn_notice_network_lost), style = style)
                ConnectionNotice.OFF_ROUTER_NETWORK -> {
                    Text(stringResource(R.string.conn_notice_off_router), style = style, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.conn_notice_off_router_hint), style = style)
                }
                ConnectionNotice.SLOW -> Text(stringResource(R.string.conn_quality_slow_fmt, status.rttMs ?: 0L), style = style)
                ConnectionNotice.INTERMITTENT -> Text(stringResource(R.string.conn_quality_intermittent), style = style)
            }
        }
        if (p.quality == ps.mgtik.hotspot.domain.model.LinkQualityLevel.GOOD) {
            Text(stringResource(R.string.conn_quality_good_fmt, status.rttMs ?: 0L), style = style)
        }
    }
}

/**
 * حوار القرار الإلزامي عند فشل الاتصال أو انقطاعه: لا يُغلق إلا بقرار (إعادة / ضبط / تخطي).
 * حوار واحد مركزي، وحالة attentionRequired تُفعَّل مرة واحدة لكل انقطاع فلا تتراكم الحوارات.
 */
@Composable
fun RouterDecisionDialog(
    status: RouterStatus,
    onRetry: () -> Unit,
    onConfigure: () -> Unit,
    onSkip: () -> Unit
) {
    val cloud = status.isCloudConfigUnavailable
    val lost = !cloud && (status.state == RouterConnectionState.DISCONNECTED || status.state == RouterConnectionState.RECONNECTING)
    val title = stringResource(
        when {
            cloud -> R.string.router_alert_title_cloud
            lost -> R.string.router_alert_title_lost
            else -> R.string.router_alert_title_failed
        }
    )
    val body = stringResource(
        when {
            cloud -> R.string.router_alert_body_cloud
            lost -> R.string.router_alert_body_lost
            else -> R.string.router_alert_body_failed
        }
    )
    AlertDialog(
        onDismissRequest = { /* قرار إلزامي: لا إغلاق بالنقر خارجاً أو بزر الرجوع */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                if (!cloud) {
                    status.errorCode?.let {
                        Text(
                            stringResource(R.string.router_alert_reason_fmt, stringResource(RouterErrorTexts.reasonRes(it))),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.router_action_retry_connection))
                }
                OutlinedButton(onClick = onConfigure, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.router_action_configure_settings))
                }
                TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.router_action_skip_connection))
                }
            }
        }
    )
}

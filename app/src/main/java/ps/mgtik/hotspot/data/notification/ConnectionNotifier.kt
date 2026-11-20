package ps.mgtik.hotspot.data.notification

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.data.local.prefs.AppPreferences
import ps.mgtik.hotspot.domain.repository.IRouterStateManager
import ps.mgtik.hotspot.ui.util.RouterErrorTexts
import javax.inject.Inject
import javax.inject.Singleton

/**
 * الإشعار الدائم لحالة الاتصال (3.4). قناة مستقلة `mgtik_connection` بأهمية LOW بلا صوت (لا يُستعمل mgtik_alerts).
 *
 * قرار Android 15: لا خدمة Foreground دائمة. الإشعار يُنشر ويُحدَّث أثناء ظهور التطبيق فقط (ProcessLifecycleOwner)
 * ويُزال عند الخلفية؛ فلا حاجة لنوع specialUse ولا لإبقاء عملية حيّة، وهذا متسق مع إيقاف النبض في الخلفية (4.2).
 * يعمل فقط بخيار صريح من الإعدادات، ويُزال عند تسجيل الخروج من الراوتر أو وضع عدم الاتصال (يقرر ذلك المخطط).
 */
@Singleton
class ConnectionNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routerState: IRouterStateManager,
    private val prefs: AppPreferences
) {
    private val visible = MutableStateFlow(false)
    @Volatile private var shown: ConnectionNotificationPlan = ConnectionNotificationPlan.None

    /** يُستدعى من الخيط الرئيسي (Application.onCreate) لأن تسجيل مراقب ProcessLifecycleOwner يتطلبه. */
    fun start(scope: CoroutineScope) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { visible.value = true }
            override fun onStop(owner: LifecycleOwner) { visible.value = false }
        })
        combine(routerState.status, prefs.connectionNotification, visible) { status, enabled, appVisible ->
            ConnectionNotificationPlanner.plan(enabled, appVisible, status)
        }
            .distinctUntilChanged() // نبضة فاشلة لا تغيّر الخطة ⇒ لا إشعار جديد
            .onEach { render(it) }
            .launchIn(scope)
    }

    @SuppressLint("MissingPermission") // areNotificationsEnabled() يغطي POST_NOTIFICATIONS
    private fun render(plan: ConnectionNotificationPlan) {
        try {
            val manager = NotificationManagerCompat.from(context)
            when (plan) {
                ConnectionNotificationPlan.Keep -> return
                ConnectionNotificationPlan.None -> {
                    if (shown != ConnectionNotificationPlan.None) manager.cancel(NOTIFICATION_ID)
                    shown = plan
                    return
                }
                else -> Unit
            }
            if (!manager.areNotificationsEnabled()) return
            ensureChannel()
            manager.notify(NOTIFICATION_ID, build(plan))
            shown = plan
        } catch (e: SecurityException) {
            // الإذن سُحب أثناء العمل
        } catch (e: RuntimeException) {
            // عطل نظام: الإشعار ثانوي ولا يُسقط التطبيق
        }
    }

    private fun build(plan: ConnectionNotificationPlan): android.app.Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.conn_notif_action_open), open)
        when (plan) {
            is ConnectionNotificationPlan.Connected -> {
                val wallSince = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - plan.connectedSinceElapsed)
                val line2 = context.getString(R.string.conn_notif_cpu_ram_fmt, plan.cpuPercent, plan.freeRamMb)
                b.setContentTitle(context.getString(R.string.conn_notif_connected_title))
                    .setContentText(context.getString(R.string.conn_notif_connected_text_fmt, plan.routerOs, plan.host))
                    .setStyle(
                        NotificationCompat.BigTextStyle().bigText(
                            context.getString(R.string.conn_notif_connected_text_fmt, plan.routerOs, plan.host) + "\n" + line2
                        )
                    )
                    .setUsesChronometer(true)
                    .setWhen(wallSince)
                    .setShowWhen(true)
                    .setOngoing(true)
            }
            is ConnectionNotificationPlan.Reconnecting -> {
                b.setContentTitle(context.getString(R.string.conn_notif_reconnecting_title))
                    .setContentText(context.getString(R.string.conn_notif_reconnecting_text))
                    .setProgress(0, 0, true)
                    .setOngoing(true)
            }
            is ConnectionNotificationPlan.Disconnected -> {
                val reason = plan.errorCode?.let { context.getString(RouterErrorTexts.reasonRes(it)) }
                val retry = PendingIntent.getBroadcast(
                    context, 1,
                    Intent(context, ConnectionActionReceiver::class.java).setAction(ConnectionActionReceiver.ACTION_RETRY),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                b.setContentTitle(context.getString(R.string.conn_notif_disconnected_title))
                    .setContentText(reason ?: context.getString(R.string.conn_notif_disconnected_text))
                    .addAction(0, context.getString(R.string.router_action_retry), retry)
                    .setOngoing(false)
            }
            else -> Unit
        }
        return b.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.conn_notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "mgtik_connection"
        private const val NOTIFICATION_ID = 7101
    }
}

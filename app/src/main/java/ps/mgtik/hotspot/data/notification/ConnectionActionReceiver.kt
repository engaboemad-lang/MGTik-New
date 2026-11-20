package ps.mgtik.hotspot.data.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import ps.mgtik.hotspot.domain.repository.IRouterStateManager
import javax.inject.Inject

/** إجراء «إعادة المحاولة» في إشعار الاتصال: يستدعي المدير المركزي نفسه (لا مسار اتصال ثانٍ). */
@AndroidEntryPoint
class ConnectionActionReceiver : BroadcastReceiver() {
    @Inject lateinit var routerState: IRouterStateManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_RETRY) routerState.retry()
    }

    companion object { const val ACTION_RETRY = "ps.mgtik.hotspot.action.ROUTER_RETRY" }
}

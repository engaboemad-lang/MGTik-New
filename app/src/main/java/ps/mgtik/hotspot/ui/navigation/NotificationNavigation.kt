package ps.mgtik.hotspot.ui.navigation

import androidx.navigation.NavController
import ps.mgtik.hotspot.domain.model.NotificationTarget

/** فتح وجهة إشعار (فاتورة/طلب/طابور العمليات)؛ مشتركة بين مركز الإشعارات وفتح التطبيق من إشعار النظام. */
fun NavController.navigateToNotificationTarget(type: String, id: String) {
    when (type) {
        NotificationTarget.INVOICE -> navigate("${Screen.InvoiceDetail.route}/$id")
        NotificationTarget.ORDER -> navigate("${Screen.OrderDetail.route}/$id")
        NotificationTarget.OFFLINE_QUEUE -> navigate(Screen.OfflineQueue.route) { launchSingleTop = true }
    }
}

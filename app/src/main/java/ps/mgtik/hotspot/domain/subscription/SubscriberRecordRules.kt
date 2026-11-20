package ps.mgtik.hotspot.domain.subscription

import ps.mgtik.hotspot.domain.model.Subscription
import ps.mgtik.hotspot.domain.model.SubscriptionExecution
import ps.mgtik.hotspot.domain.model.SubscriptionStatus
import ps.mgtik.hotspot.domain.model.UserRecordStatus
import ps.mgtik.hotspot.domain.model.UserSyncStatus

/**
 * اتساق سجل المستخدم (local_users) مع اشتراكه: الاشتراك هو المصدر، والسجل مرآة له تُكتب داخل معاملة الاشتراك نفسها.
 * نقية ليُختبر الاتساق دون Firestore.
 */
object SubscriberRecordRules {
    fun statusOf(s: Subscription): UserRecordStatus = when (s.status) {
        SubscriptionStatus.ACTIVE -> UserRecordStatus.ACTIVE
        SubscriptionStatus.EXPIRED -> UserRecordStatus.EXPIRED
        // DRAFT / SCHEDULED / SUSPENDED / CANCELLED: الحساب غير فعّال إدارياً
        else -> UserRecordStatus.DISABLED
    }

    fun syncOf(s: Subscription): UserSyncStatus = when (s.execution) {
        SubscriptionExecution.SYNCED -> UserSyncStatus.SYNCED
        SubscriptionExecution.PENDING -> UserSyncStatus.PENDING
        SubscriptionExecution.FAILED -> UserSyncStatus.FAILED
    }
}

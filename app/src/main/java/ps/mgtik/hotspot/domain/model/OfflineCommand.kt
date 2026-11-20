package ps.mgtik.hotspot.domain.model

import java.util.UUID

/**
 * OfflineCommand (§5.3) - تمثيل موحد لكافة عمليات الكتابة الحساسة العابرة للوحدات.
 * تم التحديث خلال التدقيق لضمان قابلية التتبع (Traceability).
 */
/** الحد الأقصى لمحاولات تنفيذ أمر في الطابور؛ بعده يُعدّ فاشلاً نهائياً ويبقى سجله (lastError) ظاهراً للمراجعة. */
const val MAX_COMMAND_ATTEMPTS = 8

/**
 * ملاحظة: لا حقل processedAt عمداً. الأمر الناجح يُحذف من الجدول (OfflineCommandProcessor)، والفاشل يبقى بـ attemptCount وlastError،
 * فلا صف يحمل "وقت المعالجة". أثر العمليات الناجحة مكانه audit_logs، لا هذا الجدول.
 */
data class OfflineCommand(
    val commandId: String = UUID.randomUUID().toString(),
    val type: OfflineCommandType,
    val payloadJson: String,
    val idempotencyKey: String,
    val createdAt: Long = System.currentTimeMillis(),
    val attemptCount: Int = 0,
    val lastError: String? = null
) {
    /** استنفد محاولاته أو فشل فشلاً غير قابل للإعادة: لا يُنفَّذ مجدداً تلقائياً. */
    val isDead: Boolean get() = attemptCount >= MAX_COMMAND_ATTEMPTS
}

enum class OfflineCommandType {
    // Profiles / Generation
    SUBMIT_GENERATION,
    RETRY_EXECUTION,
    CREATE_PROFILE,
    UPDATE_PROFILE,
    DELETE_PROFILE,
    MIGRATE_PROFILE,
    
    // Users
    ENABLE_USER,
    DISABLE_USER,
    DELETE_USER_FROM_ROUTER,
    RE_EXPORT_USER,
    RENEW_USER_PACKAGE,
    ARCHIVE_USER,
    EDIT_USER,
    
    // Billing & Orders
    RECORD_PAYMENT,
    VOID_PAYMENT,
    REFUND_PAYMENT,
    CANCEL_INVOICE,
    APPLY_INVOICE_DISCOUNT, // تمت إضافته لإصلاح الخلل المنطقي (§4.16)
    UPDATE_INVOICE,          // تمت إضافته لضمان التحديث الصحيح (§4.16)
    REOPEN_INVOICE,
    APPLY_OWNER_CREDIT,
    UNAPPLY_OWNER_CREDIT,
    UPDATE_PAYMENT,
    CLOSE_ORDER,
    CORRECT_ORDER_OWNER,
    
    // Owner Accounts
    SET_OWNER_OPENING_BALANCE,
    RECORD_OWNER_ADVANCE,
    REFUND_FROM_OWNER_CREDIT,

    // History
    ARCHIVE_HISTORY_BATCH
}

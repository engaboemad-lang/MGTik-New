package ps.mgtik.hotspot.ui.util

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.domain.usecase.BillingRules
import ps.mgtik.hotspot.domain.usecase.OwnerCreditRules
import ps.mgtik.hotspot.domain.usecase.OwnerInputValidator
import ps.mgtik.hotspot.domain.usecase.UserEditRules

/**
 * ErrorTranslator - المترجم الموحد لرموز أخطاء النظام (§3.7).
 * يحول رموز ApiError التقنية إلى نصوص مفهومة للمستخدم من ملفات الموارد.
 *
 * المصدر الوحيد لربط الرمز بمورد النص: ViewModels تخزّن @StringRes في الحالة (لا نصوصاً خاماً)
 * فتتبع الواجهة لغة الجهاز، ولا يُعرض للمستخدم نص تقني إنجليزي من ApiError.message.
 */
object ErrorTranslator {

    private val messages: Map<String, Int> = mapOf(
        ApiError.CODE_NETWORK_UNAVAILABLE to R.string.ERR_NETWORK_UNAVAILABLE,
        ApiError.CODE_TIMEOUT to R.string.ERR_TIMEOUT,
        ApiError.CODE_AUTH_INVALID_CREDENTIALS to R.string.ERR_AUTH_INVALID_CREDENTIALS,
        ApiError.CODE_AUTH_USER_DISABLED to R.string.ERR_AUTH_USER_DISABLED,
        ApiError.CODE_AUTH_TOO_MANY_REQUESTS to R.string.ERR_AUTH_TOO_MANY_REQUESTS,
        ApiError.CODE_AUTH_OPERATION_NOT_ALLOWED to R.string.ERR_AUTH_OPERATION_NOT_ALLOWED,
        ApiError.CODE_AUTH_SERVICE_UNAVAILABLE to R.string.ERR_AUTH_SERVICE_UNAVAILABLE,
        ApiError.CODE_VALIDATION_FAILED to R.string.ERR_VALIDATION_FAILED,
        ApiError.CODE_UNEXPECTED_CLIENT to R.string.ERR_UNKNOWN,
        ApiError.CODE_UNKNOWN to R.string.ERR_UNKNOWN,
        ApiError.CODE_SERIALIZATION_ERROR to R.string.ERR_DATA_PARSE_FAILED,
        ApiError.CODE_HTTP_ERROR to R.string.ERR_HTTP_FAILED,
        ApiError.CODE_NOT_IMPLEMENTED to R.string.ERR_NOT_IMPLEMENTED,
        ApiError.CODE_ROUTER_AUTH_FAILED to R.string.ERR_ROUTER_AUTH_FAILED,
        ApiError.CODE_ROUTER_REST_UNAVAILABLE to R.string.ERR_ROUTER_REST_UNAVAILABLE,
        ApiError.CODE_ROUTER_SSL_UNTRUSTED to R.string.ERR_ROUTER_SSL_UNTRUSTED,
        ApiError.CODE_ROUTER_REJECTED to R.string.ERR_ROUTER_REJECTED,
        ps.mgtik.hotspot.domain.generation.GenerationRules.ERR_ROUTER_NOT_READY to R.string.ERR_GEN_ROUTER_NOT_READY,
        ps.mgtik.hotspot.data.userman.UserManSource.ERR_ROUTER_VERSION_UNKNOWN to R.string.ERR_ROUTER_VERSION_UNKNOWN,
        ApiError.CODE_ROUTER_OFFLINE to R.string.ERR_ROUTER_OFFLINE,
        ApiError.CODE_ROUTER_HOST_UNREACHABLE to R.string.router_error_unreachable,
        ApiError.CODE_ROUTER_PORT_CLOSED to R.string.router_error_port_closed,
        ApiError.CODE_ROUTER_PERMISSION_DENIED to R.string.router_error_permission_denied,
        ApiError.CODE_ROUTER_NOT_CONFIGURED to R.string.ERR_ROUTER_NOT_CONFIGURED,
        "ERR_ROUTER_CONFIG_VERIFY_FAILED" to R.string.router_save_verify_failed,
        // رموز أعمال تُرمى من طبقة البيانات وتصل للواجهة كما هي
        OwnerInputValidator.ERR_PHONE_DUPLICATE to R.string.owner_error_phone_duplicate,
        "ERR_INSUFFICIENT_CREDIT" to R.string.owner_error_insufficient_credit,
        OwnerCreditRules.ERR_INVOICE_NOT_PAYABLE to R.string.owner_error_credit_invoice_not_payable,
        OwnerCreditRules.ERR_EXCEEDS_DUE to R.string.owner_error_credit_exceeds_due,
        OwnerCreditRules.ERR_CREDIT_METHOD_RESERVED to R.string.owner_error_credit_method_reserved,
        OwnerCreditRules.ERR_OWNER_NOT_FOUND to R.string.owner_error_not_found,
        // فوترة الطلبات: فاتورة/دفعة/خصم
        BillingRules.ERR_INVOICE_NOT_FOUND to R.string.billing_ERR_INVOICE_NOT_FOUND,
        BillingRules.ERR_INVOICE_NOT_PAYABLE to R.string.billing_ERR_INVOICE_NOT_PAYABLE,
        BillingRules.ERR_PAYMENT_EXCEEDS_DUE to R.string.billing_ERR_PAYMENT_EXCEEDS_DUE,
        BillingRules.ERR_PAYMENT_NOT_FOUND to R.string.billing_ERR_PAYMENT_NOT_FOUND,
        BillingRules.ERR_PAYMENT_ALREADY_REVERSED to R.string.billing_ERR_PAYMENT_ALREADY_REVERSED,
        BillingRules.ERR_REFUND_EXCEEDS_PAYMENT to R.string.billing_ERR_REFUND_EXCEEDS_PAYMENT,
        BillingRules.ERR_DISCOUNT_INVALID to R.string.billing_ERR_DISCOUNT_INVALID,
        BillingRules.ERR_DISCOUNT_BELOW_PAID to R.string.billing_ERR_DISCOUNT_BELOW_PAID,
        BillingRules.ERR_INVOICE_HAS_PAYMENTS to R.string.billing_ERR_INVOICE_HAS_PAYMENTS,
        BillingRules.ERR_INVOICE_NOT_CANCELED to R.string.billing_ERR_INVOICE_NOT_CANCELED,
        BillingRules.ERR_INVOICE_FIELD_NOT_UPDATABLE to R.string.billing_ERR_FIELD_NOT_UPDATABLE,
        BillingRules.ERR_PAYMENT_FIELD_NOT_UPDATABLE to R.string.billing_ERR_FIELD_NOT_UPDATABLE,
        OwnerCreditRules.ERR_OWNER_FIELD_NOT_UPDATABLE to R.string.billing_ERR_FIELD_NOT_UPDATABLE,
        OwnerCreditRules.ERR_OWNER_NOT_ACTIVE to R.string.owner_error_not_active,
        OwnerCreditRules.ERR_OWNER_NAME_REQUIRED to R.string.owner_error_name_required,
        OwnerCreditRules.ERR_OWNER_STATUS_INVALID to R.string.owner_error_status_invalid,
        "ERR_SUB_INVALID_STATE" to R.string.sub_err_invalid_state,
        "ERR_SUB_EXPIRED_RENEW_REQUIRED" to R.string.sub_err_expired_renew,
        "ERR_SUB_NOT_FOUND" to R.string.sub_err_not_found,
        "ERR_SUB_USERNAME_TAKEN" to R.string.sub_err_username_taken,
        "ERR_SUB_NAME_REQUIRED" to R.string.sub_err_name_required,
        "ERR_SUB_USERNAME_INVALID" to R.string.sub_err_username_invalid,
        "ERR_SUB_PASSWORD_INVALID" to R.string.sub_err_password_invalid,
        "ERR_SUB_PROFILE_REQUIRED" to R.string.sub_err_profile_required,
        "ERR_SUB_PERIOD_INVALID" to R.string.sub_err_period_invalid,
        "ERR_SUB_PRICE_INVALID" to R.string.sub_err_price_invalid,
        "ERR_SUB_DISCOUNT_INVALID" to R.string.sub_err_discount_invalid,
        "ERR_SUB_START_INVALID" to R.string.sub_err_start_invalid,
        "ERR_SUB_ROUTER_PENDING" to R.string.sub_err_router_pending,
        "ERR_SUB_AMOUNT_INVALID" to R.string.sub_err_amount_invalid,
        "ERR_SUB_OVERPAY" to R.string.sub_err_overpay,
        "ERR_SUB_INVOICE_SETTLED" to R.string.sub_err_invoice_settled,
        "ERR_SUB_PAYMENT_NOT_FOUND" to R.string.sub_err_payment_not_found,
        "ERR_SUB_INVOICE_NOT_FOUND" to R.string.sub_err_invoice_not_found,
        "ERR_NO_SUBSCRIPTION" to R.string.sub_err_no_subscription,
        "ERR_CATEGORY_CLASS_REQUIRED" to R.string.sysclass_err_class_required,
        "ERR_CATEGORY_CLASS_IMMUTABLE" to R.string.sysclass_err_class_immutable,
        "ERR_PROFILE_CATEGORY_REQUIRED" to R.string.sysclass_err_category_required,
        "ERR_CATEGORY_UNCLASSIFIED" to R.string.sysclass_err_category_unclassified,
        "ERR_PROFILE_CLASS_MISMATCH" to R.string.sysclass_err_profile_class_mismatch,
        "ERR_PROFILE_MOVE_CLASS_MISMATCH" to R.string.sysclass_err_move_mismatch,
        "ERR_USER_SUBSCRIPTION_MANAGED" to R.string.sysclass_err_user_subscription_managed,
        // إنشاء مستخدم فردي من S16
        "ERR_USER_CREATE_USERNAME_TAKEN" to R.string.user_create_err_username_taken,
        "ERR_USER_CREATE_USERNAME_INVALID" to R.string.user_create_err_username_invalid,
        "ERR_USER_CREATE_PASSWORD_INVALID" to R.string.user_create_err_password_invalid,
        "ERR_USER_CREATE_PROFILE_REQUIRED" to R.string.user_create_err_profile_required,
        "ERR_USER_CREATE_ADMIN_OWNER_MISSING" to R.string.user_create_err_admin_missing,
        "ERR_USER_CREATE_ADMIN_OWNER_AMBIGUOUS" to R.string.user_create_err_admin_ambiguous,
        "ERR_USER_CREATE_ADMIN_OWNER_INACTIVE" to R.string.user_create_err_admin_inactive,
        // إدارة الجلسات
        "ERR_SESSION_GENERATION_ACTIVE" to R.string.ERR_SESSION_GENERATION_ACTIVE,
        "ERR_SESSION_ROUTER_STILL_CONNECTED" to R.string.ERR_SESSION_ROUTER_STILL_CONNECTED,
        // الإعدادات التشغيلية للباقة (توليد وإنشاء فردي)
        "ERR_PROFILE_LIMIT_INVALID" to R.string.user_create_err_limits_invalid,
        "ERR_PROFILE_CLASSIFICATION_MISSING" to R.string.user_create_err_classification_missing,
        "ERR_PROFILE_ROUTER_OUT_OF_SYNC" to R.string.user_create_err_router_profile_out_of_sync,
        "ERR_PROFILE_ROUTER_MISSING" to R.string.gen_error_router_profile_missing,
        "ERR_USER_ROUTER_UNVERIFIED" to R.string.gen_error_user_router_unverified,
        "ERR_GEN_USERNAME_TAKEN" to R.string.gen_error_username_taken,
        "ERR_USER_ROUTER_LIMITS_UNVERIFIED" to R.string.user_create_err_limits_unverified,
        OwnerCreditRules.ERR_INVALID_AMOUNT to R.string.owner_error_amount_invalid,
        "CLOUD_OPENING_BALANCE_ALREADY_SET" to R.string.owner_error_opening_balance_set,
        "ERR_FIRESTORE_PERMISSION_DENIED" to R.string.ERR_PERMISSION_DENIED,
        // تعديل/أرشفة المستخدم
        UserEditRules.ERR_NO_CHANGES to R.string.user_edit_err_no_changes,
        UserEditRules.ERR_OWNER_LOCKED to R.string.user_edit_err_owner_locked,
        UserEditRules.ERR_OWNER_INVALID to R.string.user_edit_err_owner_invalid,
        UserEditRules.ERR_CONFLICT to R.string.user_edit_err_conflict,
        UserEditRules.ERR_SESSION_DISCONNECT_FAILED to R.string.user_err_session_disconnect_failed,
        UserEditRules.ERR_OPERATION_PENDING to R.string.user_err_operation_pending,
        UserEditRules.ERR_ARCHIVE_ROUTER_UNVERIFIED to R.string.user_archive_err_router_unverified,
        UserEditRules.ERR_ROUTER_DELETE_UNVERIFIED to R.string.user_err_delete_unverified,
        UserEditRules.ERR_STATE_UNVERIFIED to R.string.user_err_state_unverified,
        UserEditRules.ERR_REEXPORT_SOURCE_MISSING to R.string.user_err_reexport_source_missing,
        UserEditRules.ERR_MISSING_ON_ROUTER to R.string.user_err_missing_on_router,
        UserEditRules.ERR_SYNC_NOT_APPLICABLE to R.string.user_err_sync_not_applicable,
        UserEditRules.ERR_NULL_USER_NOW_MANAGED to R.string.user_err_null_now_managed,
        "USER_NOT_FOUND" to R.string.ERR_USER_NOT_FOUND,
        "NO_CONFIG" to R.string.ERR_ROUTER_NOT_CONFIGURED,
        "ERR_NO_SUBSCRIPTION" to R.string.ERR_NO_SUBSCRIPTION
    )

    /**
     * مورد النص المناسب للرمز دائماً (غير @Composable كي تستعمله ViewModels).
     * رمز HTTP يأتي بصيغة "ERR_HTTP_FAILED (500)" فيُطابَق على بادئته؛ والمجهول يصبح «خطأ غير معروف».
     */
    @StringRes
    fun resIdOf(errorCode: String?): Int {
        if (errorCode.isNullOrBlank()) return R.string.ERR_UNKNOWN
        messages[errorCode]?.let { return it }
        if (errorCode.startsWith(ApiError.CODE_HTTP_ERROR)) return R.string.ERR_HTTP_FAILED
        return R.string.ERR_UNKNOWN
    }

    @StringRes
    fun resIdOf(error: ApiError): Int = resIdOf(error.code)

    /** null عند غياب ترجمة معروفة للرمز (لمن يريد نصاً احتياطياً خاصاً به بدل «خطأ غير معروف»). */
    @StringRes
    fun resIdOrNull(errorCode: String?): Int? {
        if (errorCode.isNullOrBlank()) return null
        messages[errorCode]?.let { return it }
        return if (errorCode.startsWith(ApiError.CODE_HTTP_ERROR)) R.string.ERR_HTTP_FAILED else null
    }

    /** يعيد نصاً مفهوماً للمستخدم دائماً؛ الرموز غير المعروفة لا تُعرض خاماً بل تُترجم إلى «خطأ غير معروف». */
    @Composable
    fun translate(errorCode: String?): String? {
        if (errorCode.isNullOrBlank()) return null
        return stringResource(resIdOf(errorCode))
    }
}

package ps.mgtik.hotspot.data.offline

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.map
import ps.mgtik.hotspot.domain.model.GenerationCommandRequest
import ps.mgtik.hotspot.domain.model.OfflineCommand
import ps.mgtik.hotspot.domain.model.OfflineCommandType
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.UserEditRequest
import ps.mgtik.hotspot.domain.repository.IBillingRepository
import ps.mgtik.hotspot.domain.repository.IGenerationRepository
import ps.mgtik.hotspot.domain.repository.IHistoryRepository
import ps.mgtik.hotspot.domain.repository.IOwnerRepository
import ps.mgtik.hotspot.domain.repository.IProfileRepository
import ps.mgtik.hotspot.domain.repository.IUserRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OfflineCommandDispatcher - ينفّذ أمراً من الطابور بالاستدعاء المباشر للمستودع المناسب.
 *
 * القاعدة الثابتة لمعنى skipQueue في كل المشروع:
 *   skipQueue = false → العملية تُسجَّل في الطابور ويتولاها WorkManager،
 *   skipQueue = true  → تنفيذ مباشر بلا طابور.
 * لذلك يستدعي المنفّذ دائماً بـ skipQueue = true (وإلا أعاد الأمر إلى الطابور في حلقة لا تنتهي).
 * كل العمليات هنا تحمل idempotencyKey الأصلي فتبقى الإعادة آمنة.
 */
@Singleton
class OfflineCommandDispatcher @Inject constructor(
    private val profiles: IProfileRepository,
    private val owners: IOwnerRepository,
    private val users: IUserRepository,
    private val history: IHistoryRepository,
    private val generation: IGenerationRepository,
    private val billing: IBillingRepository
) {

    suspend fun execute(command: OfflineCommand): AppResult<Unit> {
        val key = command.idempotencyKey
        val payload = command.payloadJson
        return try {
            when (command.type) {
                // ---- الباقات ----
                OfflineCommandType.CREATE_PROFILE ->
                    profiles.createCloudProfile(CommandCodec.decode<Profile>(payload), key, skipQueue = true)
                OfflineCommandType.UPDATE_PROFILE -> {
                    val p = CommandCodec.decode<ProfileUpdatePayload>(payload)
                    profiles.updateCloudProfile(p.profileId, CommandCodec.jsonToFields(p.fields), key, skipQueue = true)
                }
                OfflineCommandType.DELETE_PROFILE -> {
                    val p = CommandCodec.decode<ProfileDeletePayload>(payload)
                    profiles.deleteCloudProfile(p.profileId, p.alsoDeleteFromRouter, p.force, key, skipQueue = true)
                }
                OfflineCommandType.MIGRATE_PROFILE -> {
                    val p = CommandCodec.decode<ProfileRenamePayload>(payload)
                    profiles.migrateProfileRename(p.profileId, p.newProfileName, key, skipQueue = true, repointUsers = p.repointUsers)
                }

                // ---- المستخدمون ----
                OfflineCommandType.ENABLE_USER -> users.enableUser(payload, key, skipQueue = true)
                OfflineCommandType.DISABLE_USER -> users.disableUser(payload, key, skipQueue = true)
                OfflineCommandType.RENEW_USER_PACKAGE -> users.renewUserPackage(payload, key, skipQueue = true)
                OfflineCommandType.ARCHIVE_USER -> {
                    val p = CommandCodec.decode<UserArchivePayload>(payload)
                    users.archiveUser(p.recordUuid, p.reason, key, skipQueue = true).map { }
                }
                OfflineCommandType.DELETE_USER_FROM_ROUTER -> users.deleteUserFromRouter(payload, key, skipQueue = true).map { }
                OfflineCommandType.RE_EXPORT_USER -> users.reExportUserToRouter(payload, key, skipQueue = true).map { }
                OfflineCommandType.EDIT_USER ->
                    users.editUser(CommandCodec.decode<UserEditRequest>(payload), key, skipQueue = true).map { }

                // ---- الملاك ----
                OfflineCommandType.SET_OWNER_OPENING_BALANCE -> {
                    val p = CommandCodec.decode<OwnerBalancePayload>(payload)
                    owners.setOwnerOpeningBalance(p.ownerId, p.amountMinor, key, skipQueue = true)
                }
                OfflineCommandType.RECORD_OWNER_ADVANCE -> {
                    val p = CommandCodec.decode<OwnerAdvancePayload>(payload)
                    owners.recordOwnerAdvance(p.ownerId, p.amountMinor, p.notes, key, skipQueue = true)
                }
                OfflineCommandType.REFUND_FROM_OWNER_CREDIT -> {
                    val p = CommandCodec.decode<OwnerBalancePayload>(payload)
                    owners.refundFromOwnerCredit(p.ownerId, p.amountMinor, key, skipQueue = true)
                }

                // ---- السجل التاريخي والتوليد ----
                OfflineCommandType.ARCHIVE_HISTORY_BATCH -> history.archiveBatch(payload, skipQueue = true)
                // التوليد له عاملُه الخاص (GenerationWorker) بدل تشغيل ساغا طويلة داخل عامل الطابور العام؛
                // هذان الأمران القديمان المخزَّنان يُسلَّمان له فقط (الاستدعاء بـ false لا يعيد الأمر للطابور) وهما idempotent بالمفتاح.
                OfflineCommandType.SUBMIT_GENERATION ->
                    generation.submitGenerationCommand(CommandCodec.decode<GenerationCommandRequest>(payload), key, skipQueue = false).map { }
                OfflineCommandType.RETRY_EXECUTION ->
                    generation.retryExecution(payload, key, skipQueue = false).map { }

                // ---- الفوترة ----
                OfflineCommandType.RECORD_PAYMENT -> {
                    val p = CommandCodec.decode<RecordPaymentPayload>(payload)
                    billing.recordPayment(p.invoiceId, p.amountMinor, p.method, p.reference, p.notes, key, skipQueue = true).map { }
                }
                OfflineCommandType.VOID_PAYMENT -> {
                    val p = CommandCodec.decode<VoidPaymentPayload>(payload)
                    billing.voidPayment(p.paymentId, p.reason, key, skipQueue = true)
                }
                OfflineCommandType.REFUND_PAYMENT -> {
                    val p = CommandCodec.decode<RefundPaymentPayload>(payload)
                    billing.refundPayment(p.paymentId, p.refundAmountMinor, key, skipQueue = true)
                }
                OfflineCommandType.CANCEL_INVOICE -> {
                    val p = CommandCodec.decode<CancelInvoicePayload>(payload)
                    billing.cancelInvoice(p.invoiceId, p.force, p.resolution, key, skipQueue = true)
                }
                OfflineCommandType.APPLY_INVOICE_DISCOUNT -> {
                    val p = CommandCodec.decode<InvoiceDiscountPayload>(payload)
                    billing.applyInvoiceDiscount(p.invoiceId, p.discountType, p.discountValue, key, skipQueue = true)
                }
                OfflineCommandType.UPDATE_INVOICE -> {
                    val p = CommandCodec.decode<InvoiceUpdatePayload>(payload)
                    billing.updateInvoice(p.invoiceId, CommandCodec.jsonToFields(p.fields), key, skipQueue = true)
                }
                OfflineCommandType.REOPEN_INVOICE ->
                    billing.reopenCancelledInvoice(CommandCodec.decode<InvoiceIdPayload>(payload).invoiceId, key, skipQueue = true)
                OfflineCommandType.APPLY_OWNER_CREDIT -> {
                    val p = CommandCodec.decode<OwnerCreditPayload>(payload)
                    billing.applyOwnerCredit(p.invoiceId, p.amountMinor, key, skipQueue = true)
                }
                OfflineCommandType.UNAPPLY_OWNER_CREDIT -> {
                    val p = CommandCodec.decode<UnapplyCreditPayload>(payload)
                    billing.unapplyOwnerCredit(p.invoiceId, p.ledgerEntryId, key, skipQueue = true)
                }
                OfflineCommandType.UPDATE_PAYMENT -> {
                    val p = CommandCodec.decode<PaymentUpdatePayload>(payload)
                    billing.updatePayment(p.paymentId, CommandCodec.jsonToFields(p.fields), key, skipQueue = true)
                }

                // أنواع بلا منفّذ (لا منتِج لها اليوم): فشل صريح لا نجاح وهمي
                else -> unsupported(command.type)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            AppResult.Error(ApiError(ERR_PAYLOAD_INVALID, "Invalid payload for ${command.type}: ${e.localizedMessage}"))
        } catch (e: IllegalArgumentException) {
            AppResult.Error(ApiError(ERR_PAYLOAD_INVALID, "Invalid payload for ${command.type}: ${e.localizedMessage}"))
        }
    }

    private fun unsupported(type: OfflineCommandType) =
        AppResult.Error(ApiError(ERR_UNSUPPORTED_COMMAND, "No executor registered for command type $type"))

    companion object {
        const val ERR_PAYLOAD_INVALID = "ERR_COMMAND_PAYLOAD_INVALID"
        const val ERR_UNSUPPORTED_COMMAND = "ERR_COMMAND_UNSUPPORTED"
    }
}

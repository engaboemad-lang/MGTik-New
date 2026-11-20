package ps.mgtik.hotspot.data.offline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.LogSeverity
import ps.mgtik.hotspot.domain.model.MAX_COMMAND_ATTEMPTS
import ps.mgtik.hotspot.domain.repository.IAuditRepository
import ps.mgtik.hotspot.domain.repository.INotificationPublisher
import ps.mgtik.hotspot.domain.repository.IOfflineCommandRepository
import javax.inject.Inject
import javax.inject.Singleton

/** ناتج دورة معالجة: عدد ما نُفِّذ، وما سيُعاد لاحقاً (عطل مؤقت)، وما فشل نهائياً. */
data class ProcessSummary(val succeeded: Int = 0, val retryLater: Int = 0, val failedPermanently: Int = 0)

/** قرار نقي (قابل للاختبار) لما يُفعل بنتيجة أمر. */
enum class CommandDecision { REMOVE, RETRY, FAIL, RETRY_AND_STOP }

internal fun decide(result: AppResult<*>): CommandDecision = when (result) {
    is AppResult.Success -> CommandDecision.REMOVE
    is AppResult.Loading -> CommandDecision.RETRY
    is AppResult.Error -> when {
        // لا اتصال/مهلة: بقية الأوامر ستفشل لنفس السبب، فنتوقف ونحافظ على الترتيب
        result.error.code == ApiError.CODE_NETWORK_UNAVAILABLE || result.error.code == ApiError.CODE_TIMEOUT ->
            CommandDecision.RETRY_AND_STOP
        result.error.retryable -> CommandDecision.RETRY
        else -> CommandDecision.FAIL
    }
}

/**
 * OfflineCommandProcessor - محرك الطابور (§5.3).
 * - FIFO حسب وقت الإنشاء، أمر واحد في كل مرة، وMutex يمنع تداخل دورتين.
 * - نجاح ⇒ حذف الأمر. عطل مؤقت ⇒ عدّاد محاولات (حد أقصى MAX_COMMAND_ATTEMPTS). فشل منطقي (تحقق/صلاحيات/حمولة) ⇒
 *   فشل نهائي فوراً مع حفظ السبب، فلا يعيق بقية الأوامر.
 */
@Singleton
class OfflineCommandProcessor @Inject constructor(
    private val commandRepo: IOfflineCommandRepository,
    private val dispatcher: OfflineCommandDispatcher,
    private val auditRepo: IAuditRepository,
    private val notificationPublisher: INotificationPublisher
) {
    private val mutex = Mutex()

    suspend fun processPending(): ProcessSummary = mutex.withLock {
        var succeeded = 0
        var retryLater = 0
        var failed = 0

        for (command in commandRepo.getRunnableCommands(MAX_COMMAND_ATTEMPTS)) {
            val result: AppResult<Unit> = try {
                dispatcher.execute(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.Error(ApiError.mapThrowable(e))
            }

            val decision = decide(result)
            val errorText = (result as? AppResult.Error)?.error?.let { "${it.code}: ${it.message}" }

            when (decision) {
                CommandDecision.REMOVE -> {
                    commandRepo.removeCommand(command.commandId)
                    succeeded++
                }
                CommandDecision.FAIL -> {
                    commandRepo.markFailed(command.commandId, errorText, MAX_COMMAND_ATTEMPTS)
                    auditRepo.logAction("QUEUE_COMMAND_FAILED", "${command.type}: $errorText", LogSeverity.ERROR)
                    notifyFailed(command.commandId, result)
                    failed++
                }
                CommandDecision.RETRY, CommandDecision.RETRY_AND_STOP -> {
                    commandRepo.updateAttempt(command.commandId, errorText)
                    // آخر محاولة مسموحة: الأمر يصير "ميتاً" ولن يُعاد تلقائياً ⇒ يُبلَّغ المستخدم مرة واحدة
                    if (command.attemptCount + 1 >= MAX_COMMAND_ATTEMPTS) {
                        auditRepo.logAction("QUEUE_COMMAND_FAILED", "${command.type}: $errorText", LogSeverity.ERROR)
                        notifyFailed(command.commandId, result)
                    }
                    retryLater++
                    if (decision == CommandDecision.RETRY_AND_STOP) {
                        // الأوامر المتبقية لم تُجرَّب بعد: ستُنفَّذ في الدورة التالية
                        retryLater += commandRepo.getRunnableCommands(MAX_COMMAND_ATTEMPTS).count { it.commandId != command.commandId && it.createdAt >= command.createdAt }
                        return@withLock ProcessSummary(succeeded, retryLater, failed)
                    }
                }
            }
        }
        ProcessSummary(succeeded, retryLater, failed)
    }

    /** الإشعار ثانوي: فشله لا يوقف معالجة بقية الأوامر. */
    private suspend fun notifyFailed(commandId: String, result: AppResult<Unit>) {
        val code = (result as? AppResult.Error)?.error?.code ?: "UNKNOWN"
        try {
            notificationPublisher.queueCommandFailed(commandId, code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // يُتجاهل عمداً
        }
    }
}

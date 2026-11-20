package ps.mgtik.hotspot.data.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.data.offline.OfflineCommandProcessor
import ps.mgtik.hotspot.domain.model.CloudLogoutReport
import ps.mgtik.hotspot.domain.model.ExitReport
import ps.mgtik.hotspot.domain.model.LogSeverity
import ps.mgtik.hotspot.domain.model.MAX_COMMAND_ATTEMPTS
import ps.mgtik.hotspot.domain.model.RouterConnectionState
import ps.mgtik.hotspot.domain.model.SessionImpact
import ps.mgtik.hotspot.domain.repository.IAuditRepository
import ps.mgtik.hotspot.domain.repository.IAuthRepository
import ps.mgtik.hotspot.domain.repository.IGenerationRepository
import ps.mgtik.hotspot.domain.repository.IOfflineCommandRepository
import ps.mgtik.hotspot.domain.repository.IOfflineScheduler
import ps.mgtik.hotspot.domain.repository.IRouterStateManager
import ps.mgtik.hotspot.domain.repository.ISessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SessionManagerImpl - منطق الجلسات والإغلاق المنظَّم.
 *
 * مبادئ:
 *  - لا حذف لأي بيانات (Room/إعدادات الراوتر/جلسة Firebase في الإغلاق).
 *  - أعمال WorkManager الدائمة (طابور الأوامر والتوليد) تبقى لأنها هي ما يحمي البيانات من الضياع؛ لا نلغيها ولا ندّعي إيقافها،
 *    ونُبلغ بها في [ExitReport.stillRunning].
 *  - الساغا الجارية تحتاج السحابة والراوتر معاً: لا خروج سحابي ولا من الراوتر أثناءها (كانت ستفشل بعد استنفاد محاولاتها).
 *  - كل خطوة تفشل تُسجَّل سبباً ولا تُعلَن نجاحاً.
 */
@Singleton
class SessionManagerImpl @Inject constructor(
    private val auth: IAuthRepository,
    private val routerState: IRouterStateManager,
    private val commands: IOfflineCommandRepository,
    private val processor: OfflineCommandProcessor,
    private val generation: IGenerationRepository,
    private val offlineScheduler: IOfflineScheduler,
    private val audit: IAuditRepository,
    private val uncleanShutdownTracker: ps.mgtik.hotspot.core.stability.UncleanShutdownTracker
) : ISessionManager {

    override suspend fun inspect(): AppResult<SessionImpact> = try {
        AppResult.Success(
            SessionImpact(
                pendingCommands = commands.getRunnableCommands(MAX_COMMAND_ATTEMPTS).size,
                activeGenerationRuns = generation.activeRunCount()
            )
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppResult.Error(ApiError.mapThrowable(e))
    }

    private suspend fun generationBlock(): AppResult.Error? {
        val impact = when (val r = inspect()) {
            is AppResult.Success -> r.data
            is AppResult.Error -> return r // لا نخمّن أن لا عمل جارٍ
            is AppResult.Loading -> return AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }
        return if (impact.activeGenerationRuns > 0)
            AppResult.Error(ApiError(ISessionManager.ERR_GENERATION_ACTIVE, "${impact.activeGenerationRuns} generation run(s) in progress"))
        else null
    }

    override suspend fun cloudLogout(): AppResult<CloudLogoutReport> {
        generationBlock()?.let { return it }
        // محاولة تفريغ الطابور ما دامت الجلسة قائمة؛ الباقي محفوظ في Room ويُستأنف بعد الدخول التالي
        try {
            withTimeoutOrNull(FLUSH_TIMEOUT_MS) { processor.processPending() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // لا يمنع الخروج: الأوامر لم تُمسّ
        }
        val kept = (inspect() as? AppResult.Success)?.data?.pendingCommands ?: 0
        // السجل قبل الخروج: التدقيق يحتاج هوية المستخدم الحالية
        audit.logAction("CLOUD_LOGOUT", "Cloud sign-out requested; pending commands kept: $kept", LogSeverity.INFO)
        return when (val r = auth.signOut()) {
            is AppResult.Success -> AppResult.Success(CloudLogoutReport(kept))
            is AppResult.Error -> r
            is AppResult.Loading -> AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }
    }

    override suspend fun routerLogout(): AppResult<Unit> {
        generationBlock()?.let { return it }
        return try {
            audit.logAction("ROUTER_LOGOUT", "Router sign-out requested", LogSeverity.INFO)
            routerState.logout()
            if (routerState.status.value.state == RouterConnectionState.CONNECTED) {
                AppResult.Error(ApiError(ISessionManager.ERR_ROUTER_STILL_CONNECTED, "Router still connected after logout"))
            } else AppResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Error(ApiError.mapThrowable(e))
        }
    }

    override suspend fun prepareExit(): ExitReport {
        val failures = mutableListOf<String>()
        val impact = (inspect() as? AppResult.Success)?.data
        try {
            audit.logAction("APP_EXIT", "Orderly exit; still managed by Android: $impact", LogSeverity.INFO)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures += "audit: ${e.message}"
        }
        try {
            routerState.shutdown()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures += "router: ${e.message}"
            try { audit.logAction("APP_EXIT_FAILED", "Router shutdown failed: ${e.message}", LogSeverity.WARNING) } catch (_: Exception) { }
        }
        // إغلاق منظم مكتمل: تُمسح علامة «الإغلاق غير السليم» (حتى لو سُجّلت إخفاقات خطوات فرعية، فالخروج نفسه كان بقرار)
        uncleanShutdownTracker.onCleanExit()
        return ExitReport(stillRunning = impact, failures = failures)
    }

    override suspend fun resumeServices() {
        uncleanShutdownTracker.onResumed()
        routerState.start()
        offlineScheduler.schedulePeriodicSync()
        offlineScheduler.scheduleSync()
        try { generation.resumePendingRuns() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    private companion object {
        const val FLUSH_TIMEOUT_MS = 15_000L
    }
}

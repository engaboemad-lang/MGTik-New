package ps.mgtik.hotspot.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.IdempotencyKeyProvider
import ps.mgtik.hotspot.domain.model.RouterConfig
import ps.mgtik.hotspot.domain.model.RouterHealth
import ps.mgtik.hotspot.domain.model.RouterInfo
import ps.mgtik.hotspot.domain.model.RouterInterface
import ps.mgtik.hotspot.domain.model.RouterSaveOutcome
import ps.mgtik.hotspot.domain.model.RouterStateEvent
import ps.mgtik.hotspot.domain.model.RouterStatus
import ps.mgtik.hotspot.domain.repository.IRouterRepository
import ps.mgtik.hotspot.domain.repository.IRouterStateManager
import ps.mgtik.hotspot.domain.repository.ISettingsRepository
import javax.inject.Inject

/**
 * GetRouterConfigUseCase - جلب إعدادات الراوتر من مستودع الإعدادات (SSoT)
 */
class GetRouterConfigUseCase @Inject constructor(private val settingsRepository: ISettingsRepository) {
    operator fun invoke(): Flow<RouterConfig> = settingsRepository.getRouterConfig()
}

class GetRouterHealthUseCase @Inject constructor(private val routerRepository: IRouterRepository) {
    operator fun invoke(): Flow<RouterHealth> = routerRepository.getRouterHealth()
}

/** فحص إعداد دون حفظه (عبر المدير المركزي: يتبدّل وضعه إلى Configuring ويُوقف النبض أثناء الفحص). */
class TestRouterConnectionUseCase @Inject constructor(private val stateManager: IRouterStateManager) {
    suspend operator fun invoke(config: RouterConfig): AppResult<RouterInfo> {
        RouterInputValidator.validate(config)?.let { return AppResult.Error(it) }
        return stateManager.testConfig(config)
    }
}

/**
 * SaveRouterConfigUseCase - حفظ إعدادات الراوتر ثم اختبار الاتصال بها وتحديث الحالة المركزية.
 * نجاح الحفظ لا يعني نجاح الاتصال؛ النتيجة [RouterSaveOutcome] تفصل بينهما، وفشل الحفظ يُبقي الإعداد السابق.
 */
class SaveRouterConfigUseCase @Inject constructor(private val stateManager: IRouterStateManager) {
    suspend operator fun invoke(config: RouterConfig): RouterSaveOutcome {
        // الحفظ كان بلا أي تحقق: عنوان فارغ/منفذ خارج المدى يُخزَّن في السحابة ثم تفشل كل عمليات الراوتر
        RouterInputValidator.validate(config)?.let { return RouterSaveOutcome.SaveFailed(it) }
        return stateManager.saveAndConnect(config)
    }
}

/** الحالة المركزية للراوتر (تتشاركها كل الشاشات). */
class ObserveRouterStatusUseCase @Inject constructor(private val stateManager: IRouterStateManager) {
    operator fun invoke(): StateFlow<RouterStatus> = stateManager.status
}

class ObserveRouterEventsUseCase @Inject constructor(private val stateManager: IRouterStateManager) {
    operator fun invoke(): Flow<RouterStateEvent> = stateManager.events
}

class RetryRouterConnectionUseCase @Inject constructor(private val stateManager: IRouterStateManager) {
    operator fun invoke() = stateManager.retry()
}

class SkipRouterConnectionUseCase @Inject constructor(private val stateManager: IRouterStateManager) {
    operator fun invoke() = stateManager.skipConnection()
}

class GetRouterInterfacesUseCase @Inject constructor(private val routerRepository: IRouterRepository) {
    suspend operator fun invoke(config: RouterConfig): AppResult<List<RouterInterface>> =
        routerRepository.getInterfaces(config)
}

class RebootRouterUseCase @Inject constructor(private val routerRepository: IRouterRepository) {
    suspend operator fun invoke(config: RouterConfig): AppResult<Unit> = 
        routerRepository.rebootRouter(config)
}

class GetLiveSessionsUseCase @Inject constructor(private val routerRepository: IRouterRepository) {
    suspend operator fun invoke(config: RouterConfig): AppResult<List<ps.mgtik.hotspot.domain.model.LiveSession>> =
        routerRepository.getLiveSessions(config)
}

/** فصل الجلسة الحالية فقط؛ لا يعطّل الحساب ولا يغيّر أي سجل (التعطيل مسار آخر منفصل). */
class DisconnectSessionUseCase @Inject constructor(private val routerRepository: IRouterRepository) {
    suspend operator fun invoke(config: RouterConfig, sessionId: String): AppResult<ps.mgtik.hotspot.domain.model.DisconnectOutcome> =
        if (sessionId.isBlank()) AppResult.Error(ps.mgtik.hotspot.core.result.ApiError.validationError("Session id is required"))
        else routerRepository.disconnectSession(config, sessionId)
}

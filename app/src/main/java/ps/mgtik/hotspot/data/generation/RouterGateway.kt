package ps.mgtik.hotspot.data.generation

import kotlinx.coroutines.flow.first
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.data.userman.UserManPlanner
import ps.mgtik.hotspot.data.userman.UserManSource
import ps.mgtik.hotspot.domain.usecase.RouterProfileSettings
import ps.mgtik.hotspot.domain.generation.CreateOutcome
import ps.mgtik.hotspot.domain.generation.RouterPort
import ps.mgtik.hotspot.domain.generation.RouterTags
import ps.mgtik.hotspot.domain.generation.SagaError
import ps.mgtik.hotspot.domain.generation.SagaResult
import ps.mgtik.hotspot.domain.generation.UserLimits
import ps.mgtik.hotspot.domain.model.RouterConfig
import ps.mgtik.hotspot.domain.repository.IRouterStateManager
import javax.inject.Inject
import javax.inject.Singleton

/** RouterGateway - منفذ الساغا إلى الراوتر فوق UserManSource (REST على v7 / API على v6). */
@Singleton
class RouterGateway @Inject constructor(
    private val routerState: IRouterStateManager,
    private val userMan: UserManSource
) : RouterPort {

    /** الإعداد من الحالة المركزية (لا قراءة Firestore منفصلة قد تعيد نسخة قديمة بعد تغيير الإعدادات). */
    private fun config(): RouterConfig? =
        routerState.status.value.config.takeIf { it.host.isNotBlank() }

    private val noConfig = SagaError("NO_CONFIG", "Router connection is not configured", false)

    private fun ApiError.toSaga(): SagaError {
        val transient = retryable || code == ApiError.CODE_NETWORK_UNAVAILABLE || code == ApiError.CODE_TIMEOUT
        return SagaError(code, message, transient)
    }

    override suspend fun existingUsernames(): SagaResult<Set<String>> {
        val cfg = config() ?: return SagaResult.Err(noConfig)
        return when (val r = userMan.getUsernamesWithComments(cfg)) {
            is AppResult.Success -> SagaResult.Ok(r.data.map { it.first.lowercase() }.toSet())
            is AppResult.Error -> SagaResult.Err(r.error.toSaga())
            else -> SagaResult.Err(SagaError("ERR_UNEXPECTED", "Unexpected loading state", true))
        }
    }

    override suspend fun usernamesWithComment(tag: String): SagaResult<Set<String>> {
        val cfg = config() ?: return SagaResult.Err(noConfig)
        return when (val r = userMan.getUsernamesWithComments(cfg)) {
            is AppResult.Success -> SagaResult.Ok(r.data.filter { RouterTags.matches(it.second, tag) }.map { it.first.lowercase() }.toSet())
            is AppResult.Error -> SagaResult.Err(r.error.toSaga())
            else -> SagaResult.Err(SagaError("ERR_UNEXPECTED", "Unexpected loading state", true))
        }
    }

    override suspend fun profileSettings(profileName: String): SagaResult<RouterProfileSettings?> {
        val cfg = config() ?: return SagaResult.Err(noConfig)
        return when (val r = userMan.getProfileSettings(cfg, profileName)) {
            is AppResult.Success -> SagaResult.Ok(r.data)
            is AppResult.Error -> SagaResult.Err(r.error.toSaga())
            else -> SagaResult.Err(SagaError("ERR_UNEXPECTED", "Unexpected loading state", true))
        }
    }

    /**
     * إنشاء مستخدم في UserMan وإسناد الباقة [profileName] له. حدود الوقت والبيانات والسرعة تأتي من الباقة نفسها،
     * لذلك [limits] لا تُرسل للراوتر مع المستخدم.
     */
    override suspend fun createUser(username: String, password: String, profileName: String, comment: String, limits: UserLimits): CreateOutcome {
        val cfg = config() ?: return CreateOutcome.Fatal(noConfig)
        return userMan.createUserClassified(cfg, username, password, comment, profileName.ifBlank { null }, disabled = false)
    }

    override suspend fun verifyUsers(usernames: Collection<String>, profileName: String): SagaResult<Map<String, String>> {
        val cfg = config() ?: return SagaResult.Err(noConfig)
        if (usernames.isEmpty()) return SagaResult.Ok(emptyMap())
        return when (val r = userMan.verifyCreatedUsers(cfg, usernames, profileName)) {
            is AppResult.Success -> SagaResult.Ok(r.data)
            is AppResult.Error -> SagaResult.Err(r.error.toSaga())
            else -> SagaResult.Err(SagaError("ERR_UNEXPECTED", "Unexpected loading state", true))
        }
    }

    override suspend fun credentialMismatches(expected: Map<String, String>, comment: String): SagaResult<Set<String>> {
        val cfg = config() ?: return SagaResult.Err(noConfig)
        if (expected.isEmpty()) return SagaResult.Ok(emptySet())
        return when (val r = userMan.credentialMismatches(cfg, expected, comment)) {
            is AppResult.Success -> SagaResult.Ok(r.data)
            is AppResult.Error -> SagaResult.Err(r.error.toSaga())
            else -> SagaResult.Err(SagaError("ERR_UNEXPECTED", "Unexpected loading state", true))
        }
    }

    override suspend fun failedReadinessChecks(): SagaResult<List<String>> {
        val cfg = config() ?: return SagaResult.Err(noConfig)
        return when (val r = userMan.status(cfg)) {
            is AppResult.Success -> SagaResult.Ok(UserManPlanner.blockingChecks(r.data.checks).map { it.key })
            is AppResult.Error -> SagaResult.Err(r.error.toSaga())
            else -> SagaResult.Err(SagaError("ERR_UNEXPECTED", "Unexpected loading state", true))
        }
    }

    override suspend fun deleteUsers(usernames: Collection<String>): SagaResult<Int> {
        val cfg = config() ?: return SagaResult.Err(noConfig)
        return when (val r = userMan.deleteByUsernames(cfg, usernames)) {
            is AppResult.Success -> SagaResult.Ok(r.data)
            is AppResult.Error -> SagaResult.Err(r.error.toSaga())
            else -> SagaResult.Err(SagaError("ERR_UNEXPECTED", "Unexpected loading state", true))
        }
    }

    override suspend fun deleteByComment(tag: String): SagaResult<Int> {
        val cfg = config() ?: return SagaResult.Err(noConfig)
        return when (val r = userMan.deleteByComment(cfg, tag)) {
            is AppResult.Success -> SagaResult.Ok(r.data)
            is AppResult.Error -> SagaResult.Err(r.error.toSaga())
            else -> SagaResult.Err(SagaError("ERR_UNEXPECTED", "Unexpected loading state", true))
        }
    }
}

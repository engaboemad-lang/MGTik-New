package ps.mgtik.hotspot.data.repository

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.network.RouterConfig
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.data.cloud.source.CloudSettingsDataSource
import ps.mgtik.hotspot.domain.model.NetworkCompanyInfo
import ps.mgtik.hotspot.domain.model.UserManConfig
import ps.mgtik.hotspot.domain.repository.ISettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SettingsRepositoryImpl - تطبيق مستودع الإعدادات.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val cloudDataSource: CloudSettingsDataSource
) : ISettingsRepository {

    override fun getCompanyInfo(): Flow<NetworkCompanyInfo> =
        cloudDataSource.getCompanyInfo()

    override suspend fun updateCompanyInfo(info: NetworkCompanyInfo, idempotencyKey: String): AppResult<Unit> {
        return cloudDataSource.saveCompanyInfo(info, idempotencyKey).let { 
            if (it is AppResult.Success) AppResult.Success(Unit) 
            else AppResult.Error((it as AppResult.Error).error)
        }
    }

    override fun getRouterConfig(): Flow<RouterConfig> =
        cloudDataSource.getRouterConfig()

    override suspend fun updateRouterConfig(config: RouterConfig, idempotencyKey: String): AppResult<Unit> {
        return cloudDataSource.saveRouterConfig(config, idempotencyKey).let {
            if (it is AppResult.Success) AppResult.Success(Unit)
            else AppResult.Error((it as AppResult.Error).error)
        }
    }

    override fun getUserManConfig(): Flow<UserManConfig> = cloudDataSource.getUserManConfig()

    override suspend fun updateUserManConfig(config: UserManConfig): AppResult<Unit> =
        cloudDataSource.saveUserManConfig(config)

    override suspend fun pingCloud(): AppResult<Long> = cloudDataSource.pingCloud()
}

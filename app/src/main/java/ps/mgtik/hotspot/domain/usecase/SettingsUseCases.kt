package ps.mgtik.hotspot.domain.usecase

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.network.RouterConfig
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.IdempotencyKeyProvider
import ps.mgtik.hotspot.domain.model.NetworkCompanyInfo
import ps.mgtik.hotspot.domain.repository.ISettingsRepository
import javax.inject.Inject

/**
 * GetSettingsUseCase - جلب إعدادات النظام الموزعة (§24).
 */
class GetSettingsUseCase @Inject constructor(
    private val settingsRepository: ISettingsRepository
) {
    fun getCompanyInfo(): Flow<NetworkCompanyInfo> = settingsRepository.getCompanyInfo()
    fun getRouterConfig(): Flow<RouterConfig> = settingsRepository.getRouterConfig()
}

/**
 * UpdateCompanyInfoUseCase - تحديث هوية الشبكة والشركة.
 */
class UpdateCompanyInfoUseCase @Inject constructor(
    private val settingsRepository: ISettingsRepository
) {
    suspend operator fun invoke(
        info: NetworkCompanyInfo,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> {
        if (info.companyName.isBlank()) {
            return AppResult.Error(ApiError.validationError("اسم الشركة مطلوب"))
        }
        CompanyInfoValidator.firstInvalidField(info)?.let {
            return AppResult.Error(ApiError.validationError("Invalid company field: $it"))
        }
        return settingsRepository.updateCompanyInfo(info, idempotencyKey)
    }
}

/**
 * UpdateRouterConfigUseCase - تحديث إعدادات اتصال المايكروتك (§2).
 */
class UpdateRouterConfigUseCase @Inject constructor(
    private val settingsRepository: ISettingsRepository
) {
    suspend operator fun invoke(
        config: RouterConfig,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> {
        if (config.host.isBlank()) {
            return AppResult.Error(ApiError.validationError("عنوان IP الراوتر مطلوب"))
        }
        RouterInputValidator.validate(config)?.let { return AppResult.Error(it) }
        return settingsRepository.updateRouterConfig(config, idempotencyKey)
    }
}

/** التحقق من حقول بيانات الشركة (نقي وقابل للاختبار). الحقول الفارغة مقبولة عدا اسم الشركة. */
object CompanyInfoValidator {
    const val FIELD_EMAIL = "email"
    const val FIELD_WEBSITE = "website"
    const val FIELD_LOGO = "logo"
    const val FIELD_LOGIN_URL = "login_url"
    const val FIELD_CURRENCY = "currency"

    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val URL = Regex("^https?://[^\\s/$.?#][^\\s]*$", RegexOption.IGNORE_CASE)

    fun isEmailValid(v: String) = v.isBlank() || EMAIL.matches(v.trim())
    fun isUrlValid(v: String) = v.isBlank() || URL.matches(v.trim())
    fun isCurrencyValid(v: String) = v.trim().let { it.isNotEmpty() && it.length <= 6 }

    /** يعيد أول حقل غير صالح أو null. */
    fun firstInvalidField(info: NetworkCompanyInfo): String? = when {
        !isEmailValid(info.email) -> FIELD_EMAIL
        !isUrlValid(info.website) -> FIELD_WEBSITE
        !isUrlValid(info.logoUrl) -> FIELD_LOGO
        !isUrlValid(info.loginUrl) -> FIELD_LOGIN_URL
        !isCurrencyValid(info.currencySymbol) -> FIELD_CURRENCY
        else -> null
    }
}

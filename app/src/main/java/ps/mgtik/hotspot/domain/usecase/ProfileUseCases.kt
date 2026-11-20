package ps.mgtik.hotspot.domain.usecase

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.IdempotencyKeyProvider
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.repository.IProfileRepository
import javax.inject.Inject

/**
 * مجموعة حالات الاستخدام الخاصة بإدارة الباقات (§4).
 * تم التطهير الهندسي لدعم الحقن التلقائي والتحقق الصارم من صحة البيانات.
 */

class GetProfilesUseCase @Inject constructor(
    private val profileRepository: IProfileRepository
) {
    operator fun invoke(includeArchived: Boolean = false, categoryId: String? = null): Flow<List<Profile>> = 
        profileRepository.getProfiles(includeArchived, categoryId)
}

/** العدّاد التسلسلي الدائم لكل بروفايل (معرّف البروفايل ← آخر رقم مستخدم). */
class ObserveUsernameSequencesUseCase @Inject constructor(
    private val profileRepository: IProfileRepository
) {
    operator fun invoke(): Flow<Map<String, Long>> = profileRepository.observeUsernameSequences()
}

class ResetUsernameSequenceUseCase @Inject constructor(
    private val profileRepository: IProfileRepository
) {
    suspend operator fun invoke(profileId: String): AppResult<Unit> {
        if (profileId.isBlank()) return AppResult.Error(ApiError.validationError("Profile is required"))
        return profileRepository.resetUsernameSequence(profileId)
    }
}

class CreateProfileUseCase @Inject constructor(
    private val profileRepository: IProfileRepository
) {
    suspend operator fun invoke(
        profile: Profile,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey(),
        skipQueue: Boolean = false,
        linkExisting: Boolean = false
    ): AppResult<Unit> {
        // التحقق من صحة اسم الباقة التقني (لا يسمح برموز خاصة معينة في ميكروتك)
        if (profile.linkedProfileName.isBlank() || profile.displayNameAr.isBlank()) {
            return AppResult.Error(ApiError.validationError("يرجى إدخال اسم الباقة التقني والاسم المعروض بالعربية"))
        }
        if (!ProfileInputValidator.isRouterNameValid(profile.linkedProfileName)) {
            return AppResult.Error(ApiError.validationError("اسم الباقة على الراوتر يجب أن يحتوي فقط على أحرف وأرقام وخطوط"))
        }
        return profileRepository.createCloudProfile(profile, idempotencyKey, skipQueue, linkExisting)
    }
}

/** الباقات الموجودة على الراوتر (لخيار الربط في نافذة إنشاء الباقة). */
class GetRouterProfileOptionsUseCase @Inject constructor(
    private val profileRepository: IProfileRepository
) {
    suspend operator fun invoke(): AppResult<List<RouterProfileOption>> = profileRepository.getRouterProfileOptions()
}

/** Limitations الموجودة على الراوتر (للاختيار منها عند إنشاء باقة). */
class GetRouterLimitationNamesUseCase @Inject constructor(
    private val profileRepository: IProfileRepository
) {
    suspend operator fun invoke(): AppResult<List<String>> = profileRepository.getRouterLimitationNames()
}

class UpdateProfileUseCase @Inject constructor(
    private val profileRepository: IProfileRepository
) {
    suspend operator fun invoke(
        profileId: String, 
        fieldsToUpdate: Map<String, Any?>,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey(),
        skipQueue: Boolean = false
    ): AppResult<Unit> =
        profileRepository.updateCloudProfile(profileId, fieldsToUpdate, idempotencyKey, skipQueue)
}

/** إعادة تسمية الاسم التقني للباقة على الراوتر والسحابة؛ تمر عبر الطابور افتراضياً (skipQueue = false). */
class MigrateProfileRenameUseCase @Inject constructor(
    private val profileRepository: IProfileRepository
) {
    suspend operator fun invoke(
        profileId: String,
        newProfileName: String,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey(),
        skipQueue: Boolean = false,
        repointUsers: Boolean = true
    ): AppResult<Unit> = profileRepository.migrateProfileRename(profileId, newProfileName, idempotencyKey, skipQueue, repointUsers)
}

class PreviewReferenceChangeUseCase @Inject constructor(private val profileRepository: IProfileRepository) {
    suspend operator fun invoke(profileId: String, newProfileName: String) = profileRepository.previewReferenceChange(profileId, newProfileName)
}

class DeleteProfileUseCase @Inject constructor(
    private val profileRepository: IProfileRepository
) {
    suspend operator fun invoke(
        profileId: String, 
        alsoDeleteFromRouter: Boolean = false, 
        force: Boolean = false,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey(),
        skipQueue: Boolean = false
    ): AppResult<Unit> {
        // منع حذف الباقة الافتراضية "default"
        if (profileId.lowercase() == "default") {
            return AppResult.Error(ApiError.validationError("لا يمكن حذف الباقة الافتراضية للنظام"))
        }
        return profileRepository.deleteCloudProfile(profileId, alsoDeleteFromRouter, force, idempotencyKey, skipQueue)
    }
}

class ReconcileRouterProfilesUseCase @Inject constructor(
    private val profileRepository: IProfileRepository
) {
    suspend operator fun invoke(): AppResult<Unit> = profileRepository.reconcileRouterProfiles()
}

class GetProfileCategoriesUseCase @Inject constructor(
    private val profileRepository: IProfileRepository
) {
    operator fun invoke(): Flow<List<ProfileCategory>> = profileRepository.getProfileCategories()
}

class SaveProfileCategoryUseCase @Inject constructor(
    private val profileRepository: IProfileRepository
) {
    suspend operator fun invoke(
        category: ProfileCategory,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> {
        if (category.nameAr.isBlank()) {
            return AppResult.Error(ApiError.validationError("يرجى إدخال اسم الفئة بالعربية"))
        }
        return profileRepository.saveProfileCategory(category, idempotencyKey)
    }
}

class GetProfileClassificationsUseCase @Inject constructor(
    private val profileRepository: IProfileRepository
) {
    operator fun invoke(): Flow<List<ProfileClassification>> = profileRepository.getProfileClassifications()
}

class SaveProfileClassificationUseCase @Inject constructor(
    private val profileRepository: IProfileRepository
) {
    suspend operator fun invoke(
        classification: ProfileClassification,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> {
        if (classification.nameAr.isBlank()) {
            return AppResult.Error(ApiError.validationError("يرجى إدخال اسم التصنيف بالعربية"))
        }
        return profileRepository.saveProfileClassification(classification, idempotencyKey)
    }
}

class ResolveUnitPriceUseCase @Inject constructor(private val repo: IProfileRepository) {
    suspend operator fun invoke(profileId: String, ownerId: String?, context: SaleContext) = repo.resolveQuote(profileId, ownerId, context)
}

class SaveCategoryOwnerPriceUseCase @Inject constructor(private val repo: IProfileRepository) {
    suspend operator fun invoke(categoryId: String, ownerId: String, context: SaleContext, priceMinor: Long, reason: String? = null) =
        repo.saveCategoryOwnerPrice(categoryId, ownerId, context, priceMinor, reason)
}

class SetPricingRuleActiveUseCase @Inject constructor(private val repo: IProfileRepository) {
    suspend operator fun invoke(ruleId: String, active: Boolean) = repo.setPricingRuleActive(ruleId, active)
}

class DeletePricingRuleUseCase @Inject constructor(private val repo: IProfileRepository) {
    suspend operator fun invoke(ruleId: String) = repo.deletePricingRule(ruleId)
}

class GetCategoryPricingRulesUseCase @Inject constructor(private val repo: IProfileRepository) {
    operator fun invoke(categoryId: String): Flow<List<PricingRule>> = repo.getCategoryPricingRules(categoryId)
}

class SetClassificationStatusUseCase @Inject constructor(private val repo: IProfileRepository) {
    suspend operator fun invoke(key: String, status: CategoryStatus) = repo.setClassificationStatus(key, status)
}

class CountProfilesInClassificationUseCase @Inject constructor(private val repo: IProfileRepository) {
    suspend operator fun invoke(key: String) = repo.countProfilesInClassification(key)
}

class PreviewClassificationApplyUseCase @Inject constructor(private val repo: IProfileRepository) {
    suspend operator fun invoke(key: String) = repo.previewClassificationApply(key)
}

class ApplyClassificationUseCase @Inject constructor(private val repo: IProfileRepository) {
    suspend operator fun invoke(key: String) = repo.applyClassification(key)
}

class GetPricingRulesUseCase @Inject constructor(private val repo: IProfileRepository) {
    operator fun invoke(profileId: String): Flow<List<PricingRule>> = repo.getPricingRules(profileId)
}

class SetProfileStatusUseCase @Inject constructor(private val repo: IProfileRepository) {
    suspend operator fun invoke(profileId: String, status: ProfileStatus) = repo.setProfileStatus(profileId, status)
}

class SetCategoryStatusUseCase @Inject constructor(private val repo: IProfileRepository) {
    suspend operator fun invoke(categoryId: String, status: CategoryStatus) = repo.setCategoryStatus(categoryId, status)
}

class DeleteProfileCategoryUseCase @Inject constructor(private val repo: IProfileRepository) {
    suspend operator fun invoke(categoryId: String, idempotencyKey: String = IdempotencyKeyProvider.generateKey()) =
        repo.deleteProfileCategory(categoryId, idempotencyKey)
}

class GetProfileUsageUseCase @Inject constructor(private val repo: IProfileRepository) {
    suspend operator fun invoke(profileIds: List<String>) = repo.getProfileUsage(profileIds)
}

class GetCategoryHistoryUseCase @Inject constructor(private val repo: IProfileRepository) {
    operator fun invoke(categoryId: String) = repo.getCategoryHistory(categoryId)
}

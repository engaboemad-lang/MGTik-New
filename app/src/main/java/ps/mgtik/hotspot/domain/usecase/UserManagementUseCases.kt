package ps.mgtik.hotspot.domain.usecase

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.subscription.SubscriptionRules
import ps.mgtik.hotspot.core.result.IdempotencyKeyProvider
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.repository.IUserRepository

class ListManagedUsersUseCase @Inject constructor(private val userRepository: IUserRepository) {
    operator fun invoke(ownerId: String? = null, status: UserRecordStatus? = null): Flow<List<LocalUserRecord>> =
        userRepository.listManagedUsers(ownerId, status)
}

class ListNullUsersUseCase @Inject constructor(private val userRepository: IUserRepository) {
    operator fun invoke(): Flow<List<DiscoveryRecord>> = userRepository.listNullUsers()
}

class EnableUserUseCase @Inject constructor(private val userRepository: IUserRepository) {
    suspend operator fun invoke(
        recordUuid: String,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> = userRepository.enableUser(recordUuid, idempotencyKey)
}

class DisableUserUseCase @Inject constructor(private val userRepository: IUserRepository) {
    suspend operator fun invoke(
        recordUuid: String,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> = userRepository.disableUser(recordUuid, idempotencyKey)
}

class RenewUserPackageUseCase @Inject constructor(private val userRepository: IUserRepository) {
    suspend operator fun invoke(
        recordUuid: String,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> = userRepository.renewUserPackage(recordUuid, idempotencyKey)
}

class ArchiveUserUseCase @Inject constructor(private val userRepository: IUserRepository) {
    suspend operator fun invoke(
        recordUuid: String,
        reason: String,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<UserOperationOutcome> = userRepository.archiveUser(recordUuid, reason, idempotencyKey)
}

class EditUserUseCase @Inject constructor(private val userRepository: IUserRepository) {
    suspend operator fun invoke(
        request: UserEditRequest,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<UserOperationOutcome> = userRepository.editUser(request, idempotencyKey)
}

class ReconcileUserRegistryUseCase @Inject constructor(private val userRepository: IUserRepository) {
    suspend operator fun invoke(): AppResult<Unit> = userRepository.reconcileUserRegistry()
}

// Keeping legacy or additional ones if needed, but the technical report focuses on the above.
class GetArchivedUsersUseCase @Inject constructor(private val userRepository: IUserRepository) {
    suspend operator fun invoke(pageSize: Int = 30, cursor: String? = null): AppResult<ArchivedUsersPage> =
        userRepository.getArchivedUsersPage(pageSize, cursor)
}

/** إنشاء مستخدم عادي فردي (NORMAL) بمالك admin بلا دفعة. مفتاح العملية = معرّف السجل: إعادة المحاولة لا تكرّر. */
class CreateNormalUserUseCase @Inject constructor(private val userRepository: IUserRepository) {
    suspend operator fun invoke(
        draft: NewNormalUser,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<CreatedUser> = userRepository.createNormalUser(draft, idempotencyKey)
}

/**
 * إنشاء مشترك شهري من S16: يمرّ بـ [CreateSubscriptionUseCase] نفسه (نفس التحقق والمحرّك وسجلات المستخدم والاشتراك والفاتورة
 * وأمر التفعيل على الراوتر)، فالنتيجة مطابقة لإنشائه من لوحة الفوترة. الفرق الوحيد: مرجع مالك admin الفعلي،
 * وفحص تعارض اسم الدخول مع المستخدمين العاديين والأرشيف والراوتر (المحرّك يفحص الاشتراكات الأخرى فقط).
 * لا منطق اشتراك مكرر هنا.
 */
class CreateManagedSubscriberUseCase @Inject constructor(
    private val userRepository: IUserRepository,
    private val createSubscription: CreateSubscriptionUseCase
) {
    suspend operator fun invoke(draft: NewSubscription, subscriptionId: String): AppResult<Subscription> {
        // أخطاء المدخلات أولاً بلا رحلة سحابة (ويُعاد التحقق نفسه داخل المحرّك قبل الكتابة)
        SubscriptionRules.validateNew(draft, java.time.Instant.now())?.let {
            return AppResult.Error(ApiError(code = it, message = it, retryable = false))
        }
        val owner = when (val o = userRepository.resolveAdminOwner()) {
            is AppResult.Success -> o.data
            is AppResult.Error -> return o
            is AppResult.Loading -> return AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }
        when (val u = userRepository.ensureUsernameAvailable(draft.username, subscriptionId)) {
            is AppResult.Error -> return u
            else -> Unit
        }
        return createSubscription(draft.copy(ownerId = owner.ownerId, ownerName = owner.name), subscriptionId)
    }
}

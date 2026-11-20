package ps.mgtik.hotspot.data.repository

import kotlinx.coroutines.flow.*
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.IdempotencyKeyProvider
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.repository.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MgtikRepositoryImpl - التنفيذ الفعلي لـ IMgtikRepository الموحد.
 */
@Singleton
class MgtikRepositoryImpl @Inject constructor(
    private val generationRepo: IGenerationRepository,
    private val subscriptionRepo: ISubscriptionRepository,
    private val billingRepo: IBillingRepository,
    private val profileRepo: IProfileRepository,
    private val settingsRepo: ISettingsRepository,
    private val activeSessionRepo: IActiveSessionRepository,
    private val orderRepo: IOrderRepository,
    private val ownerRepo: IOwnerRepository,
    private val userRepo: IUserRepository,
    private val notificationRepo: INotificationRepository,
    private val routerRepo: IRouterRepository,
    private val templateRepo: ITemplateRepository,
    private val historyRepo: IHistoryRepository
) : IMgtikRepository,
    IGenerationRepository by generationRepo,
    ISubscriptionRepository by subscriptionRepo,
    IBillingRepository by billingRepo,
    IProfileRepository by profileRepo,
    ISettingsRepository by settingsRepo,
    IActiveSessionRepository by activeSessionRepo,
    IOrderRepository by orderRepo,
    IOwnerRepository by ownerRepo,
    IUserRepository by userRepo,
    INotificationRepository by notificationRepo,
    IRouterRepository by routerRepo,
    ITemplateRepository by templateRepo,
    IHistoryRepository by historyRepo {

    override fun getSubscriptionsRealtime(): Flow<List<Subscription>> = 
        subscriptionRepo.getSubscriptions()

    override fun getInvoicesRealtime(): Flow<List<Invoice>> = 
        billingRepo.getInvoices()

    override fun getRouterConfig(): Flow<RouterConfig> = settingsRepo.getRouterConfig()

    override suspend fun saveVoucherBatch(batch: VoucherBatch, vouchers: List<Voucher>): AppResult<Unit> {
        val req = GenerationCommandRequest(
            profileId = batch.cloudProfileId,
            profileName = batch.profileName,
            count = batch.requestedQuantity
        )
        return generationRepo.submitGenerationCommand(req, IdempotencyKeyProvider.generateKey()).let { 
            AppResult.Success(Unit)
        }
    }
}

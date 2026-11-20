package ps.mgtik.hotspot.domain.repository

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.*

/**
 * IMgtikRepository - العقد المعماري الأساسي (Unified Domain Contract) (§1)
 * يجمع كافة العقود الفرعية للوحدات الخمس + إدارة الراوتر + نظام القوالب.
 * تم التدقيق هندسياً لضمان توفير تدفقات بيانات متسقة (Orchestrated Flows).
 */
interface IMgtikRepository :
    IGenerationRepository,
    ISubscriptionRepository,
    IBillingRepository,
    IProfileRepository,
    ISettingsRepository,
    IActiveSessionRepository,
    IOrderRepository,
    IOwnerRepository,
    IUserRepository,
    INotificationRepository,
    IRouterRepository,
    ITemplateRepository,
    IHistoryRepository {

    fun getSubscriptionsRealtime(): Flow<List<Subscription>>
    fun getInvoicesRealtime(): Flow<List<Invoice>>
    
    // عمليات الأوركسترا (Orchestration) العابرة للوحدات
    suspend fun saveVoucherBatch(batch: VoucherBatch, vouchers: List<Voucher>): AppResult<Unit>
}

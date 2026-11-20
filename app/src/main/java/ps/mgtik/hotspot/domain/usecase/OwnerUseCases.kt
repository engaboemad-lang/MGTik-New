package ps.mgtik.hotspot.domain.usecase

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.IdempotencyKeyProvider
import ps.mgtik.hotspot.domain.model.OwnerAccount
import ps.mgtik.hotspot.domain.model.OwnerLedgerEntry
import ps.mgtik.hotspot.domain.model.OwnerWorkspace
import ps.mgtik.hotspot.domain.repository.IOwnerRepository
import javax.inject.Inject

class GetOwnersSummaryUseCase @Inject constructor(private val ownerRepository: IOwnerRepository) {
    operator fun invoke(): Flow<List<OwnerAccount>> = ownerRepository.getOwnersSummary()
}

class GetOwnerAccountUseCase @Inject constructor(private val ownerRepository: IOwnerRepository) {
    operator fun invoke(ownerId: String): Flow<OwnerAccount?> = ownerRepository.getOwnerAccount(ownerId)
}

class GetOwnerWorkspaceUseCase @Inject constructor(private val ownerRepository: IOwnerRepository) {
    operator fun invoke(ownerId: String, dateRange: Pair<String, String>? = null): Flow<OwnerWorkspace?> = 
        ownerRepository.getOwnerWorkspace(ownerId, dateRange)
}

class GetOwnerLedgerUseCase @Inject constructor(private val ownerRepository: IOwnerRepository) {
    operator fun invoke(ownerId: String): Flow<List<OwnerLedgerEntry>> = ownerRepository.getOwnerLedger(ownerId)
}

class SetOwnerOpeningBalanceUseCase @Inject constructor(private val ownerRepository: IOwnerRepository) {
    suspend operator fun invoke(
        ownerId: String, 
        amountMinor: Long, 
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> {
        // المحفظة مدفوعة مقدماً: الافتتاحي لا يكون سالباً (مديونية تُسجَّل بفواتير لا برصيد)
        if (amountMinor < 0) return AppResult.Error(ApiError.validationError("Opening balance cannot be negative"))
        return ownerRepository.setOwnerOpeningBalance(ownerId, amountMinor, idempotencyKey)
    }
}

class RecordOwnerAdvanceUseCase @Inject constructor(private val ownerRepository: IOwnerRepository) {
    suspend operator fun invoke(
        ownerId: String, 
        amountMinor: Long, 
        notes: String? = null,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> {
        if (amountMinor <= 0) return AppResult.Error(ApiError.validationError("Advance amount must be greater than zero"))
        return ownerRepository.recordOwnerAdvance(ownerId, amountMinor, notes, idempotencyKey)
    }
}

class RefundFromOwnerCreditUseCase @Inject constructor(private val ownerRepository: IOwnerRepository) {
    suspend operator fun invoke(
        ownerId: String,
        amountMinor: Long,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey(),
        skipQueue: Boolean = false
    ): AppResult<Unit> {
        if (amountMinor <= 0) return AppResult.Error(ApiError.validationError("Refund amount must be greater than zero"))
        return ownerRepository.refundFromOwnerCredit(ownerId, amountMinor, idempotencyKey, skipQueue)
    }
}

class SaveOwnerUseCase @Inject constructor(private val ownerRepository: IOwnerRepository) {
    suspend operator fun invoke(account: OwnerAccount): AppResult<Unit> {
        if (!OwnerInputValidator.isNameValid(account.name) || !OwnerInputValidator.isPhoneValid(account.phone)) {
            return AppResult.Error(ApiError.validationError("Owner name or phone is invalid"))
        }
        return ownerRepository.saveOwner(account) // يرفض رقم الهاتف المكرر (ERR_OWNER_PHONE_DUPLICATE)
    }
}

class UpdateOwnerUseCase @Inject constructor(private val ownerRepository: IOwnerRepository) {
    suspend operator fun invoke(ownerId: String, fields: Map<String, Any?>): AppResult<Unit> = 
        ownerRepository.updateOwner(ownerId, fields)
}

class DeleteOwnerUseCase @Inject constructor(private val ownerRepository: IOwnerRepository) {
    suspend operator fun invoke(ownerId: String): AppResult<Unit> = ownerRepository.deleteOwner(ownerId)
}

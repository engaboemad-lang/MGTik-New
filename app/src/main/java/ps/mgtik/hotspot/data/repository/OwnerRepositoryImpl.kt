package ps.mgtik.hotspot.data.repository

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.data.cloud.source.CloudOwnerDataSource
import ps.mgtik.hotspot.data.offline.CommandCodec
import ps.mgtik.hotspot.data.offline.OwnerAdvancePayload
import ps.mgtik.hotspot.data.offline.OwnerBalancePayload
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.repository.IAuditRepository
import ps.mgtik.hotspot.domain.repository.IOwnerRepository
import ps.mgtik.hotspot.domain.repository.IOfflineCommandRepository
import ps.mgtik.hotspot.domain.repository.IOfflineScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OwnerRepositoryImpl - تطبيق مستودع الملاك والموزعين والوكلاء مالياً (§4.18)
 */
@Singleton
class OwnerRepositoryImpl @Inject constructor(
    private val cloudDataSource: CloudOwnerDataSource,
    private val offlineRepo: IOfflineCommandRepository,
    private val offlineScheduler: IOfflineScheduler,
    private val auditRepo: IAuditRepository
) : IOwnerRepository {

    override fun getOwnersSummary(): Flow<List<OwnerAccount>> =
        cloudDataSource.getOwnersSummary()

    override fun getOwnerAccount(ownerId: String): Flow<OwnerAccount?> =
        cloudDataSource.getOwnerAccount(ownerId)

    private suspend fun queueCommand(type: OfflineCommandType, payload: String, key: String) {
        val command = OfflineCommand(
            type = type,
            payloadJson = payload,
            idempotencyKey = key
        )
        offlineRepo.addCommand(command)
        offlineScheduler.scheduleSync()
        
        auditRepo.logAction("OFFLINE_QUEUE", "Queued financial command: $type for key: $key")
    }

    override suspend fun setOwnerOpeningBalance(
        ownerId: String,
        amountMinor: Long,
        idempotencyKey: String,
        skipQueue: Boolean
    ): AppResult<Unit> {
        if (skipQueue) {
            val result = cloudDataSource.setOwnerOpeningBalance(ownerId, amountMinor, idempotencyKey)
            if (result is AppResult.Success) {
                auditRepo.logAction("FINANCE_SET_BALANCE", "Set opening balance for $ownerId to $amountMinor")
            }
            return result
        }
        queueCommand(OfflineCommandType.SET_OWNER_OPENING_BALANCE, CommandCodec.encode(OwnerBalancePayload(ownerId, amountMinor)), idempotencyKey)
        return AppResult.Success(Unit)
    }

    override suspend fun recordOwnerAdvance(
        ownerId: String,
        amountMinor: Long,
        notes: String?,
        idempotencyKey: String,
        skipQueue: Boolean
    ): AppResult<Unit> {
        if (skipQueue) {
            val result = cloudDataSource.recordOwnerAdvance(ownerId, amountMinor, notes, idempotencyKey)
            if (result is AppResult.Success) {
                auditRepo.logAction("FINANCE_ADVANCE", "Recorded advance for $ownerId: $amountMinor. Notes: $notes")
            }
            return result
        }
        queueCommand(OfflineCommandType.RECORD_OWNER_ADVANCE, CommandCodec.encode(OwnerAdvancePayload(ownerId, amountMinor, notes)), idempotencyKey)
        return AppResult.Success(Unit)
    }

    override suspend fun refundFromOwnerCredit(
        ownerId: String,
        amountMinor: Long,
        idempotencyKey: String,
        skipQueue: Boolean
    ): AppResult<Unit> {
        if (skipQueue) {
            val result = cloudDataSource.refundFromOwnerCredit(ownerId, amountMinor, idempotencyKey)
            if (result is AppResult.Success) {
                auditRepo.logAction("FINANCE_REFUND", "Refunded from credit for $ownerId: $amountMinor")
            }
            return result
        }
        queueCommand(OfflineCommandType.REFUND_FROM_OWNER_CREDIT, CommandCodec.encode(OwnerBalancePayload(ownerId, amountMinor)), idempotencyKey)
        return AppResult.Success(Unit)
    }

    override fun getOwnerWorkspace(
        ownerId: String,
        dateRange: Pair<String, String>?
    ): Flow<OwnerWorkspace?> =
        cloudDataSource.getOwnerWorkspace(ownerId, dateRange)

    override fun getOwnerLedger(ownerId: String): Flow<List<OwnerLedgerEntry>> =
        cloudDataSource.getOwnerLedger(ownerId)

    override suspend fun saveOwner(account: OwnerAccount): AppResult<Unit> {
        return cloudDataSource.saveOwner(account).also {
            if (it is AppResult.Success) auditRepo.logAction("OWNER_SAVE", "Saved owner: ${account.name}")
        }
    }

    override suspend fun updateOwner(ownerId: String, fieldsToUpdate: Map<String, Any?>): AppResult<Unit> {
        return cloudDataSource.updateOwner(ownerId, fieldsToUpdate).also {
            if (it is AppResult.Success) auditRepo.logAction("OWNER_UPDATE", "Updated owner: $ownerId")
        }
    }

    override suspend fun deleteOwner(ownerId: String): AppResult<Unit> =
        cloudDataSource.deleteOwner(ownerId).also {
            if (it is AppResult.Success) auditRepo.logAction("OWNER_DELETE", "Deleted owner: $ownerId")
        }
}

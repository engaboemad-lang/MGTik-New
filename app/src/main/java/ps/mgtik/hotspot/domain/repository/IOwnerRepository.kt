package ps.mgtik.hotspot.domain.repository

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.OwnerAccount
import ps.mgtik.hotspot.domain.model.OwnerLedgerEntry
import ps.mgtik.hotspot.domain.model.OwnerWorkspace

/**
 * IOwnerRepository - عقد إدارة حسابات الملاك والموزعين مالياً (§4.18)
 * تم التحديث لدعم التنفيذ المباشر (skipQueue) لتجنب حلقات التكرار في نظام الـ Offline.
 * skipQueue (معنى ثابت في كل المستودعات): false → تمر العملية عبر الطابور/WorkManager، true → تنفيذ مباشر بلا طابور.
 */
interface IOwnerRepository {
    // --- الحسابات (Accounts) ---
    fun getOwnersSummary(): Flow<List<OwnerAccount>>
    fun getOwnerAccount(ownerId: String): Flow<OwnerAccount?>
    
    suspend fun setOwnerOpeningBalance(ownerId: String, amountMinor: Long, idempotencyKey: String, skipQueue: Boolean = false): AppResult<Unit>
    suspend fun recordOwnerAdvance(ownerId: String, amountMinor: Long, notes: String?, idempotencyKey: String, skipQueue: Boolean = false): AppResult<Unit>
    suspend fun refundFromOwnerCredit(ownerId: String, amountMinor: Long, idempotencyKey: String, skipQueue: Boolean = false): AppResult<Unit>
    
    // --- مساحة العمل وكشف الحساب ---
    fun getOwnerWorkspace(ownerId: String, dateRange: Pair<String, String>?): Flow<OwnerWorkspace?>
    fun getOwnerLedger(ownerId: String): Flow<List<OwnerLedgerEntry>>
    
    // --- العمليات الأساسية ---
    suspend fun saveOwner(account: OwnerAccount): AppResult<Unit>
    suspend fun updateOwner(ownerId: String, fieldsToUpdate: Map<String, Any?>): AppResult<Unit>
    suspend fun deleteOwner(ownerId: String): AppResult<Unit>
}

package ps.mgtik.hotspot.data.cloud.source

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.safeApiCall
import ps.mgtik.hotspot.data.mapping.SyncErrorReporter
import ps.mgtik.hotspot.data.mapping.toEnumOrFailSafe
import ps.mgtik.hotspot.data.mapping.toEnumOrNull
import ps.mgtik.hotspot.domain.model.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CloudGenerationDataSource - تنفيذ عمليات التوليد ومحرك التنفيذ (§4.9).
 */
@Singleton
class CloudGenerationDataSource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val reporter: SyncErrorReporter
) {
    private val executionCollection = firestore.collection("executions")
    private val batchCollection = firestore.collection("batches")
    private val defaultsCollection = firestore.collection("profile_defaults")

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())

    suspend fun startExecution(
        contextId: String,
        flowType: WorkflowFlowType,
        steps: List<String>
    ): AppResult<Execution> = safeApiCall {
        val executionId = UUID.randomUUID().toString()
        val execution = Execution(
            executionId = executionId,
            contextId = contextId,
            flowType = flowType,
            status = ExecutionStatus.RUNNING,
            steps = steps.map { ExecutionStepRecord(step = it) },
            startedAt = dateFormatter.format(Instant.now())
        )
        executionCollection.document(executionId).set(execution).await()
        execution
    }

    suspend fun reportExecutionStep(
        executionId: String,
        stepName: String,
        result: StepStatus,
        detail: Map<String, String>?,
        errorCode: String?
    ): AppResult<Unit> = safeApiCall {
        val docRef = executionCollection.document(executionId)
        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(docRef)
            val steps = snapshot.get("steps") as? List<Map<String, Any?>> ?: return@runTransaction
            val updatedSteps = steps.map { step ->
                if (step["step"] == stepName) {
                    step.toMutableMap().apply {
                        put("status", result.name)
                        put("completed_at", FieldValue.serverTimestamp())
                        errorCode?.let { put("error_code", it) }
                        detail?.let { put("detail", it) }
                    }
                } else step
            }
            transaction.update(docRef, "steps", updatedSteps)
        }.await()
        Unit
    }

    fun getExecutionFlow(executionId: String): Flow<Execution?> {
        return executionCollection.document(executionId).snapshots().map { doc ->
            if (doc.exists()) mapExecution(doc) else null
        }
    }

    fun listExecutionsFlow(
        contextId: String?,
        flowType: WorkflowFlowType?,
        status: ExecutionStatus?
    ): Flow<List<Execution>> {
        var query: Query = executionCollection
        contextId?.let { query = query.whereEqualTo("contextId", it) }
        flowType?.let { query = query.whereEqualTo("flowType", it.name) }
        status?.let { query = query.whereEqualTo("status", it.name) }

        return query.orderBy("startedAt", Query.Direction.DESCENDING).snapshots().map { snapshot ->
            snapshot.documents.mapNotNull { doc -> mapExecution(doc) }
        }
    }

    suspend fun retryExecution(
        executionId: String,
        idempotencyKey: String
    ): AppResult<RetryExecutionResponse> = safeApiCall {
        val docRef = executionCollection.document(executionId)
        var response: RetryExecutionResponse? = null
        
        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(docRef)
            if (snapshot.exists()) {
                val nextAttempt = (snapshot.getLong("attemptNumber") ?: 1L) + 1
                
                transaction.update(docRef, "status", ExecutionStatus.RUNNING.name)
                transaction.update(docRef, "attemptNumber", nextAttempt)
                transaction.update(docRef, "retry_idempotency_key", idempotencyKey)
                
                response = RetryExecutionResponse(
                    executionId = executionId,
                    status = ExecutionStatus.RUNNING,
                    requestedQuantity = snapshot.getLong("requestedQuantity")?.toInt() ?: 0,
                    successfulQuantity = snapshot.getLong("successfulQuantity")?.toInt() ?: 0,
                    failedQuantity = 0,
                    isRetryable = true
                )
            }
        }.await()
        
        response ?: throw IllegalStateException("CLOUD_EXECUTION_NOT_FOUND")
    }

    suspend fun peekNextSequence(cloudProfileId: String): AppResult<Long> = safeApiCall {
        val doc = defaultsCollection.document(cloudProfileId).get().await()
        val last = doc.getLong("last_assigned_sequence") ?: 0L
        last + 1
    }

    fun observeBatchesFlow(): Flow<List<VoucherBatch>> {
        return batchCollection.orderBy("created_at", Query.Direction.DESCENDING).snapshots().map { snapshot ->
            snapshot.documents.map { doc ->
                VoucherBatch(
                    id = doc.id,
                    batchNumber = doc.getString("batch_number") ?: doc.id,
                    cloudProfileId = doc.getString("cloud_profile_id") ?: "",
                    profileName = doc.getString("profile_name") ?: "Profile",
                    ownerId = doc.getString("owner_id") ?: "",
                    requestedQuantity = (doc.getLong("requested_quantity") ?: 0L).toInt(),
                    successfulQuantity = (doc.getLong("successful_quantity") ?: 0L).toInt(),
                    failedQuantity = (doc.getLong("failed_quantity") ?: 0L).toInt(),
                    pricePerVoucherMinor = doc.getLong("price_per_voucher_minor") ?: 0L,
                    totalAmountMinor = doc.getLong("total_amount_minor") ?: 0L,
                    status = doc.getString("status").toEnumOrFailSafe(VoucherBatchStatus.FAILED), // فشل آمن؛ الناقصة لم تعد PENDING
                    createdAt = dateFormatter.format((doc.getTimestamp("created_at")?.toDate() ?: java.util.Date()).toInstant())
                )
            }
        }
    }

    /** كروت دفعة من Firestore (batches/{id}/vouchers) — احتياطي حين لا تتوفر محلياً (جهاز آخر/بعد مسح الكاش). */
    suspend fun fetchBatchVouchers(batchId: String): AppResult<List<Voucher>> = safeApiCall {
        val batchDoc = batchCollection.document(batchId).get().await()
        val price = batchDoc.getLong("price_per_voucher_minor") ?: 0L
        batchCollection.document(batchId).collection("vouchers").orderBy("seq").get().await().documents.map { doc ->
            Voucher(
                code = doc.getString("username").orEmpty(),
                password = doc.getString("password").orEmpty(),
                profileName = doc.getString("profile_name").orEmpty(),
                priceMinor = price,
                batchId = batchId,
                serialNumber = doc.getLong("seq") ?: 0L
            )
        }
    }

    /**
     * flowType ناقص/مجهول يرفض السجل (null + SYNC_ERROR): لا يُعتبر GENERATION. حالة التنفيذ الناقصة/المجهولة
     * تصير FAILED (فشل آمن لا يدّعي تقدماً ولا نجاحاً) بدل QUEUED/COMPLETED المُفترضة سابقاً.
     */
    private fun mapExecution(doc: com.google.firebase.firestore.DocumentSnapshot): Execution? {
        val rawFlow = doc.getString("flowType")
        val flowType = rawFlow.toEnumOrNull<WorkflowFlowType>()
        if (flowType == null) {
            reporter.rejected("executions", doc.id, if (rawFlow.isNullOrBlank()) "missing flowType" else "unknown flowType '$rawFlow'")
            return null
        }
        return Execution(
            executionId = doc.id,
            contextId = doc.getString("contextId") ?: "",
            flowType = flowType,
            attemptNumber = (doc.getLong("attemptNumber") ?: 1L).toInt(),
            status = doc.getString("status").toEnumOrFailSafe(ExecutionStatus.FAILED),
            requestedQuantity = doc.getLong("requestedQuantity")?.toInt(),
            successfulQuantity = doc.getLong("successfulQuantity")?.toInt(),
            failedQuantity = doc.getLong("failedQuantity")?.toInt(),
            errorCode = doc.getString("errorCode"),
            errorMessage = doc.getString("errorMessage"),
            startedAt = doc.getString("startedAt") ?: "",
            completedAt = doc.getString("completedAt")
        )
    }
}

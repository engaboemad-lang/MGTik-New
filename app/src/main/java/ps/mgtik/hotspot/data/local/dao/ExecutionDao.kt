package ps.mgtik.hotspot.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.data.local.entity.ExecutionEntity
import ps.mgtik.hotspot.data.local.entity.ExecutionStepEntity

@Dao
interface ExecutionDao {
    @Transaction
    @Query("SELECT * FROM executions WHERE executionId = :executionId")
    fun observeExecutionWithSteps(executionId: String): Flow<ExecutionWithSteps?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExecution(execution: ExecutionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSteps(steps: List<ExecutionStepEntity>)

    @Query("DELETE FROM execution_steps WHERE execution_id = :executionId")
    suspend fun deleteSteps(executionId: String)

    @Transaction
    suspend fun insertExecutionWithSteps(execution: ExecutionEntity, steps: List<ExecutionStepEntity>) {
        insertExecution(execution)
        // حذف صريح للخطوات القديمة: الساغا تحفظ حالتها عند كل انتقال، ولا نعتمد على cascade الاستبدال كي لا تتكرر
        deleteSteps(execution.executionId)
        insertSteps(steps)
    }

    @Query("DELETE FROM executions")
    suspend fun clearAll()
}

data class ExecutionWithSteps(
    @Embedded val execution: ExecutionEntity,
    @Relation(
        parentColumn = "executionId",
        entityColumn = "execution_id"
    )
    val steps: List<ExecutionStepEntity>
)

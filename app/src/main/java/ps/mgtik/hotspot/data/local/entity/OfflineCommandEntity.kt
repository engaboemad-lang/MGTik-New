package ps.mgtik.hotspot.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import ps.mgtik.hotspot.domain.model.OfflineCommand
import ps.mgtik.hotspot.domain.model.OfflineCommandType

@Entity(tableName = "offline_commands")
data class OfflineCommandEntity(
    @PrimaryKey val commandId: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    @ColumnInfo(name = "last_error") val lastError: String?
) {
    /** null عند نوع أمر غير معروف (نسخة أحدث): يُتجاهل الصف بدل تنفيذ أمر خاطئ أو إسقاط الطابور كله. */
    fun toDomainOrNull(): OfflineCommand? =
        if (enumValues<OfflineCommandType>().none { it.name == type }) null else toDomain()

    private fun toDomain(): OfflineCommand {
        return OfflineCommand(
            commandId = commandId,
            type = OfflineCommandType.valueOf(type),
            payloadJson = payloadJson,
            idempotencyKey = idempotencyKey,
            createdAt = createdAt,
            attemptCount = attemptCount,
            lastError = lastError
        )
    }

    companion object {
        fun fromDomain(command: OfflineCommand): OfflineCommandEntity {
            return OfflineCommandEntity(
                commandId = command.commandId,
                type = command.type.name,
                payloadJson = command.payloadJson,
                idempotencyKey = command.idempotencyKey,
                createdAt = command.createdAt,
                attemptCount = command.attemptCount,
                lastError = command.lastError
            )
        }
    }
}

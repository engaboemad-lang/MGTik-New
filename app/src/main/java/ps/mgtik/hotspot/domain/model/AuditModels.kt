package ps.mgtik.hotspot.domain.model

import java.util.UUID

/** مستوى خطورة سجل التدقيق (§10). الترتيب من الأخف إلى الأشد. */
enum class LogSeverity { INFO, WARNING, ERROR, CRITICAL }

/** سجل تدقيق واحد (§10): من فعل ماذا ومتى. المعرّف والوقت يُولَّدان تلقائياً عند الإنشاء. */
data class AuditLog(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val action: String,
    val detail: String,
    val userId: String,
    val severity: LogSeverity = LogSeverity.INFO
)

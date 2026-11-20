package ps.mgtik.hotspot.core.result

import java.util.UUID

/**
 * مزود مفاتيح Idempotency للعمليات المنطقية.
 * يتم إنشاء المفتاح مرة واحدة للعملية المنطقية (User Action / Logical Operation)
 * ويُعاد استخدامه في المحاولات اللاحقة (Retries) دون إنشاء مفتاح جديد لكل محاولة شبكية.
 */
object IdempotencyKeyProvider {
    fun generateKey(): String = UUID.randomUUID().toString()
}

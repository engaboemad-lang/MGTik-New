package ps.mgtik.hotspot.core.result

/** خرق قاعدة عمل داخل معاملة سحابية (يُحوَّل إلى ApiError بالكود نفسه عبر ApiError.mapThrowable). */
class RuleViolation(val code: String, message: String) : RuntimeException(message)

package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.VALIDITY_START_ASSIGNED
import ps.mgtik.hotspot.domain.model.VALIDITY_START_FIRST_AUTH

/**
 * التحقق المركزي من حقول الباقة التشغيلية (نقي). المستودع يستدعيه قبل أي كتابة، في المسار المباشر وفي مسار الطابور
 * (المنفّذ يستدعي نفس الدوال) فلا يعتمد الحفظ على تحقق الواجهة وحده. القيم الشكلية (صفر، سالب، نص بدل رقم) لا تُحفظ.
 * النتيجة: حقل Firestore ← رمز الخطأ. فارغة = صالحة.
 */
object ProfileFieldRules {
    const val ERR_TYPE = "ERR_PROFILE_FIELD_TYPE"
    const val ERR_SHARED_USERS = "ERR_PROFILE_SHARED_USERS_INVALID"
    const val ERR_RATE = "ERR_PROFILE_RATE_INVALID"
    const val ERR_VALIDITY = "ERR_PROFILE_VALIDITY_INVALID"
    const val ERR_VALIDITY_START = "ERR_PROFILE_VALIDITY_START_INVALID"
    const val ERR_VALIDITY_CONFLICT = "ERR_PROFILE_VALIDITY_CONFLICT"
    const val ERR_LIMIT_UPTIME = "ERR_PROFILE_LIMIT_UPTIME_INVALID"
    const val ERR_LIMIT_TRANSFER = "ERR_PROFILE_LIMIT_TRANSFER_INVALID"
    const val ERR_QUOTA = "ERR_PROFILE_QUOTA_INVALID"

    private const val MAX_KBPS = 10_000_000L // نفس سقف ProfileInputValidator (10 Gbps)
    /** أقصى مدة صلاحية يقبلها RouterOS (32 بت بالثواني) محوّلة إلى ساعات كاملة. */
    private const val MAX_VALIDITY_HOURS = 4_294_967_295L / 3_600L

    /** عدد صحيح من Number (من الطابور قد يصل Long/Int/Double)؛ null = ليس رقماً أو له كسر. */
    private fun whole(v: Any?): Long? = (v as? Number)?.let { n ->
        val d = n.toDouble()
        if (d.isNaN() || d.isInfinite() || d != Math.rint(d)) null else n.toLong()
    }

    /** يتحقق من كل حقل مُرسَل بمفرده (النوع والمدى). الحقول غير التشغيلية لا يفحصها هذا الملف. */
    fun validateFields(fields: Map<String, Any?>): Map<String, String> = buildMap {
        fun bool(key: String) {
            if (key in fields && fields[key] !is Boolean) put(key, ERR_TYPE)
        }
        if ("shared_users" in fields) {
            val n = whole(fields["shared_users"])
            if (n == null || n !in 1..ClassificationRules.MAX_SHARED_USERS.toLong()) put("shared_users", ERR_SHARED_USERS)
        }
        bool("shared_unlimited")
        for (key in listOf("rate_limit_rx_kbps", "rate_limit_tx_kbps")) {
            if (key in fields) {
                val n = whole(fields[key])
                if (n == null || n !in 0..MAX_KBPS) put(key, ERR_RATE)
            }
        }
        if ("validity_hours" in fields) {
            val n = whole(fields["validity_hours"])
            if (n == null || n !in 0..MAX_VALIDITY_HOURS) put("validity_hours", ERR_VALIDITY)
        }
        bool("validity_unlimited")
        if ("validity_start" in fields) {
            val v = fields["validity_start"]
            if (v !is String || (v != VALIDITY_START_FIRST_AUTH && v != VALIDITY_START_ASSIGNED)) put("validity_start", ERR_VALIDITY_START)
        }
        // null = حد ممسوح (غير مفروض)؛ غير ذلك يجب أن يكون ضمن مدى UsageLimits (لا صفر ولا سالب)
        if ("limit_uptime_seconds" in fields) {
            val v = fields["limit_uptime_seconds"]
            if (v != null && (whole(v) == null || !UsageLimits.isUptimeValid(whole(v)))) put("limit_uptime_seconds", ERR_LIMIT_UPTIME)
        }
        if ("limit_transfer_bytes" in fields) {
            val v = fields["limit_transfer_bytes"]
            if (v != null && (whole(v) == null || !UsageLimits.isTransferValid(whole(v)))) put("limit_transfer_bytes", ERR_LIMIT_TRANSFER)
        }
        if ("quota_mb" in fields) {
            val n = whole(fields["quota_mb"])
            if (n == null || n < 0 || n > Int.MAX_VALUE) put("quota_mb", ERR_QUOTA)
        }
    }

    /**
     * اتساق الحالة بعد دمج [fields] في الباقة ([after])، للحقول المُرسَلة فقط (لا يُحجب تعديل غير مرتبط بسبب بيانات قديمة):
     * صلاحية غير محدودة مع ساعات موجبة = تناقض يكتب الراوتر منه 0s ويحتفظ التطبيق بعدد مخفي.
     */
    fun validateCombination(after: Profile, fields: Set<String>): Map<String, String> = buildMap {
        val touchesValidity = "validity_unlimited" in fields || "validity_hours" in fields
        if (touchesValidity && after.validityUnlimited && after.validityHours > 0) put("validity_unlimited", ERR_VALIDITY_CONFLICT)
    }

    /** حالة باقة كاملة (إنشاء). نفس قواعد [validateFields] مطبَّقة على قيم الباقة + التناقضات. */
    fun validateProfile(p: Profile): Map<String, String> {
        val fields = linkedMapOf<String, Any?>(
            "rate_limit_rx_kbps" to p.rateLimitRxKbps,
            "rate_limit_tx_kbps" to p.rateLimitTxKbps,
            "validity_hours" to p.validityHours,
            "validity_unlimited" to p.validityUnlimited,
            "validity_start" to p.validityStart,
            "shared_unlimited" to p.sharedUnlimited,
            "limit_uptime_seconds" to p.limitUptimeSeconds,
            "limit_transfer_bytes" to p.limitTransferBytes
        )
        // عدد الأجهزة المخزَّن لا يُفحص مع غير المحدود: يُهمل ولا يمثّل الراوتر
        if (!p.sharedUnlimited) fields["shared_users"] = p.sharedUsers
        return validateFields(fields) + validateCombination(p, fields.keys)
    }
}

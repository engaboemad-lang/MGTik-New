package ps.mgtik.hotspot.data.userman

/**
 * اندفاع (Burst) اتجاه واحد في Limitation. كل الأرقام 0 = Burst معطَّل لهذا الاتجاه.
 * التسمية في RouterOS: rate-limit-burst-{rx|tx} (السرعة)، rate-limit-burst-threshold-{rx|tx} (الحد)، rate-limit-burst-time-{rx|tx} (المدة).
 * rx = رفع من العميل، tx = تنزيل إليه (نفس اتفاقية بقية الكود).
 */
data class UmBurstDir(
    val rateKbps: Int = 0,
    val thresholdKbps: Int = 0,
    val timeSeconds: Long = 0L
) {
    val enabled: Boolean get() = rateKbps > 0 || thresholdKbps > 0 || timeSeconds > 0
}

/** مشكلة تحقق في إعدادات Limitation: [code] ثابت للترجمة، و[field] الحقل المعني. */
data class UmLimitIssue(val code: String, val field: String)

/**
 * قواعد التحقق قبل أي كتابة على الراوتر (لا تُرسل إعدادات غير صالحة أو متعارضة):
 * - Burst لكل اتجاه: إمّا الثلاثة معاً (سرعة وحد ومدة) أو لا شيء.
 * - الاندفاع يحتاج سرعة قصوى > 0 لنفس الاتجاه، وسرعة الاندفاع أكبر منها، والحد أقل من سرعة الاندفاع (وإلا لا ينتهي الاندفاع أبداً).
 * - مدة الاندفاع بين 1 ثانية وساعة.
 * - حد التنزيل أو الرفع المنفصل لا يتجاوز حد النقل الإجمالي إن وُجد (وإلا لا يعمل الحد الإجمالي أبداً معه).
 */
object UmLimitRules {
    const val BURST_INCOMPLETE = "burst_incomplete"
    const val BURST_NEEDS_RATE = "burst_needs_rate"
    const val BURST_NOT_ABOVE_RATE = "burst_not_above_rate"
    const val BURST_THRESHOLD_INVALID = "burst_threshold_invalid"
    const val BURST_TIME_INVALID = "burst_time_invalid"
    const val DOWNLOAD_EXCEEDS_TRANSFER = "download_exceeds_transfer"
    const val UPLOAD_EXCEEDS_TRANSFER = "upload_exceeds_transfer"
    const val NEGATIVE_VALUE = "negative_value"
    const val PRIORITY_INVALID = "priority_invalid"
    const val PRIORITY_NEEDS_RATE = "priority_needs_rate"
    const val RESET_NEEDS_LIMIT = "reset_needs_limit"
    const val SCHEDULE_TIME_INVALID = "schedule_time_invalid"
    const val SCHEDULE_NO_DAYS = "schedule_no_days"
    /** الاستعمال غير مدعوم في هذا الجيل من User Manager (مثل التصفير الدوري على v6). */
    const val UNSUPPORTED = "unsupported"

    /** أولوية RouterOS: 1 الأعلى … 8 الأدنى؛ 8 هو الافتراضي (لا أولوية خاصة). */
    const val PRIORITY_HIGHEST = 1
    const val PRIORITY_DEFAULT = 8

    const val MAX_BURST_TIME_SECONDS = 3_600L

    /** القيم الفعلية بعد الكتابة: ما لم يُلمس في [spec] (null) يبقى كما هو في [existing]. */
    fun effective(existing: UmLimitation?, spec: UmLimitationSpec): UmLimitation {
        val base = existing ?: UmLimitation(spec.name, 0, 0, null, null)
        return base.copy(
            rxKbps = spec.rxKbps ?: base.rxKbps,
            txKbps = spec.txKbps ?: base.txKbps,
            uptimeSeconds = spec.uptimeSeconds?.takeIf { it > 0 } ?: if (spec.uptimeSeconds != null) null else base.uptimeSeconds,
            transferBytes = spec.transferBytes?.takeIf { it > 0 } ?: if (spec.transferBytes != null) null else base.transferBytes,
            downloadBytes = spec.downloadBytes?.takeIf { it > 0 } ?: if (spec.downloadBytes != null) null else base.downloadBytes,
            uploadBytes = spec.uploadBytes?.takeIf { it > 0 } ?: if (spec.uploadBytes != null) null else base.uploadBytes,
            burstRx = spec.burstRx ?: base.burstRx,
            burstTx = spec.burstTx ?: base.burstTx,
            priority = spec.priority ?: base.priority,
            resetInterval = spec.resetInterval ?: base.resetInterval
        )
    }

    fun validate(l: UmLimitation): List<UmLimitIssue> = buildList {
        addAll(burst("rx", l.rxKbps, l.burstRx))
        addAll(burst("tx", l.txKbps, l.burstTx))
        val t = l.transferBytes
        if (t != null && t > 0) {
            if ((l.downloadBytes ?: 0L) > t) add(UmLimitIssue(DOWNLOAD_EXCEEDS_TRANSFER, "download-limit"))
            if ((l.uploadBytes ?: 0L) > t) add(UmLimitIssue(UPLOAD_EXCEEDS_TRANSFER, "upload-limit"))
        }
    }

    /** يتحقق من المواصفة مدمجة مع الحالة الحالية على الراوتر (أو بلا حالة عند الإنشاء). */
    fun validate(existing: UmLimitation?, spec: UmLimitationSpec): List<UmLimitIssue> {
        val neg = buildList {
            if ((spec.rxKbps ?: 0) < 0) add(UmLimitIssue(NEGATIVE_VALUE, "rate-limit-rx"))
            if ((spec.txKbps ?: 0) < 0) add(UmLimitIssue(NEGATIVE_VALUE, "rate-limit-tx"))
            listOfNotNull(spec.burstRx, spec.burstTx).forEach {
                if (it.rateKbps < 0 || it.thresholdKbps < 0 || it.timeSeconds < 0) add(UmLimitIssue(NEGATIVE_VALUE, "burst"))
            }
            if ((spec.downloadBytes ?: 0L) < 0 || (spec.uploadBytes ?: 0L) < 0 || (spec.transferBytes ?: 0L) < 0) add(UmLimitIssue(NEGATIVE_VALUE, "limit"))
        }
        val merged = effective(existing, spec)
        return neg + validate(merged) + touched(merged, spec)
    }

    /**
     * قواعد تخص الحقول التي يلمسها هذا التنفيذ فقط (لا نحجب تعديلاً لا علاقة له بحالة قائمة على الراوتر):
     * - الأولوية 1..8، ولا أثر لها (فلا تُرسل) إلا مع سرعة قصوى > 0 في أحد الاتجاهين؛ فالأولوية خاصية قائمة الانتظار المنشأة من حد السرعة.
     * - التصفير الدوري يحتاج حداً واحداً على الأقل يُصفَّر (وقت/نقل/تنزيل/رفع)، وإلا لا شيء يُصفَّر.
     */
    private fun touched(merged: UmLimitation, spec: UmLimitationSpec): List<UmLimitIssue> = buildList {
        spec.priority?.let { if (it !in PRIORITY_HIGHEST..PRIORITY_DEFAULT) add(UmLimitIssue(PRIORITY_INVALID, "rate-limit-priority")) }
        val prioTouched = spec.priority != null || spec.rxKbps != null || spec.txKbps != null
        val p = merged.priority
        if (prioTouched && p != null && p != PRIORITY_DEFAULT && p in PRIORITY_HIGHEST..PRIORITY_DEFAULT && merged.rxKbps <= 0 && merged.txKbps <= 0) {
            add(UmLimitIssue(PRIORITY_NEEDS_RATE, "rate-limit-priority"))
        }
        val r = spec.resetInterval
        if (r != null && r != UmResetInterval.DISABLED &&
            merged.uptimeSeconds == null && merged.transferBytes == null && merged.downloadBytes == null && merged.uploadBytes == null
        ) add(UmLimitIssue(RESET_NEEDS_LIMIT, "reset-counters-interval"))
    }

    private fun burst(dir: String, maxKbps: Int, b: UmBurstDir): List<UmLimitIssue> {
        if (!b.enabled) return emptyList()
        val rate = "rate-limit-burst-$dir"
        val thr = "rate-limit-burst-threshold-$dir"
        val time = "rate-limit-burst-time-$dir"
        if (b.rateKbps <= 0 || b.thresholdKbps <= 0 || b.timeSeconds <= 0) return listOf(UmLimitIssue(BURST_INCOMPLETE, rate))
        val out = mutableListOf<UmLimitIssue>()
        when {
            maxKbps <= 0 -> out += UmLimitIssue(BURST_NEEDS_RATE, rate)
            b.rateKbps <= maxKbps -> out += UmLimitIssue(BURST_NOT_ABOVE_RATE, rate)
        }
        if (b.thresholdKbps >= b.rateKbps) out += UmLimitIssue(BURST_THRESHOLD_INVALID, thr)
        if (b.timeSeconds > MAX_BURST_TIME_SECONDS) out += UmLimitIssue(BURST_TIME_INVALID, time)
        return out
    }

    /**
     * قيم مفضلة للاستخدام العام انطلاقاً من السرعة القصوى لاتجاه: اندفاع ضعف السرعة، حد = نصفها، مدة 10 ثوانٍ.
     * سرعة قصوى 0 (بلا حد) = لا Burst (يرجع Burst معطلاً).
     */
    fun defaultBurst(maxKbps: Int): UmBurstDir =
        if (maxKbps <= 0 || maxKbps > Int.MAX_VALUE / 2) UmBurstDir()
        else UmBurstDir(rateKbps = maxKbps * 2, thresholdKbps = (maxKbps / 2).coerceAtLeast(1), timeSeconds = 10L)
}


/**
 * فترة تصفير عدّادات الاستخدام في Limitation (v7: reset-counters-interval). [DISABLED] = بلا تصفير دوري (الافتراضي في RouterOS وفي التطبيق).
 * تصفّر إحصاءات كل المستخدمين المرتبطين بالـ Limitation دفعة واحدة (وقت ونقل وتنزيل ورفع) عند كل دورة محسوبة من reset-counters-start-time.
 * القيم الزمنية المخصّصة التي يقبلها RouterOS غير مدعومة هنا: تُقرأ كـ null (غير معروفة) ولا تُكتب.
 */
enum class UmResetInterval(val wire: String) {
    DISABLED("disabled"), HOURLY("hourly"), DAILY("daily"), WEEKLY("weekly"), MONTHLY("monthly");

    companion object {
        fun fromWire(raw: String?): UmResetInterval? = entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) }
    }
}

enum class UmWeekday(val wire: String) {
    SUNDAY("sunday"), MONDAY("monday"), TUESDAY("tuesday"), WEDNESDAY("wednesday"), THURSDAY("thursday"), FRIDAY("friday"), SATURDAY("saturday");

    companion object {
        val ALL: Set<UmWeekday> = entries.toSet()
        fun fromWire(raw: String): UmWeekday? = entries.firstOrNull { it.wire.equals(raw.trim(), ignoreCase = true) }
    }
}

/**
 * نافذة تشغيل الباقة: خاصية رابط Profile↔Limitation في RouterOS (from-time وtill-time وweekdays) لا خاصية Limitation نفسه،
 * فالجدول يخص هذه الباقة وحدها حتى لو كان الـ Limitation مشتركاً. الأوقات بتوقيت الراوتر (ثوانٍ من منتصف الليل).
 * RouterOS يعرّف نافذة سماح واحدة في اليوم (لا منع صريحاً): المنع = ما خارج النافذة والأيام المحذوفة. نافذة تعبر منتصف الليل (من > إلى) غير مدعومة.
 */
data class UmSchedule(
    val fromSeconds: Int = 0,
    val tillSeconds: Int = MAX_SECONDS,
    val weekdays: Set<UmWeekday> = UmWeekday.ALL
) {
    /** كل الأيام وعلى مدار الساعة = لا قيد (الافتراضي الذي لا يُفرض على الباقة). */
    val isAlwaysOn: Boolean get() = this == ALWAYS

    companion object {
        const val MAX_SECONDS = 86_399
        val ALWAYS = UmSchedule()

        fun clock(seconds: Int): String = "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        fun weekdaysWire(days: Set<UmWeekday>): String = UmWeekday.entries.filter { it in days }.joinToString(",") { it.wire }

        /** "08:00:00" أو "0s" من الراوتر ← ثوانٍ، وfallback إن غابت القيمة. */
        fun parse(from: String?, till: String?, weekdays: String?): UmSchedule {
            val f = UmUnits.parseDurationSeconds(from)?.toInt()?.coerceIn(0, MAX_SECONDS) ?: 0
            val t = UmUnits.parseDurationSeconds(till)?.toInt()?.coerceIn(0, MAX_SECONDS) ?: MAX_SECONDS
            val days = weekdays?.split(',')?.mapNotNull { UmWeekday.fromWire(it) }?.toSet()?.takeIf { it.isNotEmpty() } ?: UmWeekday.ALL
            return UmSchedule(f, t, days)
        }
    }
}

object UmScheduleRules {
    /** مشاكل الجدولة: وقت خارج اليوم أو بداية ≥ نهاية (يشمل العبور عبر منتصف الليل)، أو لا أيام. */
    fun validate(s: UmSchedule): List<UmLimitIssue> = buildList {
        if (s.fromSeconds !in 0..UmSchedule.MAX_SECONDS || s.tillSeconds !in 0..UmSchedule.MAX_SECONDS || s.fromSeconds >= s.tillSeconds) {
            add(UmLimitIssue(UmLimitRules.SCHEDULE_TIME_INVALID, "from-time"))
        }
        if (s.weekdays.isEmpty()) add(UmLimitIssue(UmLimitRules.SCHEDULE_NO_DAYS, "weekdays"))
    }

    /** خصائص رابط profile-limitation. الجدول الافتراضي (طوال الأسبوع) يُكتب صراحةً كقيم RouterOS الافتراضية فيمحو أي قيد سابق. */
    fun attrs(s: UmSchedule): Map<String, Any?> = mapOf(
        "from-time" to UmSchedule.clock(s.fromSeconds),
        "till-time" to UmSchedule.clock(s.tillSeconds),
        "weekdays" to UmSchedule.weekdaysWire(s.weekdays)
    )
}

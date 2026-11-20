package ps.mgtik.hotspot.data.userman

/** جيل User Manager على الراوتر: v6 = /tool/user-manager (نموذج العملاء)، v7 = /user-manager. */
enum class UmGeneration { V6, V7 }

/**
 * متى تبدأ مدة صلاحية الباقة داخل User Manager:
 * - [FIRST_AUTH]: عند أول اتصال للمستخدم (v7 `starts-when=first-auth`، v6 `starts-at=logon`).
 * - [ON_ASSIGN]: لحظة إسناد الباقة للمستخدم (v7 `starts-when=assigned`، v6 `starts-at=now`).
 * الخيار الثالث (عند الدفع) قرار للتطبيق لا للراوتر: يُسنِد التطبيق الباقة لحظة تسجيل الدفع مع [ON_ASSIGN].
 */
enum class UmStartMode { FIRST_AUTH, ON_ASSIGN }

/**
 * باقة تجارية كما تُكتب في User Manager: تُترجم إلى Limitation + Profile + Profile-Limitation بأسماء حتمية مشتقة من [name].
 * rx = رفع من العميل، tx = تنزيل إليه (نفس اتفاقية الكود القديم). null/0 = بلا حد.
 */
/** حقول الاستخدام التي يعطّلها pushUsageLimits=false. */
internal val USAGE_WRITES = setOf(UmWrite.UPTIME_LIMIT, UmWrite.TRANSFER_LIMIT, UmWrite.DOWNLOAD_LIMIT, UmWrite.UPLOAD_LIMIT)

data class UmPackageSpec(
    val name: String,
    val rxKbps: Int = 0,
    val txKbps: Int = 0,
    val sharedUsers: Int = 1,
    /** null = لا تُلمس القيمة على الراوتر (تُحفظ كما هي). 0 = إزالة الحد صراحةً. >0 = الحد بالثواني. */
    val uptimeSeconds: Long? = null,
    /** نفس دلالة [uptimeSeconds]: null = لا تُلمس، 0 = إزالة صريحة، >0 = بايت. */
    val transferBytes: Long? = null,
    /** null = لا تُلمس الصلاحية على الراوتر (لا يُرسل 0s افتراضياً). >0 = بالثواني. غير المحدودة تُطلب صراحةً عبر [validityUnlimited]. */
    val validitySeconds: Long? = null,
    /** صلاحية غير محدودة بقرار صريح (تُكتب 0s). لا يُستنتج من null ولا من 0. */
    val validityUnlimited: Boolean = false,
    /** true = override-shared-users=unlimited ([sharedUsers] يُهمل). */
    val sharedUnlimited: Boolean = false,
    val startMode: UmStartMode = UmStartMode.FIRST_AUTH,
    /** false = لا يُرسل حدّا الوقت والنقل أبداً: تبقى الحدود الموجودة على الراوتر كما هي (لا كتابة ولا تصفير). */
    val pushUsageLimits: Boolean = true,
    /** اسم Limitation يُستعمل بدل المشتق mgtik-<name> (مكون موجود/مشترك). null = الاسم المشتق. */
    val limitationName: String? = null,
    /**
     * Limitations المختارة صراحةً (الأول = الأساسي الذي تُكتب عليه السرعة والحدود في وضع [UmComponentMode.MANAGE]).
     * غير فارغة = القائمة المرجعية الدقيقة لروابط الـ Profile (تُضاف الناقصة وتُفصل الزائدة دون حذف أي Limitation).
     * فارغة = السلوك القديم: [limitationName] أو المشتق mgtik-<name> يُضمَن ربطه فقط.
     */
    val limitationNames: List<String> = emptyList(),
    /** [UmComponentMode.REFERENCE] = Profile موجود/مشترك: لا يُكتب عليه ولا على روابطه أبداً (يُتحقق من وجوده فقط). */
    val profileMode: UmComponentMode = UmComponentMode.MANAGE,
    /** [UmComponentMode.REFERENCE] = Limitations موجودة/مشتركة: لا يُكتب عليها؛ تُربط بالـ Profile فقط. */
    val limitationMode: UmComponentMode = UmComponentMode.MANAGE,
    /**
     * الحقول التي يكتبها هذا التنفيذ. الحقل غير الموجود هنا يبقى على الراوتر كما هو (لا قيمة افتراضية تُكتب بصمت):
     * عند تعديل الباقة تُدرج فقط الحقول التي غيّرها المستخدم فعلاً. الإنشاء = الكل ([UmWrite.ALL]).
     */
    val writes: Set<UmWrite> = UmWrite.ALL,
    // الحقول الجديدة في النهاية كي لا يتغير ترتيب الاستدعاءات الموضعية القائمة
    /** حدّا التنزيل/الرفع المنفصلان: نفس دلالة [transferBytes] (null لا يُلمس، 0 إزالة صريحة). يتبعان [pushUsageLimits]. */
    val downloadBytes: Long? = null,
    val uploadBytes: Long? = null,
    /** Burst لكل اتجاه: null = لا يُلمس؛ [UmBurstDir] بأصفار = تعطيل صريح. يتبع كتابة السرعة ([UmWrite.BURST]). */
    val burstRx: UmBurstDir? = null,
    val burstTx: UmBurstDir? = null,
    /** أولوية الحركة 1..8 على Limitation الباقة الأساسي (تتبع [UmWrite.PRIORITY]). null = لا تُلمس. */
    val priority: Int? = null,
    /** التصفير الدوري للعدّادات (تتبع [UmWrite.RESET_INTERVAL]). null = لا يُلمس؛ DISABLED = تعطيل صريح. */
    val resetInterval: UmResetInterval? = null,
    val resetStartTime: String? = null,
    /** نافذة التشغيل على روابط Limitations الباقة (تتبع [UmWrite.SCHEDULE]). null = لا تُلمس؛ [UmSchedule.ALWAYS] = رفع القيد صراحةً. */
    val schedule: UmSchedule? = null,
    /** سعر التجزئة بالوحدة الصغرى لحقل price في Profile (تتبع [UmWrite.PRICE]). null = لا يُلمس. */
    val priceMinor: Long? = null
) {
    /** هل سيُكتب هذا الحقل؟ حدّا الاستخدام يتبعان أيضاً [pushUsageLimits]. */
    fun willWrite(w: UmWrite): Boolean =
        w in writes && (pushUsageLimits || w !in USAGE_WRITES)

    /** Limitations التي يجب أن تكون مربوطة بالباقة بعد التنفيذ (الأول أساسي). */
    fun effectiveLimitations(): List<String> = when {
        limitationNames.isNotEmpty() -> limitationNames.distinct()
        !limitationName.isNullOrBlank() -> listOf(limitationName)
        else -> listOf("$UM_LIMITATION_PREFIX$name")
    }
}

/** باقة كما قرأناها من الراوتر. */
data class UmPackage(
    val name: String,
    val rxKbps: Int,
    val txKbps: Int,
    val sharedUsers: Int,
    val uptimeSeconds: Long?,
    val transferBytes: Long?,
    val validitySeconds: Long?,
    /** Limitations مربوطة بالباقة على الراوتر وليست من إنشاء التطبيق (لا تُعدَّل ولا تُحذف منه) ← تقيّد المستخدمين فعلياً. */
    val foreignLimitations: List<String> = emptyList(),
    /** true = Limitation التطبيق `mgtik-<name>` مربوطة فعلاً بالباقة عبر profile-limitation. وجودها دون رابط = قيود غير مفروضة على الراوتر. */
    val limitationLinked: Boolean = true,
    /** true = Limitation التطبيق موجودة على الراوتر لكنها غير مربوطة بالباقة (إنشاء ناقص): قيودها غير مفروضة. غيابها كلياً ليس يتيمة. */
    val limitationOrphaned: Boolean = false,
    /** الباقة على الراوتر `override-shared-users=unlimited`: [sharedUsers] لا يمثلها (يبقى 1) فلا يجوز نسخه إلى السحابة كقيمة فعلية. */
    val sharedUnlimited: Boolean = false,
    /** حدّا التنزيل/الرفع الإجماليان المنفصلان (بالبايت) من Limitation الباقة؛ null = بلا حد. */
    val downloadBytes: Long? = null,
    val uploadBytes: Long? = null,
    /** الصلاحية على الراوتر `validity=0s`/unlimited بقرار صريح؛ [validitySeconds] = null مع false = غير محددة. */
    val validityUnlimited: Boolean = false,
    /** سياسة بداية الصلاحية على Profile الراوتر؛ null = الحقل غير مقروء أو قيمته غير معروفة (لا تُخمَّن). */
    val startMode: UmStartMode? = null
) {
    /**
     * هل الباقة على الراوتر مكتملة ومطابقة لـ [spec]؟ (قيم ورابط Limitation). يُستعمل لتمييز «وصلت محاولة سابقة كاملة»
     * عن «أُنشئ الجزء الأول فقط» (بروفايل بلا رابط قيود): الثاني ليس نجاحاً ويجب إعادة المحاولة.
     */
    fun matches(spec: UmPackageSpec): Boolean {
        val uptimeOk = !spec.pushUsageLimits || spec.uptimeSeconds == null || uptimeSeconds == spec.uptimeSeconds?.takeIf { it > 0 }
        val transferOk = !spec.pushUsageLimits || spec.transferBytes == null || transferBytes == spec.transferBytes?.takeIf { it > 0 }
        val validityOk = when {
            spec.validityUnlimited -> validitySeconds == null
            spec.validitySeconds == null -> true
            else -> validitySeconds == spec.validitySeconds?.takeIf { it > 0 }
        }
        val sharedOk = if (spec.sharedUnlimited) sharedUnlimited else !sharedUnlimited && sharedUsers == spec.sharedUsers.coerceAtLeast(1)
        return limitationLinked && rxKbps == spec.rxKbps.coerceAtLeast(0) && txKbps == spec.txKbps.coerceAtLeast(0) &&
            sharedOk && uptimeOk && transferOk && validityOk
    }
}

/** مستخدم UserMan كما قرأناه من الراوتر (packageName = آخر باقة مُسندة إن وُجدت). */
data class UmUser(
    val id: String,
    val name: String,
    val disabled: Boolean,
    val packageName: String?,
    val comment: String?,
    val password: String?
)

/**
 * جلسة كما يسجّلها User Manager (حية أو تاريخية). المصدر الوحيد لبيانات الجلسات والاستهلاك.
 * [active] = علم active في الراوتر؛ [status] النص الخام لحالة المحاسبة (start/interim/stop/expired/close-acked في v7).
 * لا تُعدّ الجلسة حيّة بمجرد وجود سجلها: انظر [isLive]. [startedRaw] نص وقت البداية كما أعاده الراوتر (بتوقيت الراوتر).
 * [uptimeSeconds] المدة المعتمدة: (ساعة الراوتر − البداية) إن أمكن قراءتهما، وإلا uptime المبلَّغ في الجلسة.
 * العدّادات: upload = ما أرسله العميل، download = ما استقبله.
 */
data class UmSession(
    val id: String,
    val username: String,
    val active: Boolean,
    val status: String,
    val address: String,
    val macAddress: String,
    val startedRaw: String?,
    val uptimeSeconds: Long?,
    val uploadBytes: Long,
    val downloadBytes: Long,
    /** معرّف جلسة المحاسبة (acct-session-id) إن أعاده الراوتر؛ فارغ = غير متوفر (لا يُخمَّن). */
    val acctSessionId: String = "",
    /** عنوان NAS الذي أبلغ عن الجلسة (nas-ip-address)؛ فارغ = غير متوفر. */
    val nasAddress: String = "",
    /** سبب إنهاء الجلسة كما سجّله User Manager (terminate-cause)؛ فارغ = غير مسجَّل/غير مدعوم. */
    val terminateCause: String = "",
    /** وقت نهاية الجلسة كما أعاده الراوتر (ended في v7، till-time في v6)؛ null = لم تنتهِ أو غير متوفر. */
    val endedRaw: String? = null,
    /** وقت آخر حزمة محاسبة (last-accounting-packet، v7)؛ null = غير متوفر. */
    val lastAccountingRaw: String? = null
) {
    /** حيّة = علم active صحيح وحالة المحاسبة لا تدل على نهاية الجلسة. سجل تاريخي ≠ جلسة نشطة. */
    val isLive: Boolean get() = active && !UmSessionStatus.endsSession(status)
}

/** ملخص سجل جلسات (تاريخي) بلا احتساب مزدوج: الجلسة نفسها (المعرّف أو المفتاح المركّب) تُحتسب مرة واحدة. */
data class UmSessionSummary(
    val totalSessions: Int,
    val completedSessions: Int,
    val currentSessions: Int,
    val totalUptimeSeconds: Long,
    val totalUploadBytes: Long,
    val totalDownloadBytes: Long
) {
    val totalBytes: Long get() = totalUploadBytes + totalDownloadBytes
}

object UmSessionHistory {
    /** هوية الجلسة: acct-session-id إن وُجد، وإلا (مستخدم|بداية|MAC) ثم .id كملاذ أخير. */
    fun identity(s: UmSession): String = when {
        s.acctSessionId.isNotBlank() -> "acct:${s.username.lowercase()}:${s.acctSessionId}"
        !s.startedRaw.isNullOrBlank() -> "key:${s.username.lowercase()}|${s.startedRaw}|${s.macAddress.lowercase()}"
        else -> "id:${s.id}"
    }

    /** يزيل تكرار الجلسة نفسها (يُبقي الصف ذا العدّادات الأكبر لأن العدّادات تنمو) ويرتّب الأحدث أولاً. */
    fun dedupe(rows: List<UmSession>): List<UmSession> =
        rows.groupBy(::identity).values
            .map { g -> g.maxWith(compareBy<UmSession>({ it.uploadBytes + it.downloadBytes }, { it.uptimeSeconds ?: 0L })) }
            .sortedByDescending { UmUnits.parseRouterDateTime(it.startedRaw) ?: Long.MIN_VALUE }

    fun summarize(rows: List<UmSession>): UmSessionSummary {
        val d = dedupe(rows)
        return UmSessionSummary(
            totalSessions = d.size,
            completedSessions = d.count { !it.isLive },
            currentSessions = d.count { it.isLive },
            totalUptimeSeconds = d.sumOf { it.uptimeSeconds ?: 0L },
            totalUploadBytes = d.sumOf { it.uploadBytes },
            totalDownloadBytes = d.sumOf { it.downloadBytes }
        )
    }
}

object UmSessionStatus {
    private val ENDED = setOf("stop", "expired", "close-acked", "close-acknowledged", "closed")

    /** الحالة bit set مفصولة بفواصل؛ أي رمز نهاية يجعل الجلسة منتهية. فارغ/غير معروف = لا دليل على النهاية. */
    fun endsSession(status: String): Boolean =
        status.split(',').map { it.trim().lowercase() }.any { it in ENDED }
}

/** استهلاك مستخدم مجمّعاً من جلسات UserMan: upload = من العميل (in)، download = إليه (out). */
data class UmUsage(val uptimeSeconds: Long, val uploadBytes: Long, val downloadBytes: Long)

/** نتيجة فحص جاهزية واحد (قراءة فقط). */
data class UmCheck(val key: String, val ok: Boolean, val detail: String)

/** تحويلات القيم النصية لـ RouterOS (مدد، سرعات). دوال نقية لتسهيل الاختبار. */
object UmUnits {
    private val durationPart = Regex("(\\d+)([wdhms])")

    /** "1d2h3m4s" أو "3600" أو "00:10:00" → ثوانٍ. null إن كانت القيمة فارغة؛ 0 تعني بلا حد. */
    fun parseDurationSeconds(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        s.toLongOrNull()?.let { return it }
        if (s.contains(':')) {
            val p = s.split(':').map { it.toLongOrNull() ?: return null }
            return when (p.size) { 3 -> p[0] * 3600 + p[1] * 60 + p[2]; 2 -> p[0] * 60 + p[1]; else -> null }
        }
        var total = 0L
        var consumed = 0
        for (m in durationPart.findAll(s)) {
            val n = m.groupValues[1].toLong()
            total += n * when (m.groupValues[2]) { "w" -> 604_800L; "d" -> 86_400L; "h" -> 3_600L; "m" -> 60L; else -> 1L }
            consumed += m.value.length
        }
        return if (consumed == s.length) total else null
    }

    fun formatSeconds(seconds: Long?): String = if (seconds == null || seconds <= 0L) "0s" else "${seconds}s"

    /** "512k" / "2M" / "1500000" (بت/ث) → كيلوبت/ث. 0 إن كانت فارغة. */
    fun parseRateKbps(raw: String?): Int {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return 0
        val last = s.last().lowercaseChar()
        val num = (if (last.isLetter()) s.dropLast(1) else s).toLongOrNull() ?: return 0
        val kbps = when (last) { 'k' -> num; 'm' -> num * 1_000; 'g' -> num * 1_000_000; else -> num / 1_000 }
        return kbps.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    fun formatRate(kbps: Int): String = if (kbps <= 0) "0" else "${kbps}k"

    private val bytesToken = Regex("^(\\d+(?:\\.\\d+)?)\\s*(b|kib|mib|gib|tib)?$", RegexOption.IGNORE_CASE)

    /**
     * عدد بايتات من رقم خام ("5368709120") أو بصيغة العرض في RouterOS ("5368709120B" و"4.7GiB"، الوحدات ثنائية).
     * null = فارغ أو غير مفهوم. لا يُحوَّل نص غير مفهوم إلى 0 هنا: المستدعي يقرّر (0 قد تعني «بلا حد» أو «لا استهلاك»).
     */
    fun parseBytes(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        s.toLongOrNull()?.let { return it }
        val m = bytesToken.matchEntire(s) ?: return null
        val factor = when (m.groupValues[2].lowercase()) {
            "kib" -> 1_024L; "mib" -> 1_048_576L; "gib" -> 1_073_741_824L; "tib" -> 1_099_511_627_776L; else -> 1L
        }
        val total = java.math.BigDecimal(m.groupValues[1]).multiply(java.math.BigDecimal(factor)).setScale(0, java.math.RoundingMode.DOWN)
        return if (total.toBigInteger().bitLength() < 63) total.toLong() else null
    }

    /**
     * بدء الفترة الجارية في v7: user-profile لا يعرض وقت بدء (حقلاه المقروءان state وend-time)، فيُشتق من
     * نهاية الفترة − مدة صلاحية الباقة. null إن كانت النهاية غير محسوبة (unlimited/not-yet-running) أو الصلاحية مفتوحة.
     * النهاية والجلسات من قاعدة User Manager نفسها فتتشاركان المحور الزمني؛ لا مقارنة بساعة الجهاز.
     */
    fun periodStartSeconds(endRaw: String?, validitySeconds: Long?): Long? {
        val end = parseRouterDateTime(endRaw) ?: return null
        return validitySeconds?.takeIf { it > 0 }?.let { end - it }
    }

    /** سعر بالوحدة الصغرى ← نص عشري بخانتين كما يقبله حقل price في Profile (1250 ← "12.50"). */
    fun formatPrice(minor: Long): String = java.math.BigDecimal.valueOf(minor, 2).toPlainString()

    /** نص price من الراوتر ← وحدة صغرى. null = فارغ/غير مفهوم أو سالب. */
    fun parsePriceMinor(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        return try {
            java.math.BigDecimal(s).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).takeIf { it.signum() >= 0 && it.toBigInteger().bitLength() < 63 }?.toLong()
        } catch (e: NumberFormatException) {
            null
        }
    }

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val isoDateTime = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})")
    private val rosDateTime = Regex("^([A-Za-z]{3})/(\\d{1,2})/(\\d{4})[ T](\\d{2}):(\\d{2}):(\\d{2})")

    /**
     * وقت RouterOS ("2026-11-10 15:00:00" أو "nov/10/2026 15:00:00") ← ثوانٍ على محور موحّد (يُعامل كأنه UTC).
     * لا معنى مطلقاً للقيمة: تُستعمل للفرق بين وقتين من **ساعة الراوتر نفسها** فيسقط أثر المنطقة الزمنية. null = صيغة غير مفهومة.
     */
    fun parseRouterDateTime(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val parts: List<Int> = isoDateTime.find(s)?.groupValues?.drop(1)?.map { it.toInt() }
            ?: rosDateTime.find(s)?.let { m ->
                val month = months.indexOf(m.groupValues[1].lowercase()).takeIf { it >= 0 }?.plus(1) ?: return null
                listOf(m.groupValues[3].toInt(), month, m.groupValues[2].toInt()) + m.groupValues.drop(4).map { it.toInt() }
            }
            ?: return null
        val (y, mo, d, h, mi) = parts
        val se = parts[5]
        return try {
            java.time.LocalDateTime.of(y, mo, d, h, mi, se).toEpochSecond(java.time.ZoneOffset.UTC)
        } catch (e: java.time.DateTimeException) {
            null
        }
    }
}

// ───────────────────────────── الحالة والتهيئة والصيانة ─────────────────────────────

/** مفاتيح فحوص الجاهزية (ثابتة: الواجهة تترجمها إلى نصوص). */
object UmCheckKeys {
    const val PACKAGE = "package"
    const val ENABLED = "enabled"
    const val USE_PROFILES = "use-profiles"
    const val CUSTOMER = "customer"
    const val UM_ROUTER = "um-router"
    const val RADIUS = "radius"
    const val SECRET_MATCH = "secret-match"
    const val USE_RADIUS = "use-radius"
    /** `/radius incoming accept=yes`: بدونه لا يستطيع User Manager فصل جلسة حيّة عند بلوغ الحد (Disconnect/CoA يُرفض عند الهوتسبوت). */
    const val INCOMING = "radius-incoming"
}

/**
 * لقطة حالة User Manager من الراوتر (قراءة فقط).
 * [enabled]/[useProfiles] لـ v7 فقط (null على v6)، و[dbSizeBytes] إن أعاده الراوتر رقماً.
 */
data class UmStatus(
    val generation: UmGeneration,
    val routerOsVersion: String,
    val packageInstalled: Boolean,
    val enabled: Boolean?,
    val useProfiles: Boolean?,
    val dbPath: String?,
    val dbSizeBytes: Long?,
    val userCount: Int,
    val packageCount: Int,
    val checks: List<UmCheck>
) {
    /** كل الفحوص ناجحة: يمكن للتطبيق إدارة المستخدمين والباقات عبر User Manager. */
    val isReady: Boolean get() = packageInstalled && checks.isNotEmpty() && checks.all { it.ok }
}

/** خطوات تهيئة الراوتر لـ User Manager (كلها idempotent). */
enum class UmStep { ENABLE, NAS, RADIUS, HOTSPOT_PROFILES, INCOMING }

enum class UmAction { CREATED, UPDATED, UNCHANGED, FAILED }

data class UmProvisionStep(val step: UmStep, val action: UmAction, val detail: String = "")

/**
 * UserManPlanner - يحوّل فحوص الجاهزية الفاشلة إلى الخطوات التي ستُنفَّذ (تُعرض للمستخدم قبل التأكيد). دوال نقية.
 * فحصا [UmCheckKeys.PACKAGE] و[UmCheckKeys.CUSTOMER] لا يُصلحهما التطبيق (تثبيت الحزمة/إنشاء العميل يدوي على الراوتر).
 */
object UserManPlanner {
    fun stepsFor(checks: List<UmCheck>): Set<UmStep> {
        val steps = linkedSetOf<UmStep>()
        for (c in checks.filterNot { it.ok }) {
            when (c.key) {
                UmCheckKeys.ENABLED, UmCheckKeys.USE_PROFILES -> steps += UmStep.ENABLE
                UmCheckKeys.UM_ROUTER -> steps += UmStep.NAS
                UmCheckKeys.RADIUS -> steps += UmStep.RADIUS
                UmCheckKeys.SECRET_MATCH -> { steps += UmStep.NAS; steps += UmStep.RADIUS }
                UmCheckKeys.USE_RADIUS -> steps += UmStep.HOTSPOT_PROFILES
                UmCheckKeys.INCOMING -> steps += UmStep.INCOMING
            }
        }
        return steps
    }

    /**
     * الفحوص الفاشلة التي تمنع التوليد: كل ما يلزم لنجاح مصادقة RADIUS. السر المشترك [UmCheckKeys.SECRET_MATCH] خارجها
     * لأن قراءة الأسرار تحتاج صلاحية حساسة، فقد يفشل الفحص بسبب الصلاحية لا بسبب عدم التطابق؛ يبقى تحذيراً في شاشة الإعداد.
     * و[UmCheckKeys.INCOMING] خارجها أيضاً: غيابه لا يمنع المصادقة (الافتراضي في RouterOS accept=no)، بل يعطّل فصل الجلسة الحيّة
     * عند بلوغ الحد فقط؛ يبقى تحذيراً يُصلحه زر التهيئة، ولا يوقف توليد المستخدمين على إعدادات كانت تعمل.
     */
    fun blockingChecks(checks: List<UmCheck>): List<UmCheck> =
        checks.filter { !it.ok && it.key != UmCheckKeys.SECRET_MATCH && it.key != UmCheckKeys.INCOMING }

    /** فحوص فاشلة لا يعالجها التطبيق (تحتاج تدخلاً يدوياً). */
    fun manualChecks(checks: List<UmCheck>): List<UmCheck> =
        checks.filter { !it.ok && (it.key == UmCheckKeys.PACKAGE || it.key == UmCheckKeys.CUSTOMER) }
}

/** قراءات User Manager التي يُشخَّص فشلها كلاً على حدة (S32). */
enum class UmReadKind { PACKAGES, LIMITATIONS, USERS, SESSIONS }

/** نتيجة فحص قراءة واحدة: [errorCode] = ApiError.code عند الفشل. */
data class UmReadCheck(val kind: UmReadKind, val ok: Boolean, val errorCode: String? = null)

/** قراءة النتائج: أي القراءات فشلت (لتحديد زر الإصلاح/إعادة الفحص المناسب). نقية. */
object UmReadDiagnostics {
    fun failed(checks: List<UmReadCheck>): List<UmReadCheck> = checks.filterNot { it.ok }

    /** لا يُقترح «إصلاح» لقراءة فشلت بسبب اتصال/صلاحيات؛ إعادة الفحص فقط. الإصلاح (تهيئة) لمشاكل جاهزية User Manager نفسها. */
    fun needsReprovision(checks: List<UmReadCheck>): Boolean =
        failed(checks).any { it.errorCode == null || it.errorCode == "ERR_ROUTER_REJECTED" }
}

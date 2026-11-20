package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.domain.model.OperationalSetting
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.ProfileCategory
import ps.mgtik.hotspot.domain.model.ProfileClassification

/**
 * ProfileDetailRules - قواعد نقية (بلا Android) لتعديل البروفايل من صفحة التفاصيل، مقسومة إلى ثلاثة تعديلات مستقلة:
 * إدارية، تشغيلية، تجارية. تبني الحقول التي تُرسَل إلى `updateCloudProfile` (أو الفئة) بعد التحقق، ولا تكتب شيئاً بنفسها.
 *
 * مبدأ: لا يُخمَّن شيء. الحقل الفارغ يعني «غير محدد»، والقيم الموروثة لا تُنسخ إلى البروفايل إلا عبر مسار الوراثة نفسه
 * ([ClassificationRules.materialize] داخل المستودع).
 */
object ProfileDetailRules {

    // ---- رموز أخطاء الحقول (تُترجَم في الواجهة)
    const val ERR_NAME_REQUIRED = "ERR_PROFILE_NAME_REQUIRED"
    const val ERR_SPEED_INVALID = "ERR_PROFILE_SPEED_INVALID"
    const val ERR_SHARED_USERS_INVALID = "ERR_PROFILE_SHARED_USERS_INVALID"
    const val ERR_UPTIME_INVALID = "ERR_PROFILE_UPTIME_INVALID"
    const val ERR_TRANSFER_INVALID = "ERR_PROFILE_TRANSFER_INVALID"
    const val ERR_PRICE_INVALID = "ERR_PROFILE_PRICE_INVALID"
    const val ERR_VALIDITY_INVALID = "ERR_PROFILE_VALIDITY_INVALID"

    /** مفاتيح حقول الأخطاء. */
    object Field {
        const val NAME = "name"
        const val SPEED = "speed"
        const val SHARED_USERS = "sharedUsers"
        const val UPTIME = "uptime"
        const val TRANSFER = "transfer"
        const val VALIDITY = "validity"
        const val RETAIL = "retail"
        const val WHOLESALE = "wholesale"
    }

    // =====================================================================================================
    // 1) إداري
    // =====================================================================================================

    data class AdminInput(
        val nameAr: String,
        val nameEn: String,
        val description: String,
        val categoryId: String?,
        val classificationKey: String?
    )

    sealed interface AdminPlan {
        data class Ok(val fields: Map<String, Any?>) : AdminPlan {
            val isEmpty: Boolean get() = fields.isEmpty()
        }
        data class Invalid(val errors: Map<String, String>) : AdminPlan
    }

    fun planAdmin(profile: Profile, input: AdminInput, classifications: List<ProfileClassification>): AdminPlan {
        val nameAr = input.nameAr.trim().replace(Regex("\\s+"), " ")
        if (nameAr.isBlank()) return AdminPlan.Invalid(mapOf(Field.NAME to ERR_NAME_REQUIRED))
        val fields = linkedMapOf<String, Any?>()
        if (nameAr != profile.displayNameAr) fields["display_name_ar"] = nameAr
        val nameEn = input.nameEn.trim()
        // الاسم الإنجليزي الفارغ لا يمسح الموجود (الحقل غير قابل للإفراغ في المستودع)
        if (nameEn.isNotEmpty() && nameEn != profile.displayNameEn) fields["display_name_en"] = nameEn
        val description = input.description.trim().ifEmpty { null }
        if (description != profile.description?.trim()?.ifEmpty { null }) fields["description"] = description
        if (input.categoryId != profile.categoryId) fields["category_id"] = input.categoryId
        if (input.classificationKey != profile.classificationKey) {
            fields["classification_key"] = input.classificationKey
            val target = input.classificationKey?.let { k -> classifications.firstOrNull { it.key == k } }
            fields["own_settings"] = ClassificationRules.ownSettingsToCsv(ownSettingsAfterReassign(profile, target))
        }
        return AdminPlan.Ok(fields)
    }

    /**
     * مجموعة الإعدادات الخاصة بعد ربط البروفايل بتصنيف آخر: البروفايل القديم (لا يعرف ما هو خاص به) يحتفظ بقيمه الحالية
     * (ما يخالف التصنيف الجديد يُعدّ خاصاً، وما يطابقه يرثه)، والبروفايل الذي يتبع الوراثة سلفاً يحتفظ بمجموعته.
     */
    fun ownSettingsAfterReassign(profile: Profile, target: ProfileClassification?): Set<OperationalSetting> =
        profile.ownSettings ?: OperationalSetting.entries.filter { s ->
            target == null || !target.defines(s) ||
                ClassificationRules.pendingChanges(profile.copy(ownSettings = emptySet()), target).contains(s)
        }.toSet()

    // =====================================================================================================
    // 2) تشغيلي
    // =====================================================================================================

    /**
     * مدخلات التعديل التشغيلي. كل إعداد له علم «خاص بالباقة» ([ownSpeed]…): غير مفعّل = يرث من التصنيف (إن عرّفه) فلا تُقرأ نصوصه.
     * السرعة: نص اتجاه واحد (مثل 5M أو 512k)؛ فارغ = بلا حد. المدة/الحجم: فارغ = غير محدد (لا تُخترع قيمة).
     */
    data class OperationalInput(
        val ownSpeed: Boolean,
        val downloadText: String,
        val uploadText: String,
        val ownSharedUsers: Boolean,
        val sharedUsersText: String,
        val ownUptime: Boolean,
        /** null = القيمة الحالية لا تُمثَّل بوحدات التعديل فتبقى كما هي (لا تُمسح بصمت). */
        val uptimeText: String?,
        val uptimeUnit: UptimeUnit,
        val ownTransfer: Boolean,
        /** null = كما سبق (حجم لا يمثّله عدد صحيح من MB). */
        val transferText: String?,
        val transferUnit: TransferUnit,
        /** عدد أجهزة غير محدود (قرار صريح للباقة). null = لا يُلمس. true يغلب [sharedUsersText] ويجعل الإعداد خاصاً بالباقة. */
        val sharedUnlimited: Boolean? = null,
        /** صلاحية غير محدودة. null = لا تُلمس. true تغلب [validityText] وتصفّر الساعات المخزَّنة (كما عند الإنشاء). */
        val validityUnlimited: Boolean? = null,
        /** مدة الصلاحية بعدد صحيح موجب؛ فارغ = غير محددة؛ null = لا تُلمس. */
        val validityText: String? = null,
        /** ساعات الوحدة المختارة: 1 ساعة، 24 يوم، 720 شهر. */
        val validityUnitHours: Int = 24,
        /** true = تبدأ الصلاحية عند الإسناد، false = عند أول مصادقة؛ null = لا تُلمس. */
        val startAssigned: Boolean? = null
    )

    /**
     * خطة تعديل تشغيلي جاهزة للتأكيد والتنفيذ.
     * @property changed الإعدادات التي تتغير قيمتها **الفعلية** (بعد الوراثة).
     * @property touchesRouterProfile تغيّر السرعة/عدد الجلسات: يُدفَع إلى الباقة على الراوتر فيسري فوراً على كل مستخدميه.
     * @property touchesUserLimits تغيّر المدة/الحجم: تُسجَّل في الباقة وتُطبَّق على الحسابات **الجديدة** فقط؛ الحسابات القائمة لا تُمسّ.
     */
    data class OperationalPlan(
        val profileId: String,
        val fields: Map<String, Any?>,
        val changed: Set<OperationalSetting>,
        val touchesRouterProfile: Boolean,
        val touchesUserLimits: Boolean,
        /** تغيّرت الصلاحية أو سياسة بدايتها (تُدفع إلى بروفايل User Manager وتسري على من يُسنَد إليها لاحقاً). */
        val validityChanged: Boolean = false
    ) {
        val isEmpty: Boolean get() = fields.isEmpty()
    }

    sealed interface OperationalOutcome {
        data class Ok(val plan: OperationalPlan) : OperationalOutcome
        data class Invalid(val errors: Map<String, String>) : OperationalOutcome
    }

    /** سرعة اتجاه واحد (kbps): فارغ = 0 (بلا حد)، صيغة فيها «/» أو غير صالحة = null. */
    private fun parseOneWay(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return 0
        if (t.contains('/')) return null
        val r = ProfileInputValidator.parseRateLimit(t) ?: return null
        return r.rxKbps
    }

    fun planOperational(
        profile: Profile,
        classification: ProfileClassification?,
        input: OperationalInput
    ): OperationalOutcome {
        val errors = linkedMapOf<String, String>()
        // لا وراثة بلا تصنيف يعرّف الإعداد
        fun own(flag: Boolean, s: OperationalSetting) = flag || classification == null || !classification.defines(s)
        val ownSpeed = own(input.ownSpeed, OperationalSetting.SPEED)
        // غير المحدود قرار خاص بالباقة صراحةً (لا يُورَّث من تصنيف)
        val sharedUnlimited = input.sharedUnlimited ?: profile.sharedUnlimited
        val sharedUnlimitedChanged = sharedUnlimited != profile.sharedUnlimited
        val ownShared = sharedUnlimited || own(input.ownSharedUsers, OperationalSetting.SHARED_USERS)
        val ownUptime = own(input.ownUptime, OperationalSetting.LIMIT_UPTIME)
        val ownTransfer = own(input.ownTransfer, OperationalSetting.LIMIT_TRANSFER)

        var tx = profile.rateLimitTxKbps
        var rx = profile.rateLimitRxKbps
        if (ownSpeed) {
            val d = parseOneWay(input.downloadText)
            val u = parseOneWay(input.uploadText)
            if (d == null || u == null) errors[Field.SPEED] = ERR_SPEED_INVALID else { tx = d; rx = u }
        }
        var shared = profile.sharedUsers
        if (ownShared && !sharedUnlimited) {
            val n = input.sharedUsersText.trim().toIntOrNull()
            if (n == null || n !in 1..ClassificationRules.MAX_SHARED_USERS) errors[Field.SHARED_USERS] = ERR_SHARED_USERS_INVALID else shared = n
        }
        var uptime = profile.limitUptimeSeconds
        if (ownUptime && input.uptimeText != null) when (val p = UsageLimits.parseUptime(input.uptimeText, input.uptimeUnit)) {
            UsageLimits.Parsed.Unspecified -> uptime = null
            is UsageLimits.Parsed.Value -> uptime = p.amount
            UsageLimits.Parsed.Invalid -> errors[Field.UPTIME] = ERR_UPTIME_INVALID
        }
        var transfer = profile.limitTransferBytes
        if (ownTransfer && input.transferText != null) when (val p = UsageLimits.parseTransfer(input.transferText, input.transferUnit)) {
            UsageLimits.Parsed.Unspecified -> transfer = null
            is UsageLimits.Parsed.Value -> transfer = p.amount
            UsageLimits.Parsed.Invalid -> errors[Field.TRANSFER] = ERR_TRANSFER_INVALID
        }

        // الصلاحية وسياسة بدايتها: لا تُلمس ما لم تُرسَل. غير المحدودة تصفّر الساعات كما عند الإنشاء (لا قيمة مخفية تتعارض مع «غير محدودة»).
        val validityUnlimited = input.validityUnlimited ?: profile.validityUnlimited
        var validityHours = profile.validityHours
        if (input.validityUnlimited != null || input.validityText != null) {
            if (validityUnlimited) validityHours = 0
            else if (input.validityText != null) {
                val t = input.validityText.trim()
                if (t.isEmpty()) validityHours = 0
                else {
                    val n = t.toLongOrNull()?.takeIf { it in 1..Int.MAX_VALUE }
                    val h = n?.let { it * input.validityUnitHours.coerceAtLeast(1) }
                    if (h == null || h > Int.MAX_VALUE) errors[Field.VALIDITY] = ERR_VALIDITY_INVALID else validityHours = h.toInt()
                }
            }
        }
        val validityStart = input.startAssigned?.let { if (it) "assigned" else "first_auth" } ?: profile.validityStart
        if (errors.isNotEmpty()) return OperationalOutcome.Invalid(errors)

        val newOwn = buildSet {
            if (ownSpeed) add(OperationalSetting.SPEED)
            if (ownShared) add(OperationalSetting.SHARED_USERS)
            if (ownUptime) add(OperationalSetting.LIMIT_UPTIME)
            if (ownTransfer) add(OperationalSetting.LIMIT_TRANSFER)
        }
        // بروفايل قديم (ownSettings = null) كل إعداداته خاصة: لا نحوّله إلى صريح ما لم يغيّر المدير المصدر فعلاً
        val ownChanged = if (profile.ownSettings == null) newOwn.size != OperationalSetting.entries.size else newOwn != profile.ownSettings

        val fields = linkedMapOf<String, Any?>()
        if (ownChanged) fields["own_settings"] = ClassificationRules.ownSettingsToCsv(newOwn)
        if (ownSpeed && (tx != profile.rateLimitTxKbps || rx != profile.rateLimitRxKbps)) {
            fields["rate_limit_tx_kbps"] = tx
            fields["rate_limit_rx_kbps"] = rx
        }
        if (ownShared && shared != profile.sharedUsers) fields["shared_users"] = shared
        if (ownUptime && uptime != profile.limitUptimeSeconds) fields["limit_uptime_seconds"] = uptime
        if (ownTransfer && transfer != profile.limitTransferBytes) fields["limit_transfer_bytes"] = transfer
        if (sharedUnlimitedChanged) fields["shared_unlimited"] = sharedUnlimited
        if (validityHours != profile.validityHours) fields["validity_hours"] = validityHours
        if (validityUnlimited != profile.validityUnlimited) fields["validity_unlimited"] = validityUnlimited
        if (validityStart != profile.validityStart) fields["validity_start"] = validityStart
        val validityChanged = validityHours != profile.validityHours || validityUnlimited != profile.validityUnlimited ||
            validityStart != profile.validityStart

        // الأثر على القيم الفعلية: مقارنة المخزَّن بالمحسوب بعد التعديل (يشمل تحوّل إعداد من خاص إلى موروث وبالعكس)
        val after = profile.copy(
            rateLimitTxKbps = tx, rateLimitRxKbps = rx, sharedUsers = shared,
            limitUptimeSeconds = uptime, limitTransferBytes = transfer,
            ownSettings = if (ownChanged) newOwn else profile.ownSettings
        )
        val before = ClassificationRules.effective(profile, classification)
        val now = ClassificationRules.effective(after, classification)
        val changed = buildSet {
            if (before.rxKbps != now.rxKbps || before.txKbps != now.txKbps) add(OperationalSetting.SPEED)
            if (before.sharedUsers != now.sharedUsers || sharedUnlimitedChanged) add(OperationalSetting.SHARED_USERS)
            if (before.limitUptimeSeconds != now.limitUptimeSeconds) add(OperationalSetting.LIMIT_UPTIME)
            if (before.limitTransferBytes != now.limitTransferBytes) add(OperationalSetting.LIMIT_TRANSFER)
        }
        return OperationalOutcome.Ok(
            OperationalPlan(
                profileId = profile.id,
                fields = fields,
                changed = changed,
                // في User Manager كل الإعدادات التشغيلية الأربعة (سرعة/جلسات/وقت/نقل) تعيش في Limitation/Profile على الراوتر.
                touchesRouterProfile = changed.isNotEmpty() || validityChanged,
                touchesUserLimits = OperationalSetting.LIMIT_UPTIME in changed || OperationalSetting.LIMIT_TRANSFER in changed,
                validityChanged = validityChanged
            )
        )
    }

    // =====================================================================================================
    // 3) تجاري
    // =====================================================================================================

    /** أين يُحفظ السعر: على الفئة (النموذج الحالي، يسري على كل باقاتها) أو على الباقة نفسها (باقة بلا فئة / قديمة). */
    enum class PriceTarget { CATEGORY, PROFILE }

    data class CommercialInput(val retailText: String, val wholesaleText: String)

    sealed interface CommercialOutcome {
        /** [category] الفئة بعد التعديل (للهدف CATEGORY)، [profileFields] حقول الباقة (للهدف PROFILE)؛ [noChange] لا تغيير. */
        data class Ok(
            val target: PriceTarget,
            val category: ProfileCategory?,
            val profileFields: Map<String, Any?>,
            val noChange: Boolean
        ) : CommercialOutcome
        data class Invalid(val errors: Map<String, String>) : CommercialOutcome
    }

    /** السعر الذي تعرضه الصفحة وتحرّره: سعر الفئة إن وُجدت فئة، وإلا سعر الباقة القديم. */
    fun priceTarget(category: ProfileCategory?): PriceTarget = if (category != null) PriceTarget.CATEGORY else PriceTarget.PROFILE

    private fun parsePrice(text: String): Long? { // -1 = غير صالح
        val t = text.trim()
        if (t.isEmpty()) return null
        val v = ProfileInputValidator.parsePriceMinor(t) ?: return -1L
        return if (PricingPolicy.isPriceValid(v)) v else -1L
    }

    fun planCommercial(profile: Profile, category: ProfileCategory?, input: CommercialInput): CommercialOutcome {
        val errors = linkedMapOf<String, String>()
        val retail = parsePrice(input.retailText)
        val wholesale = parsePrice(input.wholesaleText)
        if (retail == -1L) errors[Field.RETAIL] = ERR_PRICE_INVALID
        if (wholesale == -1L && category != null) errors[Field.WHOLESALE] = ERR_PRICE_INVALID
        // سعر الباقة القديم إلزامي (لا «غير محدد» على مستوى الباقة: الحقل رقم غير قابل للإفراغ)
        if (category == null && retail == null) errors[Field.RETAIL] = ERR_PRICE_INVALID
        if (errors.isNotEmpty()) return CommercialOutcome.Invalid(errors)

        return if (category != null) {
            val updated = category.copy(retailPriceMinor = retail, wholesalePriceMinor = wholesale)
            CommercialOutcome.Ok(
                PriceTarget.CATEGORY, updated, emptyMap(),
                noChange = updated.retailPriceMinor == category.retailPriceMinor && updated.wholesalePriceMinor == category.wholesalePriceMinor
            )
        } else {
            val fields = if (retail != profile.priceMinor) mapOf<String, Any?>("price_minor" to retail) else emptyMap()
            CommercialOutcome.Ok(PriceTarget.PROFILE, null, fields, noChange = fields.isEmpty())
        }
    }
}

package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.domain.model.OperationalSetting
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.RouterProfileOption
import ps.mgtik.hotspot.domain.model.VALIDITY_START_ASSIGNED
import ps.mgtik.hotspot.domain.model.VALIDITY_START_FIRST_AUTH

/**
 * استيراد إعدادات بروفايل User Manager الفعلية إلى باقة MGTik (نقي، بلا Android ولا شبكة).
 *
 * المصدر الوحيد لثلاثة مسارات كي لا تختلف: الربط ببروفايل موجود (إنشاء)، إعادة التوجيه إلى مرجع قائم
 * (migrateProfileRename)، ومزامنة الراوتر (reconcileRouterProfiles). القاعدة: ما يُحفظ في السحابة والكاش يمثّل ما على الراوتر،
 * فلا تُخزَّن قيمة شكلية (عدد أجهزة 1، صلاحية 0، بداية first_auth...) تختلف عنه.
 */
object RouterProfileImport {
    /** صلاحية الراوتر ليست عدداً صحيحاً من الساعات: لا يمكن تمثيلها في حقل validityHours دون تغيير قيمتها. */
    const val ERR_VALIDITY_NOT_REPRESENTABLE = "ERR_ROUTER_VALIDITY_NOT_REPRESENTABLE"
    /** حدّ الوقت/الحجم على الراوتر خارج المدى الذي يقبله التطبيق (كان سيفشل كل إنشاء مستخدم لاحقاً). */
    const val ERR_LIMIT_NOT_REPRESENTABLE = "ERR_ROUTER_LIMIT_NOT_REPRESENTABLE"

    sealed interface Result {
        data class Ok(val profile: Profile) : Result
        data class Rejected(val code: String, val detail: String) : Result
    }

    /** ساعات الصلاحية كما تُخزَّن: 0 = غير محددة/غير محدودة (يُميَّز بعلم validityUnlimited)، null = لا تُمثَّل بعدد صحيح من الساعات. */
    fun validityHours(option: RouterProfileOption): Int? {
        if (option.validityUnlimited) return 0
        val s = option.validitySeconds ?: return 0
        if (s <= 0L) return 0
        if (s % 3_600L != 0L) return null
        val h = s / 3_600L
        return if (h <= Int.MAX_VALUE) h.toInt() else null
    }

    /**
     * يطبّق إعدادات [option] الفعلية على [base] (الاسم التقني والسرعة وعدد الأجهزة والصلاحية وسياسة البداية وحدّي الوقت/الحجم).
     * @param keepRateIfRouterUnlimited true = سرعة الراوتر 0/0 لا تُكتب فوق سرعة محفوظة (سلوك المزامنة القديم: باقة بلا Limitation مقروءة).
     * @param limitsReliable false = حدّا الوقت/الحجم غير موثوقين (Limitation التطبيق موجودة وغير مربوطة): يبقيان كما هما.
     * @param claimOwnership true = كل الإعدادات الأربعة تصبح خاصة بالباقة (ownSettings) فلا يستبدلها تصنيف لاحقاً (الربط وإعادة التوجيه)؛
     *   false = ownSettings لا تُمسّ (المزامنة الدورية لا تغيّر علاقة الباقة بتصنيفها).
     * يرفض (بلا أي تغيير) ما لا يُمثَّل بدقة بدل أن يخزّن قيمة تقريبية.
     */
    fun import(
        base: Profile,
        option: RouterProfileOption,
        keepRateIfRouterUnlimited: Boolean = false,
        limitsReliable: Boolean = true,
        claimOwnership: Boolean = true
    ): Result {
        val hours = validityHours(option)
            ?: return Result.Rejected(ERR_VALIDITY_NOT_REPRESENTABLE, "Router validity ${option.validitySeconds}s is not a whole number of hours")
        if (limitsReliable) {
            if (!UsageLimits.isUptimeValid(option.limitUptimeSeconds)) {
                return Result.Rejected(ERR_LIMIT_NOT_REPRESENTABLE, "uptime-limit ${option.limitUptimeSeconds}s is out of range")
            }
            if (!UsageLimits.isTransferValid(option.limitTransferBytes)) {
                return Result.Rejected(ERR_LIMIT_NOT_REPRESENTABLE, "transfer-limit ${option.limitTransferBytes}B is out of range")
            }
        }
        val hasRate = option.rateLimitRxKbps > 0 || option.rateLimitTxKbps > 0
        val keepRate = keepRateIfRouterUnlimited && !hasRate
        return Result.Ok(
            base.copy(
                linkedProfileName = option.name,
                rateLimitRxKbps = if (keepRate) base.rateLimitRxKbps else option.rateLimitRxKbps,
                rateLimitTxKbps = if (keepRate) base.rateLimitTxKbps else option.rateLimitTxKbps,
                // غير المحدود لا يُمثَّل بعدد: العدد المخزَّن يبقى (لا يُكتب 1 فوقه) والعلم هو الحقيقة
                sharedUsers = if (option.sharedUnlimited) base.sharedUsers else option.sharedUsers.coerceAtLeast(1),
                sharedUnlimited = option.sharedUnlimited,
                validityHours = hours,
                validityUnlimited = option.validityUnlimited,
                // سياسة بداية غير مقروءة: لا نخترع قيمة، تبقى المخزَّنة (والمخزَّنة الافتراضية first_auth)
                validityStart = option.validityStart?.takeIf { it == VALIDITY_START_FIRST_AUTH || it == VALIDITY_START_ASSIGNED } ?: base.validityStart,
                limitUptimeSeconds = if (limitsReliable) option.limitUptimeSeconds else base.limitUptimeSeconds,
                limitTransferBytes = if (limitsReliable) option.limitTransferBytes else base.limitTransferBytes,
                // quotaMb القديم يُحسب ownTransferBytes عند غياب limitTransferBytes: يُصفَّر عند الربط/إعادة التوجيه كي لا يعيد حداً غير موجود على الراوتر
                // (المزامنة الدورية لا تمسّه: لا نحذف قيمة قديمة؛ يبقى الاختلاف ظاهراً)
                quotaMb = if (limitsReliable && claimOwnership) 0 else base.quotaMb,
                // القيم هنا قيم الراوتر نفسها: لا يجوز أن ترثها/تستبدلها قيم تصنيف عند أي تطبيق لاحق
                ownSettings = if (claimOwnership) OperationalSetting.entries.toSet() else base.ownSettings
            )
        )
    }

    /** حقول Firestore (بأسمائها) التي اختلفت بين [old] و[new] من الحقول التشغيلية فقط؛ own_settings بصيغة القائمة التي تخزّنها السحابة. */
    fun cloudFields(old: Profile, new: Profile): Map<String, Any?> = buildMap {
        if (old.linkedProfileName != new.linkedProfileName) put("linked_profile_name", new.linkedProfileName)
        if (old.rateLimitRxKbps != new.rateLimitRxKbps) put("rate_limit_rx_kbps", new.rateLimitRxKbps)
        if (old.rateLimitTxKbps != new.rateLimitTxKbps) put("rate_limit_tx_kbps", new.rateLimitTxKbps)
        if (old.sharedUsers != new.sharedUsers) put("shared_users", new.sharedUsers)
        if (old.sharedUnlimited != new.sharedUnlimited) put("shared_unlimited", new.sharedUnlimited)
        if (old.validityHours != new.validityHours) put("validity_hours", new.validityHours)
        if (old.validityUnlimited != new.validityUnlimited) put("validity_unlimited", new.validityUnlimited)
        if (old.validityStart != new.validityStart) put("validity_start", new.validityStart)
        if (old.limitUptimeSeconds != new.limitUptimeSeconds) put("limit_uptime_seconds", new.limitUptimeSeconds)
        if (old.limitTransferBytes != new.limitTransferBytes) put("limit_transfer_bytes", new.limitTransferBytes)
        if (old.quotaMb != new.quotaMb) put("quota_mb", new.quotaMb)
        if (old.ownSettings != new.ownSettings) put("own_settings", new.ownSettings?.sortedBy { it.ordinal }?.map { it.wire })
    }

    /** إعدادات الباقة المخزَّنة بصيغة بروفايل الراوتر (للمقارنة والعرض قبل إعادة التوجيه). */
    fun optionOf(profile: Profile): RouterProfileOption = RouterProfileOption(
        name = profile.linkedProfileName,
        rateLimitRxKbps = profile.rateLimitRxKbps,
        rateLimitTxKbps = profile.rateLimitTxKbps,
        sharedUsers = profile.sharedUsers,
        sharedUnlimited = profile.sharedUnlimited,
        validitySeconds = (profile.validityHours.toLong() * 3_600L).takeIf { it > 0 && !profile.validityUnlimited },
        validityUnlimited = profile.validityUnlimited,
        validityStart = profile.validityStart,
        limitUptimeSeconds = profile.limitUptimeSeconds,
        limitTransferBytes = profile.ownTransferBytes
    )

    const val DIFF_SPEED = "speed"
    const val DIFF_SHARED = "shared_users"
    const val DIFF_VALIDITY = "validity"
    const val DIFF_START = "validity_start"
    const val DIFF_UPTIME = "limit_uptime"
    const val DIFF_TRANSFER = "limit_transfer"

    /**
     * الإعدادات التي تختلف بين [stored] (المخزَّنة) و[router] (الفعلية). غير المحدود ≠ 1، والصلاحية غير المحدودة ≠ غير المحددة ≠ قيمة،
     * وسياسة بداية غير مقروءة من الراوتر لا تُعدّ فرقاً (لا دليل). فارغة = متطابقتان.
     */
    fun differing(stored: RouterProfileOption, router: RouterProfileOption): Set<String> = buildSet {
        if (stored.rateLimitRxKbps != router.rateLimitRxKbps || stored.rateLimitTxKbps != router.rateLimitTxKbps) add(DIFF_SPEED)
        if (stored.sharedUnlimited != router.sharedUnlimited ||
            (!router.sharedUnlimited && stored.sharedUsers != router.sharedUsers)) add(DIFF_SHARED)
        if (validityKey(stored) != validityKey(router)) add(DIFF_VALIDITY)
        if (router.validityStart != null && stored.validityStart != router.validityStart) add(DIFF_START)
        if (stored.limitUptimeSeconds != router.limitUptimeSeconds) add(DIFF_UPTIME)
        if (stored.limitTransferBytes != router.limitTransferBytes) add(DIFF_TRANSFER)
    }

    private fun validityKey(o: RouterProfileOption): String =
        if (o.validityUnlimited) "unlimited" else (o.validitySeconds?.takeIf { it > 0 } ?: 0L).toString()
}

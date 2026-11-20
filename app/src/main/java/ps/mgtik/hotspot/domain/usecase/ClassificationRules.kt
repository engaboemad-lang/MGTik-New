package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.domain.model.CategoryStatus
import ps.mgtik.hotspot.domain.model.ClassificationApplyItem
import ps.mgtik.hotspot.domain.model.ClassificationApplyPlan
import ps.mgtik.hotspot.domain.model.EffectiveSettings
import ps.mgtik.hotspot.domain.model.OperationalSetting
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.ProfileCategory
import ps.mgtik.hotspot.domain.model.ProfileClassification
import ps.mgtik.hotspot.domain.model.ProfileStatus
import ps.mgtik.hotspot.domain.model.SettingSource
import ps.mgtik.hotspot.domain.model.SystemClass

/**
 * قواعد تصنيف الباقات التشغيلي (نقية، تستعملها كل الطبقات ولا تُكرَّر).
 *
 * النموذج: التصنيف يعرّف الإعدادات التشغيلية الافتراضية؛ البروفايل يرثها ما لم يحدّد إعداداً خاصاً به
 * ([Profile.ownSettings]). حقول البروفايل التشغيلية تحمل دائماً القيمة **المطبَّقة** (يقرؤها التوليد والراوتر كما هي)،
 * وتعديل التصنيف لا يغيّرها: التغيير يُراجَع ويُطبَّق صراحةً عبر [planApply].
 * لا يلمس هذا الملف أي سعر.
 */
object ClassificationRules {
    const val ERR_NAME_REQUIRED = "ERR_CLASSIFICATION_NAME_REQUIRED"
    const val ERR_NAME_EXISTS = "ERR_CLASSIFICATION_NAME_EXISTS"
    const val ERR_SPEED_INVALID = "ERR_CLASSIFICATION_SPEED_INVALID"
    const val ERR_SHARED_USERS_INVALID = "ERR_CLASSIFICATION_SHARED_USERS_INVALID"
    const val ERR_UPTIME_INVALID = "ERR_CLASSIFICATION_UPTIME_INVALID"
    const val ERR_TRANSFER_INVALID = "ERR_CLASSIFICATION_TRANSFER_INVALID"
    const val ERR_INACTIVE = "ERR_CLASSIFICATION_INACTIVE"

    const val MAX_SHARED_USERS = 9_999
    private const val MAX_KBPS = 10_000_000 // نفس سقف ProfileInputValidator (10 Gbps)

    /** حقل ← رمز الخطأ. فارغ = صالح. الأسماء: name, speed, sharedUsers, uptime, transfer. */
    fun validate(c: ProfileClassification): Map<String, String> = buildMap {
        if (c.nameAr.isBlank()) put("name", ERR_NAME_REQUIRED)
        val speedBad = listOfNotNull(c.downloadKbps, c.uploadKbps).any { it !in 1..MAX_KBPS }
        if (speedBad) put("speed", ERR_SPEED_INVALID)
        c.sharedUsers?.let { if (it !in 1..MAX_SHARED_USERS) put("sharedUsers", ERR_SHARED_USERS_INVALID) }
        if (!UsageLimits.isUptimeValid(c.limitUptimeSeconds)) put("uptime", ERR_UPTIME_INVALID)
        if (!UsageLimits.isTransferValid(c.limitTransferBytes)) put("transfer", ERR_TRANSFER_INVALID)
    }

    /** الاسم فريد بين التصنيفات (قص + دمج المسافات + حالة أحرف)، ويشمل المؤرشفة. */
    fun nameClash(candidate: ProfileClassification, all: List<ProfileClassification>): Boolean {
        val n = PricingPolicy.normalizeName(candidate.nameAr)
        return n.isNotEmpty() && all.any { it.key != candidate.key && PricingPolicy.normalizeName(it.nameAr) == n }
    }

    /** ربط جديد (إنشاء/تعديل بروفايل) لا يقبل إلا تصنيفاً موجوداً ونشطاً. null = مسموح. */
    fun checkAssignable(classification: ProfileClassification?): String? = when {
        classification == null -> ERR_INACTIVE
        classification.status != CategoryStatus.ACTIVE -> ERR_INACTIVE
        else -> null
    }

    /**
     * القيم الفعلية للبروفايل:
     *  - بلا تصنيف، أو بروفايل قديم ([Profile.ownSettings] = null): قيمه الخاصة كلها (لا وراثة بالتخمين).
     *  - إعداد ضمن [Profile.ownSettings] أو لا يعرّفه التصنيف: قيمة البروفايل.
     *  - غير ذلك: قيمة التصنيف (موروثة).
     */
    fun effective(profile: Profile, c: ProfileClassification?): EffectiveSettings {
        val own = profile.ownSettings
        // عدد الأجهزة غير المحدود قرار صريح للباقة (أو قيمة الراوتر الفعلية): لا يستبدله عدد التصنيف ولو لم يُدرج في ownSettings،
        // وإلا بدا الإعداد «معلَّقاً» دائماً وكتب تطبيق التصنيف عدداً في السحابة فوق باقة غير محدودة على الراوتر.
        fun inherits(s: OperationalSetting) = c != null && own != null && s !in own && c.defines(s) &&
            !(s == OperationalSetting.SHARED_USERS && profile.sharedUnlimited)
        val sources = OperationalSetting.entries.associateWith {
            if (inherits(it)) SettingSource.INHERITED else SettingSource.PROFILE
        }
        val speedInherited = inherits(OperationalSetting.SPEED)
        return EffectiveSettings(
            rxKbps = if (speedInherited) c!!.uploadKbps ?: 0 else profile.rateLimitRxKbps,
            txKbps = if (speedInherited) c!!.downloadKbps ?: 0 else profile.rateLimitTxKbps,
            sharedUsers = if (inherits(OperationalSetting.SHARED_USERS)) c!!.sharedUsers!! else profile.sharedUsers,
            limitUptimeSeconds = if (inherits(OperationalSetting.LIMIT_UPTIME)) c!!.limitUptimeSeconds else profile.limitUptimeSeconds,
            limitTransferBytes = if (inherits(OperationalSetting.LIMIT_TRANSFER)) c!!.limitTransferBytes else profile.ownTransferBytes,
            sources = sources
        )
    }

    /** الإعدادات التي تختلف فيها القيمة المخزَّنة (المطبَّقة) عن الفعلية الآن. */
    fun pendingChanges(profile: Profile, c: ProfileClassification?): Set<OperationalSetting> {
        val e = effective(profile, c)
        return buildSet {
            if (e.rxKbps != profile.rateLimitRxKbps || e.txKbps != profile.rateLimitTxKbps) add(OperationalSetting.SPEED)
            if (e.sharedUsers != profile.sharedUsers) add(OperationalSetting.SHARED_USERS)
            if (e.limitUptimeSeconds != profile.limitUptimeSeconds) add(OperationalSetting.LIMIT_UPTIME)
            if (e.limitTransferBytes != profile.ownTransferBytes) add(OperationalSetting.LIMIT_TRANSFER)
        }
    }

    /** البروفايل بعد تثبيت القيم الفعلية في حقوله (بلا تغيير في ownSettings ولا في أي حقل مالي أو تجاري). */
    fun materialize(profile: Profile, c: ProfileClassification?): Profile {
        val e = effective(profile, c)
        return profile.copy(
            rateLimitRxKbps = e.rxKbps,
            rateLimitTxKbps = e.txKbps,
            sharedUsers = e.sharedUsers,
            limitUptimeSeconds = e.limitUptimeSeconds,
            limitTransferBytes = e.limitTransferBytes
        )
    }

    /**
     * خطة تطبيق تصنيف على بروفايلاته (للمعاينة والتنفيذ معاً فلا يختلفان):
     * البروفايلات القديمة (ownSettings = null) والمؤرشفة لا تدخل الخطة، ولا من لا تغيير عنده.
     */
    fun planApply(c: ProfileClassification, profiles: List<Profile>, categories: List<ProfileCategory>): ClassificationApplyPlan {
        val items = profiles
            .filter { it.classificationKey == c.key && it.ownSettings != null && it.status != ProfileStatus.ARCHIVED }
            .mapNotNull { p ->
                val changed = pendingChanges(p, c)
                if (changed.isEmpty()) return@mapNotNull null
                val subscription = SystemClassRules.classOf(p, categories) == SystemClass.SUBSCRIPTION
                ClassificationApplyItem(
                    profile = p,
                    updated = materialize(p, c),
                    changed = changed,
                    // في User Manager كل الإعدادات التشغيلية الأربعة (سرعة/جلسات/وقت/نقل) تعيش في Limitation/Profile على الراوتر.
                    touchesRouterProfile = changed.isNotEmpty(),
                    touchesUserLimits = OperationalSetting.LIMIT_UPTIME in changed || OperationalSetting.LIMIT_TRANSFER in changed,
                    userLimitsManagedBySubscription = subscription
                )
            }
        return ClassificationApplyPlan(c.key, items)
    }

    /** ترميز ownSettings نصاً (طابور العمليات لا يقبل القوائم): "speed,shared_users"، النص الفارغ = مجموعة فارغة. */
    fun ownSettingsToCsv(set: Set<OperationalSetting>): String = set.sortedBy { it.ordinal }.joinToString(",") { it.wire }

    fun ownSettingsFromCsv(csv: String?): Set<OperationalSetting>? =
        csv?.split(',')?.mapNotNull { OperationalSetting.fromWire(it.trim()) }?.toSet()
}

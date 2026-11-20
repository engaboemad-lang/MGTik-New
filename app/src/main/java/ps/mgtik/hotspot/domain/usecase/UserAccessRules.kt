package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.domain.model.RouterUserSnapshot
import java.time.Instant

/** سبب منع الاستخدام: ثلاث حالات مختلفة تتطلب معالجة مختلفة (تمديد مدة ≠ إعادة ضبط عدّاد ≠ تفعيل حساب). */
enum class AccessBlock { DISABLED, TIME_EXPIRED, UPTIME_EXHAUSTED, TRANSFER_EXHAUSTED, DOWNLOAD_EXHAUSTED, UPLOAD_EXHAUSTED }

/**
 * قواعد نقية: هل يستطيع المستخدم الاستخدام، وإن لا فلماذا؟
 * - انتهاء الصلاحية الزمنية (تاريخ انتهاء الفترة) ≠ استنفاد limit-uptime ≠ استنفاد limit-bytes-total ≠ تعطيل الحساب.
 * - المقارنة بعدّادات الفترة الحالية ([RouterUserSnapshot.usedUptimeSeconds]/[usedBytes]) لا بالإجمالي التاريخي.
 * - قيمة غير معروفة (null) لا تُعدّ استنفاداً: لا نمنع مستخدماً على تخمين.
 */
object UserAccessRules {

    fun blocks(snapshot: RouterUserSnapshot, periodEnd: Instant?, now: Instant): Set<AccessBlock> = buildSet {
        if (snapshot.disabled) add(AccessBlock.DISABLED)
        if (periodEnd != null && !periodEnd.isAfter(now)) add(AccessBlock.TIME_EXPIRED)
        val lu = snapshot.limitUptimeSeconds; val uu = snapshot.usedUptimeSeconds
        if (lu != null && uu != null && uu >= lu) add(AccessBlock.UPTIME_EXHAUSTED)
        val lt = snapshot.limitTransferBytes; val ut = snapshot.usedBytes
        if (lt != null && ut != null && ut >= lt) add(AccessBlock.TRANSFER_EXHAUSTED)
        val ld = snapshot.limitDownloadBytes; val ud = snapshot.usedDownloadBytes
        if (ld != null && ud != null && ud >= ld) add(AccessBlock.DOWNLOAD_EXHAUSTED)
        val lp = snapshot.limitUploadBytes; val up = snapshot.usedUploadBytes
        if (lp != null && up != null && up >= lp) add(AccessBlock.UPLOAD_EXHAUSTED)
    }

    fun canUse(snapshot: RouterUserSnapshot, periodEnd: Instant?, now: Instant): Boolean = blocks(snapshot, periodEnd, now).isEmpty()

    /** الأولوية للعرض: التعطيل ثم الزمن ثم المدة ثم الحجم؛ null = يستطيع الاستخدام. */
    fun primary(blocks: Set<AccessBlock>): AccessBlock? =
        listOf(AccessBlock.DISABLED, AccessBlock.TIME_EXPIRED, AccessBlock.UPTIME_EXHAUSTED, AccessBlock.TRANSFER_EXHAUSTED, AccessBlock.DOWNLOAD_EXHAUSTED, AccessBlock.UPLOAD_EXHAUSTED).firstOrNull { it in blocks }

    /** المتبقي (ثوانٍ/بايت) للحد؛ null = بلا حد أو غير معروف. لا يهبط تحت الصفر. */
    fun remainingUptimeSeconds(s: RouterUserSnapshot): Long? =
        if (s.limitUptimeSeconds != null && s.usedUptimeSeconds != null) (s.limitUptimeSeconds - s.usedUptimeSeconds).coerceAtLeast(0L) else null

    fun remainingBytes(s: RouterUserSnapshot): Long? =
        if (s.limitTransferBytes != null && s.usedBytes != null) (s.limitTransferBytes - s.usedBytes).coerceAtLeast(0L) else null

    fun remainingDownloadBytes(s: RouterUserSnapshot): Long? =
        if (s.limitDownloadBytes != null && s.usedDownloadBytes != null) (s.limitDownloadBytes - s.usedDownloadBytes).coerceAtLeast(0L) else null

    fun remainingUploadBytes(s: RouterUserSnapshot): Long? =
        if (s.limitUploadBytes != null && s.usedUploadBytes != null) (s.limitUploadBytes - s.usedUploadBytes).coerceAtLeast(0L) else null
}

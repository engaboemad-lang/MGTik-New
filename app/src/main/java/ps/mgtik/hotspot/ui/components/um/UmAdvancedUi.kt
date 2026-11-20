package ps.mgtik.hotspot.ui.components.um

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.data.userman.UmLimitIssue
import ps.mgtik.hotspot.data.userman.UmLimitRules
import ps.mgtik.hotspot.data.userman.UmLimitation
import ps.mgtik.hotspot.data.userman.UmResetInterval
import ps.mgtik.hotspot.data.userman.UmSchedule
import ps.mgtik.hotspot.data.userman.UmScheduleRules
import ps.mgtik.hotspot.data.userman.UmWeekday
import ps.mgtik.hotspot.domain.model.PackageLimitExtras

/**
 * حالة الإعدادات المتقدمة: أولوية الحركة، فترة تصفير العدّادات، وجدول التشغيل.
 * الافتراضات لا تفرض شيئاً: أولوية عادية (8)، بلا تصفير دوري، بلا جدولة (طوال الأسبوع وعلى مدار الساعة).
 */
class UmAdvancedFormState(initial: UmLimitation? = null) {
    private val initPriority = initial?.priority ?: UmLimitRules.PRIORITY_DEFAULT
    private val initReset = initial?.resetInterval ?: UmResetInterval.DISABLED
    /** للتعديل: null = لم تتغير فلا تُرسل. */
    val changedPriority: Int? get() = priority.takeIf { it != initPriority }
    val changedReset: UmResetInterval? get() = reset.takeIf { it != initReset }

    var priority by mutableStateOf(initial?.priority ?: UmLimitRules.PRIORITY_DEFAULT)
    var reset by mutableStateOf(initial?.resetInterval ?: UmResetInterval.DISABLED)
    var scheduleOn by mutableStateOf(false)
    var days by mutableStateOf(UmWeekday.ALL)
    var from by mutableStateOf("08:00")
    var till by mutableStateOf("16:00")

    private fun seconds(hm: String): Int {
        val p = hm.trim().split(':')
        val h = p.getOrNull(0)?.toIntOrNull() ?: return -1
        val m = p.getOrNull(1)?.toIntOrNull() ?: return -1
        if (p.size != 2 || h !in 0..23 || m !in 0..59) return -1
        return if (h == 23 && m == 59) UmSchedule.MAX_SECONDS else h * 3600 + m * 60
    }

    /** null = بلا جدولة. قيمة وقت غير مفهومة تُنتج جدولاً غير صالح تكشفه [UmScheduleRules]. */
    val schedule: UmSchedule? get() = if (scheduleOn) UmSchedule(seconds(from), seconds(till), days) else null

    fun toExtras(base: PackageLimitExtras): PackageLimitExtras = base.copy(
        priority = priority.takeIf { it != UmLimitRules.PRIORITY_DEFAULT },
        resetInterval = reset.takeIf { it != UmResetInterval.DISABLED }?.wire,
        scheduleFromSeconds = schedule?.fromSeconds, scheduleTillSeconds = schedule?.tillSeconds,
        scheduleDays = schedule?.let { UmSchedule.weekdaysWire(it.weekdays) }.orEmpty()
    )

    fun scheduleIssues(): List<UmLimitIssue> = schedule?.let { UmScheduleRules.validate(it) }.orEmpty()
}

@Composable
private fun weekdayLabel(d: UmWeekday): String = stringResource(
    when (d) {
        UmWeekday.SUNDAY -> R.string.um_day_sunday; UmWeekday.MONDAY -> R.string.um_day_monday; UmWeekday.TUESDAY -> R.string.um_day_tuesday
        UmWeekday.WEDNESDAY -> R.string.um_day_wednesday; UmWeekday.THURSDAY -> R.string.um_day_thursday
        UmWeekday.FRIDAY -> R.string.um_day_friday; UmWeekday.SATURDAY -> R.string.um_day_saturday
    }
)

@Composable
private fun resetLabel(r: UmResetInterval): String = stringResource(
    when (r) {
        UmResetInterval.DISABLED -> R.string.um_reset_disabled; UmResetInterval.HOURLY -> R.string.um_reset_hourly
        UmResetInterval.DAILY -> R.string.um_reset_daily; UmResetInterval.WEEKLY -> R.string.um_reset_weekly
        UmResetInterval.MONTHLY -> R.string.um_reset_monthly
    }
)

@Composable
fun UmAdvancedFormSection(form: UmAdvancedFormState, issues: List<UmLimitIssue>, enabled: Boolean = true, showSchedule: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // أولوية الحركة: 1 الأعلى … 8 العادية
        Text(stringResource(R.string.um_priority_title), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (UmLimitRules.PRIORITY_HIGHEST..UmLimitRules.PRIORITY_DEFAULT).forEach { p ->
                FilterChip(selected = form.priority == p, enabled = enabled, onClick = { form.priority = p }, label = { Text(p.toString()) })
            }
        }
        Text(stringResource(R.string.um_priority_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // التصفير الدوري
        Text(stringResource(R.string.um_reset_title), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            UmResetInterval.entries.forEach { r ->
                FilterChip(selected = form.reset == r, enabled = enabled, onClick = { form.reset = r }, label = { Text(resetLabel(r)) })
            }
        }
        Text(stringResource(R.string.um_reset_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // الجدولة (خاصية رابط الباقة لا الـ Limitation: تُحدَّد عند إنشاء الباقة)
        if (showSchedule) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = form.scheduleOn, enabled = enabled, onCheckedChange = { form.scheduleOn = it })
            Text(stringResource(R.string.um_schedule_title), style = MaterialTheme.typography.labelLarge)
        }
        if (showSchedule && form.scheduleOn) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                UmWeekday.entries.forEach { d ->
                    FilterChip(
                        selected = d in form.days, enabled = enabled,
                        onClick = { form.days = if (d in form.days) form.days - d else form.days + d },
                        label = { Text(weekdayLabel(d)) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(form.from, { form.from = it.take(5) }, label = { Text(stringResource(R.string.um_schedule_from)) }, singleLine = true, enabled = enabled, modifier = Modifier.weight(1f))
                OutlinedTextField(form.till, { form.till = it.take(5) }, label = { Text(stringResource(R.string.um_schedule_till)) }, singleLine = true, enabled = enabled, modifier = Modifier.weight(1f))
            }
            Text(stringResource(R.string.um_schedule_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        issues.firstOrNull()?.let { Text(limitIssueText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

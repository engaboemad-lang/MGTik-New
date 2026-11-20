package ps.mgtik.hotspot.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ps.mgtik.hotspot.R
import java.util.Locale

/** عرض قيود الاستخدام للمستخدم: null = «غير محدد» (لا يُعرض صفر). المدة بالثواني والحجم بالبايت (ثنائي 1024). */
@Composable
fun formatLimitDuration(seconds: Long?): String {
    if (seconds == null) return stringResource(R.string.limit_unlimited)
    val days = seconds / 86_400
    val hours = seconds % 86_400 / 3_600
    val minutes = seconds % 3_600 / 60
    return when {
        days > 0 -> stringResource(R.string.limit_duration_days_hours_fmt, days, hours)
        hours > 0 -> stringResource(R.string.limit_duration_hours_minutes_fmt, hours, minutes)
        minutes > 0 -> stringResource(R.string.limit_duration_minutes_fmt, minutes)
        else -> stringResource(R.string.limit_duration_seconds_fmt, seconds)
    }
}

@Composable
fun formatLimitBytes(bytes: Long?): String {
    if (bytes == null) return stringResource(R.string.limit_unlimited)
    fun trimmed(value: Double) = String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
    return when {
        bytes >= 1_073_741_824L -> stringResource(R.string.limit_bytes_gb_fmt, trimmed(bytes / 1_073_741_824.0))
        bytes >= 1_048_576L -> stringResource(R.string.limit_bytes_mb_fmt, trimmed(bytes / 1_048_576.0))
        bytes >= 1_024L -> stringResource(R.string.limit_bytes_kb_fmt, trimmed(bytes / 1_024.0))
        else -> stringResource(R.string.limit_bytes_b_fmt, bytes)
    }
}

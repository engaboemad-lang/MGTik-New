@file:OptIn(ExperimentalMaterial3Api::class)

package ps.mgtik.hotspot.ui.components.userman

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.data.userman.UmAction
import ps.mgtik.hotspot.data.userman.UmCheck
import ps.mgtik.hotspot.data.userman.UmCheckKeys
import ps.mgtik.hotspot.data.userman.UmGeneration
import ps.mgtik.hotspot.data.userman.UmProvisionStep
import ps.mgtik.hotspot.data.userman.UmStatus
import ps.mgtik.hotspot.data.userman.UmStep
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.theme.LocalMgtikExtendedColors
import ps.mgtik.hotspot.ui.viewmodel.UserManViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ───────────────────────────── نصوص ─────────────────────────────

/** نص فحص الجاهزية؛ مفتاح غير معروف يُعرض كما هو (لا نصاً مضمّناً مترجماً). */
@StringRes
fun umCheckLabelRes(key: String): Int? = when (key) {
    UmCheckKeys.PACKAGE -> R.string.um_check_package
    UmCheckKeys.ENABLED -> R.string.um_check_enabled
    UmCheckKeys.USE_PROFILES -> R.string.um_check_use_profiles
    UmCheckKeys.CUSTOMER -> R.string.um_check_customer
    UmCheckKeys.UM_ROUTER -> R.string.um_check_um_router
    UmCheckKeys.RADIUS -> R.string.um_check_radius
    UmCheckKeys.SECRET_MATCH -> R.string.um_check_secret_match
    UmCheckKeys.USE_RADIUS -> R.string.um_check_use_radius
    UmCheckKeys.INCOMING -> R.string.um_check_radius_incoming
    else -> null
}

/** ما يفعله المستخدم يدوياً حين لا يستطيع التطبيق إصلاح الفحص. */
@StringRes
fun umManualHintRes(key: String): Int? = when (key) {
    UmCheckKeys.PACKAGE -> R.string.um_manual_package
    UmCheckKeys.CUSTOMER -> R.string.um_manual_customer
    // يصلحها التطبيق من زر التهيئة بعد تأكيد المستخدم (لا تعديل تلقائي)
    UmCheckKeys.ENABLED, UmCheckKeys.USE_PROFILES, UmCheckKeys.UM_ROUTER, UmCheckKeys.RADIUS,
    UmCheckKeys.SECRET_MATCH, UmCheckKeys.USE_RADIUS, UmCheckKeys.INCOMING -> R.string.um_fix_with_setup
    else -> null
}

@StringRes
fun umStepLabelRes(step: UmStep): Int = when (step) {
    UmStep.ENABLE -> R.string.um_step_enable
    UmStep.NAS -> R.string.um_step_nas
    UmStep.RADIUS -> R.string.um_step_radius
    UmStep.HOTSPOT_PROFILES -> R.string.um_step_hotspot
    UmStep.INCOMING -> R.string.um_step_incoming
}

@StringRes
fun umActionLabelRes(action: UmAction): Int = when (action) {
    UmAction.CREATED -> R.string.um_action_created
    UmAction.UPDATED -> R.string.um_action_updated
    UmAction.UNCHANGED -> R.string.um_action_unchanged
    UmAction.FAILED -> R.string.um_action_failed
}

@StringRes
fun umGenerationLabelRes(g: UmGeneration): Int = if (g == UmGeneration.V7) R.string.um_gen_v7 else R.string.um_gen_v6

// ───────────────────────────── مكوّنات ─────────────────────────────

@Composable
fun UmCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(Dimens.RadiusLG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM), content = content)
    }
}

@Composable
fun UmCardTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/** ملخص الحالة: الجيل والإصدار والجاهزية والأعداد وحجم القاعدة. */
@Composable
fun UserManStatusSummary(status: UmStatus, checkedAtMillis: Long) {
    val ok = LocalMgtikExtendedColors.current.success
    Text(
        stringResource(umGenerationLabelRes(status.generation)) +
            if (status.routerOsVersion.isNotBlank()) "  ·  " + stringResource(R.string.um_routeros_fmt, status.routerOsVersion) else "",
        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
    )
    Text(
        stringResource(if (status.isReady) R.string.um_ready else R.string.um_not_ready),
        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
        color = if (status.isReady) ok else MaterialTheme.colorScheme.error
    )
    if (status.packageInstalled) {
        Text(stringResource(R.string.um_counts_fmt, status.userCount, status.packageCount), style = MaterialTheme.typography.bodySmall)
        status.dbSizeBytes?.let {
            Text(
                stringResource(R.string.um_db_size_fmt, android.text.format.Formatter.formatShortFileSize(LocalContext.current, it)),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    if (checkedAtMillis > 0) {
        Text(
            stringResource(R.string.um_last_check_fmt, SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(checkedAtMillis))),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** قائمة الفحوص ✓/✗ مع إرشاد يدوي تحت الفحص الذي لا يصلحه التطبيق. */
@Composable
fun UserManChecklist(checks: List<UmCheck>) {
    val ok = LocalMgtikExtendedColors.current.success
    checks.forEach { c ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (c.ok) Icons.Default.CheckCircle else Icons.Default.Cancel, contentDescription = null,
                tint = if (c.ok) ok else MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(Dimens.SpaceSM))
            Text(umCheckLabelRes(c.key)?.let { stringResource(it) } ?: c.key, style = MaterialTheme.typography.bodyMedium)
        }
        if (!c.ok) {
            umManualHintRes(c.key)?.let {
                Text(stringResource(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 28.dp))
            }
        }
    }
}

/** نتيجة التهيئة خطوة بخطوة. */
@Composable
fun UserManProvisionResult(steps: List<UmProvisionStep>) {
    steps.forEach { s ->
        val failed = s.action == UmAction.FAILED
        Text(
            stringResource(umStepLabelRes(s.step)) + " — " + stringResource(umActionLabelRes(s.action)) +
                if (s.detail.isNotBlank() && failed) ": ${s.detail}" else "",
            style = MaterialTheme.typography.bodySmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

// ───────────────────────────── بطاقة الصيانة (قسم التشخيص والصيانة في الإعدادات) ─────────────────────────────

/**
 * بطاقة صيانة User Manager داخل «البيانات والصيانة»: الحالة، فحص الجاهزية، نسخة احتياطية، تحسين القاعدة، وتنظيف بقايا
 * الحدود، ورابط شاشة الإعداد والاتصال. كل عملية تحتاج اتصالاً فعلياً بالراوتر (المدير المركزي يرفضها فوراً إن لم يكن متاحاً).
 */
@Composable
fun UserManMaintenanceCard(viewModel: UserManViewModel, onOpenSetup: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val router by viewModel.routerStatus.collectAsState()
    var confirmClean by remember { mutableStateOf(false) }
    // أول عرض: فحص تلقائي إن كان الراوتر متصلاً (قراءة فقط)
    LaunchedEffect(router.isConnected) { if (router.isConnected && state.status == null) viewModel.refresh() }

    UmCard {
        UmCardTitle(stringResource(R.string.um_maint_title))
        if (state.isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        val status = state.status
        when {
            status != null -> {
                UserManStatusSummary(status, state.statusCheckedAtMillis)
                if (!status.isReady) UserManChecklist(status.checks.filter { !it.ok })
            }
            state.statusErrorRes != null -> Text(stringResource(state.statusErrorRes!!), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            !router.isConnected -> Text(stringResource(R.string.um_router_required), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.statusErrorRes?.takeIf { status != null }?.let {
            Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        MaintButton(Icons.Default.Refresh, R.string.um_check_now, enabled = !state.isBusy) { viewModel.refresh() }
        MaintButton(Icons.Default.Backup, R.string.um_maint_backup, enabled = !state.isBusy && router.isConnected) { viewModel.backup() }
        state.lastBackupName?.let { Text(stringResource(R.string.um_backup_name_fmt, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        // التحسين v7 فقط: لا يُعرض على v6 بدل زر ينتهي برسالة «غير مدعوم»
        if (status?.generation != UmGeneration.V6) {
            MaintButton(Icons.Default.Speed, R.string.um_maint_optimize, enabled = !state.isBusy && router.isConnected) { viewModel.optimize() }
        }
        MaintButton(Icons.Default.DeleteSweep, R.string.um_maint_orphans_scan, enabled = !state.isBusy && router.isConnected) { viewModel.scanOrphans() }
        state.orphans?.let { list ->
            if (list.isEmpty()) {
                Text(stringResource(R.string.um_orphans_none), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(R.string.um_orphans_found_fmt, list.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                list.take(5).forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
                if (list.size > 5) Text("…", style = MaterialTheme.typography.labelSmall)
                Button(onClick = { confirmClean = true }, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.um_orphans_clean))
                }
            }
        }
        state.lastCleanedCount?.let { Text(stringResource(R.string.um_orphans_cleaned_fmt, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        MaintButton(Icons.Default.Tune, R.string.um_open_setup, enabled = true, onClick = onOpenSetup)
        Text(stringResource(R.string.um_maint_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (confirmClean) {
        AlertDialog(
            onDismissRequest = { confirmClean = false },
            title = { Text(stringResource(R.string.um_orphans_clean), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.um_orphans_confirm_body)) },
            confirmButton = { Button(onClick = { confirmClean = false; viewModel.cleanOrphans() }) { Text(stringResource(R.string.um_confirm_run)) } },
            dismissButton = { TextButton(onClick = { confirmClean = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

@Composable
private fun MaintButton(icon: androidx.compose.ui.graphics.vector.ImageVector, @StringRes labelRes: Int, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(Dimens.SpaceSM))
        Text(stringResource(labelRes))
    }
}

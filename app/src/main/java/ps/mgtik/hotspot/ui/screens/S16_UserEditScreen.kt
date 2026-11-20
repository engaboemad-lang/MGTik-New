package ps.mgtik.hotspot.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.usecase.TransferUnit
import ps.mgtik.hotspot.domain.usecase.UptimeUnit
import ps.mgtik.hotspot.domain.usecase.UsageLimits
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.viewmodel.UsersViewModel
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * S16 (تعديل) - تعديل الحقول المسموحة في سجل مستخدم مُدار، وهي ما يحمله LocalUserRecord فقط:
 *  - المالك: لمستخدم ADOPTED فقط (مستخدم GENERATED مرتبط بدفعة وفاتورة باسم مالكه فيُعرض مقفلاً).
 *  - قيد المدة وقيد البيانات: يُطبَّقان على الراوتر ثم يُحفظان في السحابة والكاش.
 * اسم الدخول والدفعة والمصدر ثابتة. الحفظ يمر بالطابور (Pending) ولا يُعرض مكتملاً قبل تنفيذه.
 * التعارض: تُقارن updatedAt المرئي عند الفتح بالحالي؛ إن تغيّر السجل أثناء التحرير يُمنع الحفظ حتى «إعادة التحميل».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S16_UserEditScreen(
    viewModel: UsersViewModel,
    recordUuid: String,
    owners: List<OwnerAccount>,
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val record = state.managedUsers.firstOrNull { it.recordUuid == recordUuid }
    // اللقطة التي بُني عليها التحرير؛ تُؤخذ مرة عند الفتح ولا تتبع تحديثات السحابة اللاحقة (وإلا ضاع كشف التعارض)
    var baseline by remember(recordUuid) { mutableStateOf<LocalUserRecord?>(null) }

    LaunchedEffect(recordUuid) { viewModel.clearEditState() }
    LaunchedEffect(record != null) { if (baseline == null && record != null) baseline = record }
    LaunchedEffect(state.editAccepted) {
        if (state.editAccepted) {
            viewModel.clearEditState()
            onDone()
        }
    }

    Scaffold(
        topBar = {
            MgtikTopAppBar(
                title = { Text(stringResource(R.string.user_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        val base = baseline
        if (base == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.isUsersLoading) CircularProgressIndicator()
                else Text(stringResource(R.string.user_edit_not_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        val pending = state.pendingOps[recordUuid]
        val stale = record != null && record.updatedAt != base.updatedAt
        val gone = record == null && !state.isUsersLoading
        val canEditOwner = base.origin == UserOrigin.ADOPTED

        // الحقول تُعاد تهيئتها عند تغيّر اللقطة (إعادة التحميل بعد تعارض)
        val formKey = base.recordUuid + "|" + base.updatedAt
        var ownerId by remember(formKey) { mutableStateOf(base.ownerId) }
        var showOwnerPicker by remember { mutableStateOf(false) }

        val hasChanges = ownerId != base.ownerId
        val saving = state.isEditSaving
        val canSave = !saving && pending == null && !stale && !gone && hasChanges
        val ltrText = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)
        val ownerName = owners.firstOrNull { it.ownerId == ownerId }?.name?.takeIf { it.isNotBlank() } ?: ownerId

        if (showOwnerPicker) {
            OwnerPickerDialog(
                owners = owners.filter { it.status != OwnerStatus.ARCHIVED },
                onPick = { ownerId = it.ownerId; showOwnerPicker = false },
                onDismiss = { showOwnerPicker = false }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Dimens.ScreenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing)
        ) {
            if (saving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            // هوية السجل (للقراءة فقط)
            Card(
                shape = RoundedCornerShape(Dimens.RadiusLG),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                    Text(base.routerUsername, style = ltrText.copy(fontWeight = FontWeight.Bold))
                    Text(
                        stringResource(if (canEditOwner) R.string.user_origin_adopted else R.string.user_origin_generated),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.user_edit_identity_locked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (pending != null) {
                InfoBanner(stringResource(R.string.user_edit_pending_banner), isError = pending.failed)
            }
            if (gone) {
                InfoBanner(stringResource(R.string.user_edit_gone_banner), isError = true)
            } else if (stale) {
                InfoBanner(
                    text = stringResource(R.string.user_edit_stale_banner),
                    isError = true,
                    actionLabel = stringResource(R.string.user_edit_reload),
                    onAction = { baseline = record }
                )
            }

            // المالك: حقل للقراءة يفتح منتقياً؛ طبقة شفافة تلتقط اللمس لأن الحقل نفسه يستهلكه
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = ownerName,
                    onValueChange = {},
                    readOnly = true,
                    enabled = canEditOwner && !saving,
                    label = { Text(stringResource(R.string.user_detail_owner)) },
                    supportingText = { Text(stringResource(if (canEditOwner) R.string.user_edit_owner_hint else R.string.user_edit_owner_locked)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Dimens.RadiusMD)
                )
                if (canEditOwner && !saving) {
                    Box(modifier = Modifier.matchParentSize().clickable { showOwnerPicker = true })
                }
            }

            // حدود الوقت والبيانات والسرعة من الباقة: لا تُعدَّل لكل مستخدم
            Text(
                stringResource(R.string.user_edit_limits_from_package_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            state.editErrorResId?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = {
                    viewModel.editUser(
                        UserEditRequest(
                            recordUuid = base.recordUuid,
                            expectedUpdatedAt = base.updatedAt,
                            ownerId = ownerId
                        )
                    )
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.ButtonHeightLarge),
                shape = RoundedCornerShape(Dimens.RadiusMD)
            ) { Text(stringResource(R.string.user_edit_save)) }
        }
    }
}

@Composable
private fun InfoBanner(text: String, isError: Boolean, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(Dimens.RadiusMD),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(Dimens.SpaceMD),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)
        ) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun OwnerPickerDialog(owners: List<OwnerAccount>, onPick: (OwnerAccount) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.user_adopt_pick_owner_title), fontWeight = FontWeight.Bold) },
        text = {
            if (owners.isEmpty()) {
                Text(stringResource(R.string.user_adopt_no_owners))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                    items(owners, key = { it.ownerId }) { owner ->
                        TextButton(onClick = { onPick(owner) }, modifier = Modifier.fillMaxWidth()) {
                            Text(owner.name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

/** قيمة المدة المخزَّنة (ثوانٍ) → نص ووحدة للعرض. لا يُستعمل لإعادة الحساب ما لم يعدّل المدير الحقل. */

/** قيمة البيانات المخزَّنة (بايت) → نص ووحدة للعرض (ثنائية 1024). */

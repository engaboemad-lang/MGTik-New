@file:OptIn(ExperimentalMaterial3Api::class)

package ps.mgtik.hotspot.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.data.userman.UmStep
import ps.mgtik.hotspot.data.userman.UserManPlanner
import ps.mgtik.hotspot.domain.model.UserManConfig
import ps.mgtik.hotspot.domain.model.UserManMode
import ps.mgtik.hotspot.domain.usecase.UserManConfigValidator
import ps.mgtik.hotspot.domain.usecase.UserManField
import ps.mgtik.hotspot.domain.usecase.UserManFieldError
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import ps.mgtik.hotspot.ui.components.userman.UmCard
import ps.mgtik.hotspot.ui.components.userman.UmCardTitle
import ps.mgtik.hotspot.ui.components.userman.UserManChecklist
import ps.mgtik.hotspot.ui.components.userman.UserManProvisionResult
import ps.mgtik.hotspot.ui.components.userman.UserManStatusSummary
import ps.mgtik.hotspot.ui.components.userman.umStepLabelRes
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.viewmodel.UserManViewModel

/**
 * الشاشة 32: إعداد واتصال User Manager.
 *
 * ثلاثة أقسام: (1) الحالة الحية من الراوتر (الجيل v6/v7، الجاهزية)، (2) إعداد الاتصال المحفوظ (الجيل، العميل لـ v6،
 * عنوان وسر RADIUS، اسم NAS لـ v7)، (3) فحوص الجاهزية وزر «تهيئة الراوتر» الذي ينفّذ الإصلاحات بعد تأكيد صريح.
 * الحقول مستقلة عن حالة الاتصال: تُعدَّل وتُحفظ حتى والراوتر منقطع؛ الفحص والتهيئة وحدهما يحتاجان اتصالاً.
 *
 * @param onOpenRouterSetup لفتح شاشة اتصال الراوتر نفسه حين لا يكون الراوتر متاحاً.
 */
@Composable
fun S32_UserManSetupScreen(
    viewModel: UserManViewModel,
    onBack: () -> Unit,
    onOpenRouterSetup: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val router by viewModel.routerStatus.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // المسودة تنجو من تدوير الشاشة؛ يُملأ المحفوظ عند وصوله فقط إن لم يبدأ المستخدم بالكتابة
    var touched by rememberSaveable { mutableStateOf(false) }
    var modeName by rememberSaveable { mutableStateOf(UserManMode.AUTO.name) }
    var customer by rememberSaveable { mutableStateOf(UserManConfig.DEFAULT_CUSTOMER) }
    var address by rememberSaveable { mutableStateOf(UserManConfig.DEFAULT_RADIUS_ADDRESS) }
    var secret by remember { mutableStateOf("") } // سر: لا يدخل Bundle الحالة المحفوظة (يضيع عند تدوير الشاشة عمداً)
    var nasName by rememberSaveable { mutableStateOf(UserManConfig.DEFAULT_NAS_NAME) }
    var showSecret by rememberSaveable { mutableStateOf(false) }
    var confirmProvision by remember { mutableStateOf(false) }

    LaunchedEffect(state.configLoaded, state.saved) {
        if (state.configLoaded && !touched) {
            modeName = state.saved.mode.name
            customer = state.saved.customer
            address = state.saved.radiusAddress
            secret = state.saved.radiusSecret
            nasName = state.saved.nasName
        }
    }
    LaunchedEffect(router.isConnected) { if (router.isConnected && state.status == null) viewModel.refresh() }

    val mode = UserManMode.valueOf(modeName)
    fun draft() = UserManConfig(mode = mode, customer = customer, radiusAddress = address, radiusSecret = secret, nasName = nasName)

    val messageRes = state.successMessageResId ?: state.errorMessageResId
    val messageText = messageRes?.let { stringResource(it) }
    LaunchedEffect(messageRes) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MgtikTopAppBar(
                title = { Text(stringResource(R.string.um_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(Dimens.ScreenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing)
        ) {
            if (state.isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            // ── 1) الحالة ──
            UmCard {
                UmCardTitle(stringResource(R.string.um_section_status))
                val status = state.status
                when {
                    status != null -> UserManStatusSummary(status, state.statusCheckedAtMillis)
                    state.statusErrorRes != null -> Text(stringResource(state.statusErrorRes!!), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    !router.isConnected -> Text(stringResource(R.string.um_router_required), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (status != null) state.statusErrorRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!router.isConnected) {
                    OutlinedButton(onClick = onOpenRouterSetup, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Router, contentDescription = null)
                        Spacer(Modifier.width(Dimens.SpaceSM))
                        Text(stringResource(R.string.settings_router_open_setup))
                    }
                }
                OutlinedButton(onClick = { viewModel.refresh() }, enabled = router.isConnected && !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(Dimens.SpaceSM))
                    Text(stringResource(R.string.um_check_now))
                }
                TextButton(onClick = { viewModel.refresh(redetect = true) }, enabled = router.isConnected && !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.um_redetect))
                }
            }

            // ── 2) إعداد الاتصال ──
            UmCard {
                UmCardTitle(stringResource(R.string.um_section_config))
                Text(stringResource(R.string.um_mode_label), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                    ModeChip(R.string.um_mode_auto, mode == UserManMode.AUTO) { touched = true; modeName = UserManMode.AUTO.name }
                    ModeChip(R.string.um_mode_v6, mode == UserManMode.V6) { touched = true; modeName = UserManMode.V6.name }
                    ModeChip(R.string.um_mode_v7, mode == UserManMode.V7) { touched = true; modeName = UserManMode.V7.name }
                }
                if (mode != UserManMode.V7) {
                    UmField(R.string.um_field_customer, customer, state.fieldErrors[UserManField.CUSTOMER]) {
                        touched = true; customer = it; viewModel.clearFieldError(UserManField.CUSTOMER)
                    }
                }
                if (mode != UserManMode.V6) {
                    UmField(R.string.um_field_nas_name, nasName, state.fieldErrors[UserManField.NAS_NAME]) {
                        touched = true; nasName = it; viewModel.clearFieldError(UserManField.NAS_NAME)
                    }
                }
                UmField(R.string.um_field_radius_address, address, state.fieldErrors[UserManField.RADIUS_ADDRESS], KeyboardType.Uri) {
                    touched = true; address = it; viewModel.clearFieldError(UserManField.RADIUS_ADDRESS)
                }
                UmField(
                    R.string.um_field_radius_secret, secret, state.fieldErrors[UserManField.RADIUS_SECRET], KeyboardType.Password,
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        IconButton(onClick = { showSecret = !showSecret }) {
                            Icon(if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                        }
                    }
                ) { touched = true; secret = it; viewModel.clearFieldError(UserManField.RADIUS_SECRET) }
                TextButton(onClick = { touched = true; secret = UserManConfigValidator.generateSecret(); showSecret = true; viewModel.clearFieldError(UserManField.RADIUS_SECRET) }) {
                    Icon(Icons.Default.Autorenew, contentDescription = null)
                    Spacer(Modifier.width(Dimens.SpaceSM))
                    Text(stringResource(R.string.um_generate_secret))
                }
                Button(onClick = { viewModel.save(draft()) }, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(Dimens.SpaceSM))
                    Text(stringResource(R.string.um_save))
                }
                Text(stringResource(R.string.um_config_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ── 3) الجاهزية والتهيئة ──
            UmCard {
                UmCardTitle(stringResource(R.string.um_section_checks))
                val status = state.status
                if (status == null) {
                    Text(stringResource(R.string.um_no_check_yet), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    UserManChecklist(status.checks)
                }
                // تشخيص القراءات: يسمّي القراءة الفاشلة بدل رسالة عامة، مع إعادة الفحص
                UmCardTitle(stringResource(R.string.um_read_title))
                state.readChecks?.forEach { c ->
                    val name = stringResource(
                        when (c.kind) {
                            ps.mgtik.hotspot.data.userman.UmReadKind.PACKAGES -> R.string.um_read_packages
                            ps.mgtik.hotspot.data.userman.UmReadKind.LIMITATIONS -> R.string.um_read_limitations
                            ps.mgtik.hotspot.data.userman.UmReadKind.USERS -> R.string.um_read_users
                            ps.mgtik.hotspot.data.userman.UmReadKind.SESSIONS -> R.string.um_read_sessions
                        }
                    )
                    Text(
                        if (c.ok) stringResource(R.string.um_read_ok_fmt, name)
                        else stringResource(R.string.um_read_failed_fmt, name, stringResource(ps.mgtik.hotspot.ui.util.RouterErrorTexts.reasonRes(c.errorCode))),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (c.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )
                }
                OutlinedButton(
                    onClick = viewModel::checkReads,
                    enabled = router.isConnected && !state.isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(if (state.readChecks == null) R.string.um_read_check else R.string.um_read_recheck))
                }
                Button(onClick = { confirmProvision = true }, enabled = router.isConnected && !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Build, contentDescription = null)
                    Spacer(Modifier.width(Dimens.SpaceSM))
                    Text(stringResource(R.string.um_provision))
                }
                Text(stringResource(R.string.um_provision_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.provisionSteps.isNotEmpty()) {
                    UmCardTitle(stringResource(R.string.um_section_results))
                    UserManProvisionResult(state.provisionSteps)
                }
            }
        }
    }

    if (confirmProvision) {
        // قبل أول فحص كل الخطوات محتملة (كلها idempotent)؛ بعده الناقص فعلاً فقط
        val planned: List<UmStep> = state.status?.let { UserManPlanner.stepsFor(it.checks).toList() } ?: UmStep.values().toList()
        AlertDialog(
            onDismissRequest = { confirmProvision = false },
            title = { Text(stringResource(R.string.um_provision), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                    Text(stringResource(R.string.um_provision_confirm_body))
                    if (planned.isEmpty()) Text(stringResource(R.string.um_provision_nothing), fontWeight = FontWeight.SemiBold)
                    planned.forEach { Text("• " + stringResource(umStepLabelRes(it)), style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = {
                Button(onClick = { confirmProvision = false; viewModel.provision(draft()) }) { Text(stringResource(R.string.um_confirm_run)) }
            },
            dismissButton = { TextButton(onClick = { confirmProvision = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

@Composable
private fun ModeChip(@StringRes labelRes: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(stringResource(labelRes)) })
}

/** حقل تقني: نصه LTR داخل واجهة RTL، وخطؤه مترجم من [UserManFieldError]. */
@Composable
private fun UmField(
    @StringRes labelRes: Int,
    value: String,
    error: UserManFieldError?,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(labelRes)) },
        isError = error != null,
        supportingText = { error?.let { Text(stringResource(umFieldErrorRes(it))) } },
        singleLine = true,
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = androidx.compose.material3.LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
        shape = RoundedCornerShape(Dimens.RadiusMD),
        modifier = Modifier.fillMaxWidth()
    )
}

@StringRes
private fun umFieldErrorRes(e: UserManFieldError): Int = when (e) {
    UserManFieldError.REQUIRED -> R.string.um_err_required
    UserManFieldError.INVALID -> R.string.um_err_invalid
    UserManFieldError.TOO_SHORT -> R.string.um_err_too_short
    UserManFieldError.TOO_LONG -> R.string.um_err_too_long
}

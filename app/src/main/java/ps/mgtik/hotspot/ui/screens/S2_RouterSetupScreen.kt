package ps.mgtik.hotspot.ui.screens

import androidx.compose.foundation.BorderStroke
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.HelpCenter
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.core.network.RouterConfig
import ps.mgtik.hotspot.core.network.RouterPorts
import ps.mgtik.hotspot.core.network.RouterConnectionType
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.ConfigLoadState
import ps.mgtik.hotspot.domain.usecase.RouterInputValidator
import ps.mgtik.hotspot.ui.components.indicators.MgtikButtonLoadingContent
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.theme.LocalMgtikExtendedColors
import ps.mgtik.hotspot.ui.viewmodel.RouterSetupEvent
import ps.mgtik.hotspot.ui.viewmodel.RouterViewModel
import ps.mgtik.hotspot.ui.viewmodel.TestStepStatus

/**
 * S2_RouterSetupScreen - ضبط اتصال راوتر MikroTik عبر REST (RouterOS 7.1+) أو API (RouterOS 6.x و7.x) (§2).
 *
 * - الحقول التقنية (عنوان/منفذ/مستخدم/كلمة مرور) نصها LTR داخل واجهة RTL.
 * - الحقول مستقلة عن حالة الاتصال وعن تحميل الإعداد المحفوظ: تُفتح قابلة للتعديل فوراً (حتى والراوتر منقطع أو السحابة بطيئة)،
 *   ويُملأ الإعداد المحفوظ عند وصوله فقط إن لم يبدأ المستخدم بالكتابة.
 * - الحفظ لا يشترط فحصاً ناجحاً: يحفظ ثم يختبر الاتصال بالإعداد الجديد، ونجاح الحفظ لا يُعلَن اتصالاً.
 * - كل النصوص من strings.xml (ar/en).
 *
 * @param canNavigateBack false عند الوصول للشاشة كأول وجهة (راوتر غير مُهيّأ) فلا يوجد ما نعود إليه.
 * @param onSaved يُستدعى بعد نجاح الحفظ؛ الملاحة (رجوع أو تقدّم للرئيسية) قرار المخطط لا الشاشة.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S2_RouterSetupScreen(
    viewModel: RouterViewModel,
    canNavigateBack: Boolean,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    /** فشل فحص الإقلاع: يُعرض سببه مع إعادة المحاولة و«متابعة دون اتصال» (قرار صريح). */
    showStartupFailure: Boolean = false,
    startupFailureCode: String? = null
) {
    val state by viewModel.uiState.collectAsState()
    val extendedColors = LocalMgtikExtendedColors.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val currentOnSaved by rememberUpdatedState(onSaved)

    // مسودة الحقول: تنجو من تدوير الشاشة، وتبدأ بقيم افتراضية فوراً (لا انتظار لأي تحميل)
    val defaults = remember { RouterConfig() }
    var savedApplied by rememberSaveable { mutableStateOf(false) }
    var draftTouched by rememberSaveable { mutableStateOf(false) }
    var host by rememberSaveable { mutableStateOf(defaults.host) }
    var port by rememberSaveable { mutableStateOf(defaults.port.toString()) }
    var apiPort by rememberSaveable { mutableStateOf(defaults.apiPort.toString()) }
    var connectionTypeName by rememberSaveable { mutableStateOf(RouterConnectionType.AUTO.name) }
    val connectionType = RouterConnectionType.valueOf(connectionTypeName)
    var username by rememberSaveable { mutableStateOf(defaults.username) }
    var password by remember { mutableStateOf("") } // سر: لا يدخل Bundle الحالة المحفوظة (يضيع عند تدوير الشاشة عمداً)
    var useSsl by rememberSaveable { mutableStateOf(false) }
    var isPasswordVisible by rememberSaveable { mutableStateOf(false) }

    var hostError by remember { mutableStateOf<String?>(null) }
    var portError by remember { mutableStateOf<String?>(null) }
    var apiPortError by remember { mutableStateOf<String?>(null) }
    var usernameError by remember { mutableStateOf<String?>(null) }

    val msgInvalidHost = stringResource(R.string.router_error_invalid_host)
    val msgInvalidPort = stringResource(R.string.router_error_invalid_port)
    val msgInvalidApiPort = stringResource(R.string.router_error_invalid_api_port)
    val msgInvalidUsername = stringResource(R.string.router_error_invalid_username)

    LaunchedEffect(Unit) { viewModel.onScreenEntered() }

    // الإعداد المحفوظ يصل في أي وقت (أو لا يصل): يُطبَّق مرة واحدة فقط وبشرط ألا يكون المستخدم قد بدأ التعديل
    LaunchedEffect(state.configLoad, state.savedConfig) {
        val saved = state.savedConfig
        if (!draftTouched && saved.host.isNotBlank()) {
            host = saved.host
            port = saved.port.toString()
            apiPort = saved.apiPort.toString()
            connectionTypeName = saved.connectionType.name
            username = saved.username
            password = saved.password
            useSsl = saved.useSsl
            savedApplied = true
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                RouterSetupEvent.Saved -> currentOnSaved()
                is RouterSetupEvent.SaveFailed ->
                    snackbarHostState.showSnackbar(context.getString(event.messageRes))
                is RouterSetupEvent.SavedNotConnected ->
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.router_saved_not_connected_fmt, context.getString(event.messageRes))
                    )
                RouterSetupEvent.SkippedConnection -> currentOnSaved()
            }
        }
    }

    if (state.pendingUntestedConfig != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelUntestedSave,
            text = { Text(stringResource(R.string.router_untested_save_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmSaveAnyway) {
                    Text(stringResource(R.string.router_untested_save_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelUntestedSave) {
                    Text(stringResource(R.string.router_untested_save_cancel))
                }
            }
        )
    }

    val formEnabled = !state.isTesting && !state.isSaving
    val techTextStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)

    fun onDraftEdited() {
        draftTouched = true
        viewModel.onDraftChanged()
    }

    fun onSslChanged(checked: Boolean) {
        port = RouterInputValidator.portAfterSslToggle(port, checked)
        apiPort = RouterInputValidator.apiPortAfterSslToggle(apiPort, checked)
        useSsl = checked
        portError = null
        apiPortError = null
        onDraftEdited()
    }

    /** يتحقق من الحقول ويبني الإعداد؛ null (مع إظهار أخطاء الحقول) إن كانت المدخلات غير صالحة. */
    fun validatedConfig(): RouterConfig? {
        val parsedPort = RouterInputValidator.parsePort(port)
        val parsedApiPort = RouterInputValidator.parsePort(apiPort)
        val hostOk = RouterInputValidator.isHostValid(host)
        val userOk = username.isNotBlank()
        // منفذ REST لا يُتحقَّق منه في نمط API وحده، ومنفذ API لا يُتحقَّق منه في نمط REST وحده
        val restPortOk = connectionType == RouterConnectionType.API || parsedPort != null
        val apiPortOk = connectionType == RouterConnectionType.REST || parsedApiPort != null
        hostError = if (hostOk) null else msgInvalidHost
        portError = if (restPortOk) null else msgInvalidPort
        apiPortError = if (apiPortOk) null else msgInvalidApiPort
        usernameError = if (userOk) null else msgInvalidUsername
        if (!hostOk || !restPortOk || !apiPortOk || !userOk) return null

        focusManager.clearFocus()
        return RouterConfig(
            host = host.trim(),
            port = parsedPort ?: RouterPorts.defaultFor(useSsl),
            username = username.trim(),
            password = password,
            useSsl = useSsl,
            apiPort = parsedApiPort ?: RouterPorts.apiDefaultFor(useSsl),
            connectionType = connectionType
        )
    }

    fun submitTest() { validatedConfig()?.let(viewModel::testConnection) }

    fun submitSave() { validatedConfig()?.let(viewModel::save) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MgtikTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.router_setup_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (canNavigateBack) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(Dimens.ScreenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)
        ) {
            // تحميل الإعداد المحفوظ لا يمنع الإدخال: مؤشر خفيف + ملاحظة، وله مهلة تنتهي في المدير المركزي
            when (state.configLoad) {
                ConfigLoadState.LOADING -> Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.router_config_loading_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ConfigLoadState.UNAVAILABLE -> Text(
                    text = stringResource(R.string.router_config_unavailable_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                ConfigLoadState.LOADED -> Unit
            }

            if (showStartupFailure) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(Dimens.RadiusMD)
                ) {
                    Column(
                        modifier = Modifier.padding(Dimens.SpaceLG),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)
                    ) {
                        Text(
                            text = stringResource(R.string.startup_router_failed_title),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = stringResource(ps.mgtik.hotspot.ui.util.RouterErrorTexts.reasonRes(startupFailureCode)),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                            OutlinedButton(
                                onClick = viewModel::retryConnection,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(Dimens.RadiusMD)
                            ) { Text(stringResource(R.string.router_action_retry)) }
                            OutlinedButton(
                                onClick = viewModel::skipConnection,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(Dimens.RadiusMD)
                            ) { Text(stringResource(R.string.router_action_skip)) }
                        }
                    }
                }
            }

            // بطاقة المتطلبات
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(Dimens.RadiusMD)
            ) {
                Row(modifier = Modifier.padding(Dimens.SpaceLG), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.HelpCenter, null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(modifier = Modifier.width(Dimens.SpaceMD))
                    Column {
                        Text(
                            text = stringResource(R.string.router_setup_requirements_title),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = stringResource(R.string.router_setup_requirements_content),
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            // نموذج الاتصال
            Card(
                shape = RoundedCornerShape(Dimens.RadiusLG),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(Dimens.SpaceLG),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)
                ) {
                    Text(
                        text = stringResource(R.string.router_setup_section_api),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium
                    )

                    // نسخة RouterOS: تلقائي (REST ثم API) / v7 (REST) / v6 (API)
                    Text(
                        text = stringResource(R.string.router_conn_type_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                        listOf(
                            RouterConnectionType.AUTO to R.string.router_conn_type_auto,
                            RouterConnectionType.REST to R.string.router_conn_type_rest,
                            RouterConnectionType.API to R.string.router_conn_type_api
                        ).forEach { (type, labelRes) ->
                            FilterChip(
                                selected = connectionType == type,
                                onClick = {
                                    connectionTypeName = type.name
                                    portError = null
                                    apiPortError = null
                                    onDraftEdited()
                                },
                                enabled = formEnabled,
                                label = { Text(stringResource(labelRes)) }
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.router_conn_type_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = host,
                        onValueChange = {
                            host = it.filterNot(Char::isWhitespace)
                            hostError = null
                            onDraftEdited()
                        },
                        label = { Text(stringResource(R.string.router_field_host)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = formEnabled,
                        isError = hostError != null,
                        supportingText = { hostError?.let { Text(it) } },
                        textStyle = techTextStyle,
                        shape = RoundedCornerShape(Dimens.RadiusMD),
                        leadingIcon = { Icon(Icons.Rounded.Dns, null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                    )

                    if (connectionType != RouterConnectionType.API) OutlinedTextField(
                        value = port,
                        onValueChange = {
                            port = it.filter(Char::isDigit).take(5)
                            portError = null
                            onDraftEdited()
                        },
                        label = { Text(stringResource(R.string.router_field_port)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = formEnabled,
                        isError = portError != null,
                        supportingText = {
                            val error = portError
                            if (error != null) {
                                Text(error)
                            } else if (connectionType == RouterConnectionType.REST && RouterInputValidator.isBinaryApiPort(port.toIntOrNull())) {
                                // تنبيه غير مانع: 8728/8729 منفذا API الثنائي وليسا REST
                                Text(stringResource(R.string.router_port_binary_api_hint))
                            }
                        },
                        textStyle = techTextStyle,
                        shape = RoundedCornerShape(Dimens.RadiusMD),
                        leadingIcon = { Icon(Icons.Rounded.Pin, null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                    )

                    if (connectionType != RouterConnectionType.REST) OutlinedTextField(
                        value = apiPort,
                        onValueChange = {
                            apiPort = it.filter(Char::isDigit).take(5)
                            apiPortError = null
                            onDraftEdited()
                        },
                        label = { Text(stringResource(R.string.router_field_api_port)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = formEnabled,
                        isError = apiPortError != null,
                        supportingText = {
                            val error = apiPortError
                            if (error != null) {
                                Text(error)
                            } else if (useSsl) {
                                Text(stringResource(R.string.router_api_ssl_hint))
                            }
                        },
                        textStyle = techTextStyle,
                        shape = RoundedCornerShape(Dimens.RadiusMD),
                        leadingIcon = { Icon(Icons.Rounded.Pin, null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                    )

                    OutlinedTextField(
                        value = username,
                        onValueChange = {
                            username = it
                            usernameError = null
                            onDraftEdited()
                        },
                        label = { Text(stringResource(R.string.router_field_username)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = formEnabled,
                        isError = usernameError != null,
                        supportingText = { usernameError?.let { Text(it) } },
                        textStyle = techTextStyle,
                        shape = RoundedCornerShape(Dimens.RadiusMD),
                        leadingIcon = { Icon(Icons.Rounded.Person, null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            onDraftEdited()
                        },
                        label = { Text(stringResource(R.string.router_field_password)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = formEnabled,
                        textStyle = techTextStyle,
                        shape = RoundedCornerShape(Dimens.RadiusMD),
                        leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Icon(
                                    imageVector = if (isPasswordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = stringResource(
                                        if (isPasswordVisible) R.string.login_hide_password else R.string.login_show_password
                                    )
                                )
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                    )

                    // صف SSL: الصف كله قابل للنقر ومُعرَّف كمفتاح تبديل لقارئات الشاشة
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = useSsl,
                                enabled = formEnabled,
                                role = Role.Switch,
                                onValueChange = ::onSslChanged
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(stringResource(R.string.router_field_ssl), fontWeight = FontWeight.SemiBold)
                            Text(
                                text = stringResource(
                                    if (useSsl) R.string.router_ssl_secure else R.string.router_ssl_unencrypted
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (useSsl) extendedColors.success else MaterialTheme.colorScheme.error
                            )
                        }
                        Switch(checked = useSsl, onCheckedChange = null, enabled = formEnabled)
                    }
                }
            }

            // خطوات الفحص ونتيجته
            if (state.isTesting || state.testResult != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(Dimens.RadiusMD)
                ) {
                    Column(
                        modifier = Modifier.padding(Dimens.SpaceLG),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)
                    ) {
                        state.testSteps.forEach { step ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                when (step.status) {
                                    TestStepStatus.LOADING -> CircularProgressIndicator(
                                        modifier = Modifier.size(Dimens.IconSizeSmall),
                                        strokeWidth = 2.dp
                                    )
                                    TestStepStatus.SUCCESS -> Icon(
                                        Icons.Rounded.CheckCircle, null,
                                        tint = extendedColors.success,
                                        modifier = Modifier.size(Dimens.IconSizeMedium)
                                    )
                                    TestStepStatus.ERROR -> Icon(
                                        Icons.Rounded.Error, null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(Dimens.IconSizeMedium)
                                    )
                                    TestStepStatus.IDLE -> Icon(
                                        Icons.Rounded.Circle, null,
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(Dimens.IconSizeSmall)
                                    )
                                }
                                Spacer(modifier = Modifier.width(Dimens.SpaceMD))
                                Text(stringResource(step.titleRes), style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        state.testResult?.let { result ->
                            val isSuccess = result is AppResult.Success
                            val color = if (isSuccess) extendedColors.success else MaterialTheme.colorScheme.error
                            HorizontalDivider(modifier = Modifier.padding(vertical = Dimens.SpaceXS))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (isSuccess) Icons.Rounded.Verified else Icons.Rounded.Report, null, tint = color)
                                Spacer(modifier = Modifier.width(Dimens.SpaceSM))
                                Column {
                                    Text(
                                        text = stringResource(
                                            if (isSuccess) R.string.router_test_success
                                            else state.testErrorRes ?: R.string.router_test_failed_generic
                                        ),
                                        fontWeight = FontWeight.Bold,
                                        color = color,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    // تلميح علاج عملي للسبب المعروف؛ زر «اختبار الاتصال» أعلاه يعيد الاختبار
                                    state.testHintRes?.takeIf { !isSuccess }?.let { hint ->
                                        Text(
                                            text = stringResource(hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (result is AppResult.Success) {
                                        Text(
                                            text = "${result.data.name} • ${result.data.osMajor}",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)
            ) {
                OutlinedButton(
                    onClick = ::submitTest,
                    enabled = formEnabled,
                    modifier = Modifier.weight(1f).height(Dimens.ButtonHeightLarge),
                    shape = RoundedCornerShape(Dimens.RadiusMD)
                ) {
                    if (state.isTesting) {
                        MgtikButtonLoadingContent(
                            text = stringResource(R.string.router_test_loading),
                            indicatorColor = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(Icons.Rounded.WifiTethering, null, modifier = Modifier.size(Dimens.IconSizeMedium))
                        Spacer(modifier = Modifier.width(Dimens.SpaceSM))
                        Text(stringResource(R.string.router_action_test))
                    }
                }

                Button(
                    onClick = ::submitSave,
                    enabled = state.canSave,
                    modifier = Modifier.weight(1f).height(Dimens.ButtonHeightLarge),
                    shape = RoundedCornerShape(Dimens.RadiusMD)
                ) {
                    if (state.isSaving) {
                        MgtikButtonLoadingContent(text = stringResource(R.string.router_save_loading))
                    } else {
                        Icon(Icons.Rounded.Save, null, modifier = Modifier.size(Dimens.IconSizeMedium))
                        Spacer(modifier = Modifier.width(Dimens.SpaceSM))
                        Text(stringResource(R.string.router_action_save))
                    }
                }
            }

            // حُفظ الإعداد لكن الاتصال فشل: القرار للمستخدم (لا تكرار تلقائي ولا تقدّم صامت)
            if (state.savedButNotConnected) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(Dimens.RadiusMD)
                ) {
                    Column(
                        modifier = Modifier.padding(Dimens.SpaceLG),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)
                    ) {
                        Text(
                            text = stringResource(R.string.router_saved_not_connected_title),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = stringResource(R.string.router_saved_not_connected_body),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                            OutlinedButton(
                                onClick = { validatedConfig()?.let(viewModel::retrySavedConnection) },
                                enabled = state.canSave,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(Dimens.RadiusMD)
                            ) { Text(stringResource(R.string.router_action_retry)) }
                            OutlinedButton(
                                onClick = viewModel::skipConnection,
                                enabled = state.canSave,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(Dimens.RadiusMD)
                            ) { Text(stringResource(R.string.router_action_skip)) }
                        }
                    }
                }
            }
        }
    }
}

@file:OptIn(ExperimentalMaterial3Api::class)

package ps.mgtik.hotspot.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.data.local.prefs.AppLanguage
import ps.mgtik.hotspot.data.local.prefs.AppPreferences
import ps.mgtik.hotspot.data.local.prefs.ThemeMode
import ps.mgtik.hotspot.domain.export.LayoutPlanner
import ps.mgtik.hotspot.domain.model.ConfigLoadState
import ps.mgtik.hotspot.domain.model.FillDirection
import ps.mgtik.hotspot.domain.model.Margins
import ps.mgtik.hotspot.domain.model.NetworkCompanyInfo
import ps.mgtik.hotspot.domain.model.PageSize
import ps.mgtik.hotspot.domain.model.PrintOrientation
import ps.mgtik.hotspot.domain.model.RouterConnectionState
import ps.mgtik.hotspot.domain.usecase.CompanyInfoValidator
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.theme.LocalMgtikExtendedColors
import ps.mgtik.hotspot.ui.util.RouterErrorTexts
import ps.mgtik.hotspot.ui.viewmodel.SettingsUiState
import ps.mgtik.hotspot.ui.viewmodel.SettingsViewModel
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * الشاشة 24: مركز الإعدادات. شاشة رئيسية تقود إلى أقسام مستقلة؛ كل قسم يعرض قيمه الحالية ويحفظها في مصدرها:
 * تفضيلات الجهاز في AppPreferences، وبيانات الشركة في Firestore، وحالة الراوتر من المنظومة المركزية (قراءة فقط).
 */
private enum class SettingsSection { GENERAL, COMPANY, CONNECTION, PRINTING, NOTIFICATIONS, DATA, ACCOUNT, ABOUT }

@Composable
fun S24_SettingsScreen(
    viewModel: SettingsViewModel,
    userManViewModel: ps.mgtik.hotspot.ui.viewmodel.UserManViewModel,
    onNavigateToRouterSetup: () -> Unit,
    onOpenUserManSetup: () -> Unit,
    onOpenOwners: () -> Unit,
    onOpenOrders: () -> Unit,
    onOpenInvoices: () -> Unit,
    onOpenUsers: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenArchive: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenQueue: () -> Unit,
    authState: ps.mgtik.hotspot.ui.viewmodel.AuthUiState,
    onChangePassword: (String, String) -> Unit,
    onPasswordDialogClosed: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var sectionName by rememberSaveable { mutableStateOf<String?>(null) }
    val section = sectionName?.let { n -> SettingsSection.values().firstOrNull { it.name == n } }
    BackHandler(enabled = section != null) { sectionName = null }

    var showChangePassword by remember { mutableStateOf(false) }
    if (showChangePassword) {
        ChangePasswordDialog(
            state = authState,
            onSubmit = onChangePassword,
            onClose = { showChangePassword = false; onPasswordDialogClosed() }
        )
    }
    val messageResId = state.successMessageResId ?: state.errorMessageResId
    val messageText = messageResId?.let { stringResource(it) }
    LaunchedEffect(messageResId) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MgtikTopAppBar(
                title = { Text(stringResource(section?.titleRes() ?: R.string.settings_title)) },
                navigationIcon = {
                    if (section != null) {
                        IconButton(onClick = { sectionName = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
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
                .verticalScroll(rememberScrollState())
                .padding(Dimens.ScreenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing)
        ) {
            when (section) {
                null -> SettingsHub(
                    onSection = { sectionName = it.name },
                    onOpenOwners, onOpenOrders, onOpenInvoices, onOpenUsers, onOpenProfiles,
                    onOpenHistory, onOpenArchive, onOpenTemplates, onOpenQueue
                ).also {
                    // نهاية الصفحة بعد كل الأقسام: الأزرار الثلاثة المستقلة (خروج سحابي / خروج من الراوتر / إغلاق التطبيق)
                    ps.mgtik.hotspot.ui.components.session.LocalSessionActions.current?.let { host ->
                        SettingsCard { ps.mgtik.hotspot.ui.components.session.SessionActionButtons(host, Modifier.padding(Dimens.SpaceLG)) }
                    }
                }
                SettingsSection.GENERAL -> GeneralSection(viewModel)
                SettingsSection.COMPANY -> CompanySection(viewModel, state)
                SettingsSection.CONNECTION -> ConnectionSection(viewModel, state, onNavigateToRouterSetup, onOpenUserManSetup)
                SettingsSection.PRINTING -> PrintingSection(viewModel)
                SettingsSection.NOTIFICATIONS -> NotificationsSection(viewModel)
                SettingsSection.DATA -> DataSection(viewModel, state, userManViewModel, onOpenUserManSetup)
                SettingsSection.ACCOUNT -> SettingsCard {
                    SettingsLinkRow(Icons.Default.Lock, stringResource(R.string.password_change_title)) { showChangePassword = true }
                }
                SettingsSection.ABOUT -> AboutSection(viewModel, state)
            }
        }
    }
}

private fun SettingsSection.titleRes(): Int = when (this) {
    SettingsSection.GENERAL -> R.string.settings_sec_general
    SettingsSection.COMPANY -> R.string.settings_sec_company
    SettingsSection.CONNECTION -> R.string.settings_sec_connection
    SettingsSection.PRINTING -> R.string.settings_sec_printing
    SettingsSection.NOTIFICATIONS -> R.string.settings_sec_notifications
    SettingsSection.DATA -> R.string.settings_sec_data
    SettingsSection.ACCOUNT -> R.string.settings_sec_account
    SettingsSection.ABOUT -> R.string.settings_sec_about
}

// ───────────────────────────── الرئيسية ─────────────────────────────

@Composable
private fun SettingsHub(
    onSection: (SettingsSection) -> Unit,
    onOpenOwners: () -> Unit,
    onOpenOrders: () -> Unit,
    onOpenInvoices: () -> Unit,
    onOpenUsers: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenArchive: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenQueue: () -> Unit
) {
    SettingsCard {
        HubRow(Icons.Default.Language, R.string.settings_sec_general, R.string.settings_sec_general_desc) { onSection(SettingsSection.GENERAL) }
        HubRow(Icons.Default.Business, R.string.settings_sec_company, R.string.settings_sec_company_desc) { onSection(SettingsSection.COMPANY) }
        HubRow(Icons.Default.Router, R.string.settings_sec_connection, R.string.settings_sec_connection_desc) { onSection(SettingsSection.CONNECTION) }
        HubRow(Icons.Default.Print, R.string.settings_sec_printing, R.string.settings_sec_printing_desc) { onSection(SettingsSection.PRINTING) }
        HubRow(Icons.Default.Notifications, R.string.settings_sec_notifications, R.string.settings_sec_notifications_desc) { onSection(SettingsSection.NOTIFICATIONS) }
        HubRow(Icons.Default.Storage, R.string.settings_sec_data, R.string.settings_sec_data_desc) { onSection(SettingsSection.DATA) }
        HubRow(Icons.Default.ManageAccounts, R.string.settings_sec_account, R.string.settings_sec_account_desc) { onSection(SettingsSection.ACCOUNT) }
        HubRow(Icons.Default.Info, R.string.settings_sec_about, R.string.settings_sec_about_desc) { onSection(SettingsSection.ABOUT) }
    }
    // اختصارات الإدارة: شاشات التطبيق التي لا نقطة دخول لها إلا من هنا
    SettingsCard {
        Text(
            stringResource(R.string.settings_section_manage),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = Dimens.SpaceLG, vertical = Dimens.SpaceSM)
        )
        SettingsLinkRow(Icons.Default.People, stringResource(R.string.hub_owners), onOpenOwners)
        SettingsLinkRow(Icons.Default.Receipt, stringResource(R.string.hub_orders), onOpenOrders)
        SettingsLinkRow(Icons.Default.RequestQuote, stringResource(R.string.hub_invoices), onOpenInvoices)
        SettingsLinkRow(Icons.Default.ManageAccounts, stringResource(R.string.hub_users), onOpenUsers)
        SettingsLinkRow(Icons.Default.Speed, stringResource(R.string.hub_profiles), onOpenProfiles)
        SettingsLinkRow(Icons.Default.HistoryEdu, stringResource(R.string.history_title), onOpenHistory)
        SettingsLinkRow(Icons.Default.Archive, stringResource(R.string.archive_users_title), onOpenArchive)
        SettingsLinkRow(Icons.Default.ViewQuilt, stringResource(R.string.hub_templates), onOpenTemplates)
        SettingsLinkRow(Icons.Default.Sync, stringResource(R.string.queue_title), onOpenQueue)
    }
}

// ───────────────────────────── عام ─────────────────────────────

@Composable
private fun GeneralSection(viewModel: SettingsViewModel) {
    val theme by viewModel.themeMode.collectAsState()
    val language by viewModel.language.collectAsState()
    val activity = LocalContext.current as? android.app.Activity
    var confirmReset by remember { mutableStateOf(false) }

    SettingsCard {
        GroupTitle(stringResource(R.string.settings_language))
        ChoiceRow(stringResource(R.string.settings_language_system), language == AppLanguage.SYSTEM) { viewModel.setLanguage(AppLanguage.SYSTEM); activity?.recreate() }
        ChoiceRow(stringResource(R.string.settings_language_ar), language == AppLanguage.AR) { viewModel.setLanguage(AppLanguage.AR); activity?.recreate() }
        ChoiceRow(stringResource(R.string.settings_language_en), language == AppLanguage.EN) { viewModel.setLanguage(AppLanguage.EN); activity?.recreate() }
    }
    SettingsCard {
        GroupTitle(stringResource(R.string.settings_theme))
        ChoiceRow(stringResource(R.string.settings_theme_system), theme == ThemeMode.SYSTEM) { viewModel.setThemeMode(ThemeMode.SYSTEM) }
        ChoiceRow(stringResource(R.string.settings_theme_light), theme == ThemeMode.LIGHT) { viewModel.setThemeMode(ThemeMode.LIGHT) }
        ChoiceRow(stringResource(R.string.settings_theme_dark), theme == ThemeMode.DARK) { viewModel.setThemeMode(ThemeMode.DARK) }
    }
    OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.RestartAlt, contentDescription = null)
        Spacer(Modifier.width(Dimens.SpaceSM))
        Text(stringResource(R.string.settings_reset_defaults))
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.settings_reset_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.settings_reset_confirm_body)) },
            confirmButton = {
                Button(onClick = { confirmReset = false; viewModel.resetLocalDefaults(); activity?.recreate() }) {
                    Text(stringResource(R.string.settings_reset_defaults))
                }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

// ───────────────────────────── بيانات الشركة ─────────────────────────────

@Composable
private fun CompanySection(viewModel: SettingsViewModel, state: SettingsUiState) {
    // المسودة تبدأ من القيم المحفوظة كاملة: تعديل حقل واحد يُرسل بقية الحقول بقيمها الحالية فلا يُصفَّر شيء
    var draft by remember(state.companyInfo) { mutableStateOf(state.companyInfo) }
    val errors = state.companyErrors

    SettingsCard {
        LogoPreview(draft.logoUrl)
        FormField(R.string.settings_field_company, draft.companyName, errors["company"]) { draft = draft.copy(companyName = it); viewModel.clearCompanyError("company") }
        FormField(R.string.settings_field_ssid, draft.networkName, null) { draft = draft.copy(networkName = it) }
        FormField(R.string.settings_field_support, draft.phone, null, KeyboardType.Phone) { draft = draft.copy(phone = it) }
        FormField(R.string.settings_field_whatsapp, draft.whatsapp, null, KeyboardType.Phone) { draft = draft.copy(whatsapp = it) }
        FormField(R.string.settings_field_email, draft.email, errors[CompanyInfoValidator.FIELD_EMAIL], KeyboardType.Email) { draft = draft.copy(email = it); viewModel.clearCompanyError(CompanyInfoValidator.FIELD_EMAIL) }
        FormField(R.string.settings_field_website, draft.website, errors[CompanyInfoValidator.FIELD_WEBSITE], KeyboardType.Uri) { draft = draft.copy(website = it); viewModel.clearCompanyError(CompanyInfoValidator.FIELD_WEBSITE) }
        FormField(R.string.settings_field_address, draft.address, null) { draft = draft.copy(address = it) }
        FormField(R.string.settings_field_login_url, draft.loginUrl, errors[CompanyInfoValidator.FIELD_LOGIN_URL], KeyboardType.Uri) { draft = draft.copy(loginUrl = it); viewModel.clearCompanyError(CompanyInfoValidator.FIELD_LOGIN_URL) }
        FormField(R.string.settings_field_logo_url, draft.logoUrl, errors[CompanyInfoValidator.FIELD_LOGO], KeyboardType.Uri) { draft = draft.copy(logoUrl = it); viewModel.clearCompanyError(CompanyInfoValidator.FIELD_LOGO) }
        FormField(R.string.settings_field_footer, draft.footerNotes, null, imeAction = ImeAction.Default, singleLine = false) { draft = draft.copy(footerNotes = it) }
        FormField(R.string.settings_field_currency, draft.currencySymbol, errors[CompanyInfoValidator.FIELD_CURRENCY], imeAction = ImeAction.Done) { draft = draft.copy(currencySymbol = it); viewModel.clearCompanyError(CompanyInfoValidator.FIELD_CURRENCY) }
        Button(
            onClick = { viewModel.saveCompanyInfo(draft) },
            shape = RoundedCornerShape(Dimens.RadiusMD),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = !state.isSaving
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(Dimens.SpaceSM))
                Text(stringResource(R.string.settings_btn_save), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** يعرض الشعار من الرابط المحفوظ إن كان صالحاً وقابلاً للتحميل؛ وإلا لا يعرض شيئاً (بلا مكتبات صور إضافية). */
@Composable
private fun LogoPreview(url: String) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url) {
        value = if (!CompanyInfoValidator.isUrlValid(url) || url.isBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(url).openConnection().apply { connectTimeout = 5_000; readTimeout = 5_000 }
                conn.getInputStream().use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let {
        Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxWidth().height(96.dp))
    }
}

// ───────────────────────────── الاتصال والخدمات ─────────────────────────────

@Composable
private fun ConnectionSection(viewModel: SettingsViewModel, state: SettingsUiState, onOpenRouterSetup: () -> Unit, onOpenUserManSetup: () -> Unit) {
    val status by viewModel.routerStatus.collectAsState()
    val extended = LocalMgtikExtendedColors.current

    SettingsCard {
        GroupTitle(stringResource(R.string.settings_cloud_title))
        Text(
            when (status.configLoad) {
                ConfigLoadState.LOADED -> stringResource(R.string.settings_cloud_ok)
                ConfigLoadState.UNAVAILABLE -> stringResource(R.string.settings_cloud_unavailable)
                ConfigLoadState.LOADING -> stringResource(R.string.settings_cloud_loading)
            },
            color = if (status.configLoad == ConfigLoadState.UNAVAILABLE) MaterialTheme.colorScheme.error else extended.success,
            style = MaterialTheme.typography.bodyMedium
        )
    }
    SettingsCard {
        GroupTitle(stringResource(R.string.settings_router_card_title))
        val label = when (status.state) {
            RouterConnectionState.CONNECTED -> stringResource(R.string.router_status_connected_fmt, status.info?.name ?: "MikroTik", status.config.host)
            RouterConnectionState.CONNECTING -> stringResource(R.string.router_status_connecting)
            RouterConnectionState.RECONNECTING -> stringResource(R.string.router_status_reconnecting)
            RouterConnectionState.CONFIGURING -> stringResource(R.string.router_status_configuring)
            RouterConnectionState.NOT_CONFIGURED -> stringResource(R.string.router_status_not_configured)
            RouterConnectionState.DISCONNECTED -> stringResource(R.string.router_status_disconnected)
            RouterConnectionState.CONNECTION_FAILED -> stringResource(R.string.router_status_failed)
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            color = if (status.isConnected) extended.success else MaterialTheme.colorScheme.onSurface)
        status.errorCode?.takeIf { !status.isConnected }?.let {
            Text(
                stringResource(R.string.router_alert_reason_fmt, stringResource(RouterErrorTexts.reasonRes(it))),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
            )
        }
        if (status.lastCheckedAtMillis > 0) {
            Text(
                stringResource(R.string.settings_router_last_check_fmt, SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(status.lastCheckedAtMillis))),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(onClick = onOpenRouterSetup, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Router, contentDescription = null)
            Spacer(Modifier.width(Dimens.SpaceSM))
            Text(stringResource(R.string.settings_router_open_setup))
        }
        OutlinedButton(onClick = onOpenUserManSetup, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Dns, contentDescription = null)
            Spacer(Modifier.width(Dimens.SpaceSM))
            Text(stringResource(R.string.um_title))
        }
    }
    ConnectionTests(viewModel, state)
    Text(stringResource(R.string.settings_connection_separate_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** اختباران حقيقيان منفصلان: وصول الخادم السحابي، واتصال الراوتر عبر المنظومة المركزية. */
@Composable
private fun ConnectionTests(viewModel: SettingsViewModel, state: SettingsUiState) {
    SettingsCard {
        OutlinedButton(onClick = viewModel::testCloud, enabled = !state.isTestingCloud, modifier = Modifier.fillMaxWidth()) {
            if (state.isTestingCloud) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Cloud, contentDescription = null)
            Spacer(Modifier.width(Dimens.SpaceSM))
            Text(stringResource(R.string.settings_cloud_test))
        }
        state.cloudPingMs?.let { Text(stringResource(R.string.settings_cloud_test_ok_fmt, it.toInt()), color = LocalMgtikExtendedColors.current.success, style = MaterialTheme.typography.bodySmall) }
        state.cloudPingError?.let { Text(stringResource(R.string.settings_cloud_test_failed_fmt, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        OutlinedButton(onClick = viewModel::testRouter, enabled = !state.isTestingRouter, modifier = Modifier.fillMaxWidth()) {
            if (state.isTestingRouter) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Router, contentDescription = null)
            Spacer(Modifier.width(Dimens.SpaceSM))
            Text(stringResource(R.string.settings_router_test))
        }
        when (state.routerTestOk) {
            true -> Text(stringResource(R.string.settings_router_test_ok_fmt, state.routerTestDetail.orEmpty()), color = LocalMgtikExtendedColors.current.success, style = MaterialTheme.typography.bodySmall)
            false -> Text(stringResource(R.string.settings_router_test_failed_fmt, state.routerTestDetail.orEmpty()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            null -> Unit
        }
    }
}

// ───────────────────────────── الطباعة والتصدير ─────────────────────────────

@Composable
private fun PrintingSection(viewModel: SettingsViewModel) {
    val saved by viewModel.outputSettings.collectAsState()
    var draft by remember(saved) { mutableStateOf(saved) }
    val margin = draft.margins.top

    SettingsCard {
        GroupTitle(stringResource(R.string.settings_print_page))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
            FilterChip(selected = draft.page == PageSize.A4, onClick = { draft = draft.copy(page = PageSize.A4) }, label = { Text("A4") })
            FilterChip(selected = draft.page == PageSize.A5, onClick = { draft = draft.copy(page = PageSize.A5) }, label = { Text("A5") })
        }
        GroupTitle(stringResource(R.string.settings_print_orientation))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
            FilterChip(selected = draft.orientation == PrintOrientation.PORTRAIT, onClick = { draft = draft.copy(orientation = PrintOrientation.PORTRAIT) }, label = { Text(stringResource(R.string.settings_print_portrait)) })
            FilterChip(selected = draft.orientation == PrintOrientation.LANDSCAPE, onClick = { draft = draft.copy(orientation = PrintOrientation.LANDSCAPE) }, label = { Text(stringResource(R.string.settings_print_landscape)) })
        }
        GroupTitle(stringResource(R.string.settings_print_fill))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
            FilterChip(selected = draft.fillDirection == FillDirection.LTR, onClick = { draft = draft.copy(fillDirection = FillDirection.LTR) }, label = { Text(stringResource(R.string.settings_print_ltr)) })
            FilterChip(selected = draft.fillDirection == FillDirection.RTL, onClick = { draft = draft.copy(fillDirection = FillDirection.RTL) }, label = { Text(stringResource(R.string.settings_print_rtl)) })
        }
        StepperRow(stringResource(R.string.settings_print_columns), draft.columns.toString(),
            onMinus = { draft = draft.copy(columns = (draft.columns - 1).coerceAtLeast(1)) },
            onPlus = { draft = draft.copy(columns = (draft.columns + 1).coerceAtMost(LayoutPlanner.MAX_COLUMNS)) })
        StepperRow(stringResource(R.string.settings_print_margin), margin.toInt().toString(),
            onMinus = { val v = (margin - 2).coerceAtLeast(0.0); draft = draft.copy(margins = Margins(v, v, v, v)) },
            onPlus = { val v = (margin + 2).coerceAtMost(MAX_MARGIN_PT); draft = draft.copy(margins = Margins(v, v, v, v)) })
        StepperRow(stringResource(R.string.settings_print_col_gap), draft.columnGapPt.toInt().toString(),
            onMinus = { draft = draft.copy(columnGapPt = (draft.columnGapPt - 2).coerceAtLeast(0.0)) },
            onPlus = { draft = draft.copy(columnGapPt = (draft.columnGapPt + 2).coerceAtMost(MAX_GAP_PT)) })
        StepperRow(stringResource(R.string.settings_print_row_gap), draft.rowGapPt.toInt().toString(),
            onMinus = { draft = draft.copy(rowGapPt = (draft.rowGapPt - 2).coerceAtLeast(0.0)) },
            onPlus = { draft = draft.copy(rowGapPt = (draft.rowGapPt + 2).coerceAtMost(MAX_GAP_PT)) })
        Text(stringResource(R.string.settings_print_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { viewModel.saveOutputSettings(draft) }, enabled = draft != saved, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(Modifier.width(Dimens.SpaceSM))
            Text(stringResource(R.string.settings_btn_save), fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = { viewModel.saveOutputSettings(AppPreferences.DEFAULT_OUTPUT) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_print_reset))
        }
    }
}

private const val MAX_MARGIN_PT = 60.0
private const val MAX_GAP_PT = 40.0

// ───────────────────────────── الإشعارات ─────────────────────────────

@Composable
private fun NotificationsSection(viewModel: SettingsViewModel) {
    val billing by viewModel.notifyBilling.collectAsState()
    val generation by viewModel.notifyGeneration.collectAsState()
    val connection by viewModel.connectionNotification.collectAsState()
    val context = LocalContext.current
    // إذن POST_NOTIFICATIONS (Android 13+) يُطلب عند التفعيل؛ الرفض يُبقي الخيار معطّلاً
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.setConnectionNotification(granted) }
    val onConnectionToggle: (Boolean) -> Unit = { enable ->
        if (!enable) {
            viewModel.setConnectionNotification(false)
        } else if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.setConnectionNotification(true)
        }
    }
    SettingsCard {
        SwitchRow(stringResource(R.string.settings_notif_billing), stringResource(R.string.settings_notif_billing_desc), billing, viewModel::setNotifyBilling)
        SwitchRow(stringResource(R.string.settings_notif_generation), stringResource(R.string.settings_notif_generation_desc), generation, viewModel::setNotifyGeneration)
        SwitchRow(stringResource(R.string.settings_notif_connection), stringResource(R.string.settings_notif_connection_desc), connection, onConnectionToggle)
    }
    Text(stringResource(R.string.settings_notif_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ───────────────────────────── البيانات والصيانة ─────────────────────────────

@Composable
private fun DataSection(
    viewModel: SettingsViewModel,
    state: SettingsUiState,
    userManViewModel: ps.mgtik.hotspot.ui.viewmodel.UserManViewModel,
    onOpenUserManSetup: () -> Unit
) {
    val context = LocalContext.current
    val d = state.diagnostics
    var confirmTemp by remember { mutableStateOf(false) }
    var confirmLogs by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refreshDiagnostics() }

    SettingsCard {
        if (state.isDiagnosticsLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.settings_diag_cloud_fmt, d.configLoad.name), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.settings_diag_router_fmt, d.routerState.name), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.settings_data_pending_fmt, d.pendingCommands), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.settings_data_temp_fmt, android.text.format.Formatter.formatShortFileSize(context, d.tempFilesBytes)), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = viewModel::refreshDiagnostics, enabled = !state.isDiagnosticsLoading, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(Dimens.SpaceSM))
            Text(stringResource(R.string.settings_diag_refresh))
        }
    }
    ConnectionTests(viewModel, state)
    // صيانة User Manager: الحالة والفحص والنسخ الاحتياطي وتحسين القاعدة وتنظيف البقايا
    ps.mgtik.hotspot.ui.components.userman.UserManMaintenanceCard(userManViewModel, onOpenUserManSetup)
    SettingsCard {
        GroupTitle(stringResource(R.string.settings_diag_recent_errors))
        if (d.recentErrors.isEmpty()) {
            Text(stringResource(R.string.settings_diag_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
            d.recentErrors.take(5).forEach {
                Text("${fmt.format(Date(it.timestamp))}  ${it.action}: ${it.detail}", style = MaterialTheme.typography.labelSmall, maxLines = 3)
            }
        }
        OutlinedButton(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, viewModel.buildDiagnosticsReport())
                }
                context.startActivity(Intent.createChooser(send, null))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(Modifier.width(Dimens.SpaceSM))
            Text(stringResource(R.string.settings_diag_share))
        }
    }
    SettingsCard {
        OutlinedButton(onClick = { confirmTemp = true }, enabled = !state.isCleaning, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.DeleteSweep, contentDescription = null)
            Spacer(Modifier.width(Dimens.SpaceSM))
            Text(stringResource(R.string.settings_data_clean_temp))
        }
        OutlinedButton(onClick = { confirmLogs = true }, enabled = !state.isCleaning, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.DeleteSweep, contentDescription = null)
            Spacer(Modifier.width(Dimens.SpaceSM))
            Text(stringResource(R.string.settings_data_clean_logs))
        }
        Text(stringResource(R.string.settings_data_clean_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirmTemp) ConfirmDialog(R.string.settings_data_clean_temp, R.string.settings_data_clean_note, onDismiss = { confirmTemp = false }) { confirmTemp = false; viewModel.clearTempFiles() }
    if (confirmLogs) ConfirmDialog(R.string.settings_data_clean_logs, R.string.settings_data_clean_note, onDismiss = { confirmLogs = false }) { confirmLogs = false; viewModel.clearOldLogs() }
}

@Composable
private fun ConfirmDialog(@androidx.annotation.StringRes titleRes: Int, @androidx.annotation.StringRes bodyRes: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes), fontWeight = FontWeight.Bold) },
        text = { Text(stringResource(bodyRes)) },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

// ───────────────────────────── عن التطبيق ─────────────────────────────

@Composable
private fun AboutSection(viewModel: SettingsViewModel, state: SettingsUiState) {
    val d = state.diagnostics
    LaunchedEffect(Unit) { viewModel.refreshDiagnostics() }
    SettingsCard {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (d.appVersionName.isBlank()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            Text(stringResource(R.string.settings_about_version_fmt, d.appVersionName, d.appVersionCode.toInt()))
            Text(stringResource(R.string.settings_about_db_fmt, d.dbVersion))
            Text(stringResource(R.string.settings_about_build_fmt, stringResource(if (d.isDebugBuild) R.string.settings_build_debug else R.string.settings_build_release)))
            Text(stringResource(R.string.settings_about_env_fmt, d.androidApi, d.device))
        }
    }
}

// ───────────────────────────── مكونات مشتركة ─────────────────────────────

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(Dimens.RadiusLG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(vertical = Dimens.SpaceSM, horizontal = Dimens.SpaceSM), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM), content = content)
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = Dimens.SpaceSM))
}

@Composable
private fun HubRow(icon: androidx.compose.ui.graphics.vector.ImageVector, @androidx.annotation.StringRes titleRes: Int, @androidx.annotation.StringRes descRes: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(titleRes), fontWeight = FontWeight.Medium) },
        supportingContent = { Text(stringResource(descRes)) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Dimens.SpaceSM),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.padding(start = Dimens.SpaceSM))
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(desc) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
    )
}

@Composable
private fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceSM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMinus) { Icon(Icons.Default.Remove, contentDescription = null) }
            Text(value, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 32.dp))
            IconButton(onClick = onPlus) { Icon(Icons.Default.Add, contentDescription = null) }
        }
    }
}

@Composable
private fun FormField(
    @androidx.annotation.StringRes labelRes: Int,
    value: String,
    @androidx.annotation.StringRes errorRes: Int?,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    singleLine: Boolean = true,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(labelRes)) },
        isError = errorRes != null,
        supportingText = { errorRes?.let { Text(stringResource(it)) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        singleLine = singleLine,
        shape = RoundedCornerShape(Dimens.RadiusMD),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ChangePasswordDialog(
    state: ps.mgtik.hotspot.ui.viewmodel.AuthUiState,
    onSubmit: (String, String) -> Unit,
    onClose: () -> Unit
) {
    var current by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val done = state.passwordNoticeResId != null
    val mismatch = confirm.isNotEmpty() && confirm != newPass
    val valid = current.isNotEmpty() && ps.mgtik.hotspot.domain.usecase.AuthValidator.isPasswordValid(newPass) && confirm == newPass
    val hide = androidx.compose.ui.text.input.PasswordVisualTransformation()
    AlertDialog(
        onDismissRequest = { if (!state.isPasswordActionLoading) onClose() },
        title = { Text(stringResource(R.string.password_change_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                if (done) {
                    Text(stringResource(R.string.password_changed))
                } else {
                    OutlinedTextField(current, { current = it }, label = { Text(stringResource(R.string.password_current)) },
                        singleLine = true, visualTransformation = hide, enabled = !state.isPasswordActionLoading, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(newPass, { newPass = it }, label = { Text(stringResource(R.string.password_new)) },
                        singleLine = true, visualTransformation = hide, enabled = !state.isPasswordActionLoading, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(confirm, { confirm = it }, label = { Text(stringResource(R.string.password_confirm)) },
                        singleLine = true, visualTransformation = hide, enabled = !state.isPasswordActionLoading, isError = mismatch,
                        supportingText = { if (mismatch) Text(stringResource(R.string.password_mismatch)) },
                        modifier = Modifier.fillMaxWidth())
                    state.passwordErrorResId?.let {
                        Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        confirmButton = {
            if (done) TextButton(onClick = onClose) { Text(stringResource(R.string.common_close)) }
            else TextButton(onClick = { onSubmit(current, newPass) }, enabled = valid && !state.isPasswordActionLoading) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            if (!done) TextButton(onClick = onClose, enabled = !state.isPasswordActionLoading) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
private fun SettingsLinkRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, fontWeight = FontWeight.Medium) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick)
    )
}

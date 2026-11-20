package ps.mgtik.hotspot.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.model.NewNormalUser
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.SystemClass
import ps.mgtik.hotspot.domain.usecase.SingleUserRules
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.util.LocalCurrencySymbol
import ps.mgtik.hotspot.ui.viewmodel.UsersViewModel
import java.util.UUID

/**
 * S16 (إنشاء) - إنشاء مستخدم فردي من إدارة المستخدمين، دون معالج التوليد ولا لوحة الفوترة.
 *  • NORMAL: نموذج المستخدم العادي (اسم، كلمة سر، باقة من فئات NORMAL) ← CreateNormalUserUseCase.
 *  • SUBSCRIPTION: نموذج الاشتراك نفسه المستعمل في S27 ([SubscriptionFormContent]) ← CreateManagedSubscriberUseCase
 *    (المسار نفسه الذي تستعمله لوحة الفوترة)، وتُعرض له باقات فئات SUBSCRIPTION فقط.
 * المالك افتراضياً «admin» ويُحسم في طبقة المستودع (لا اختيار ولا إدخال هنا). لا منطق إنشاء في الواجهة: كل ما هنا جمع مدخلات.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S16_UserCreateScreen(
    viewModel: UsersViewModel,
    normalProfiles: List<Profile>,
    subscriptionProfiles: List<Profile>,
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var type by rememberSaveable { mutableStateOf(SystemClass.NORMAL.name) }
    val isSubscription = type == SystemClass.SUBSCRIPTION.name

    LaunchedEffect(Unit) {
        viewModel.clearCreateState()
    }
    // قُبل الإنشاء (مكتمل أو معلّق على الراوتر): الرسالة تظهر في S16 والسجل الجديد ضمن قائمته اللحظية
    LaunchedEffect(state.createAccepted) {
        if (state.createAccepted) {
            viewModel.clearCreateState()
            onDone()
        }
    }

    Scaffold(
        topBar = {
            MgtikTopAppBar(
                title = { Text(stringResource(R.string.user_create_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.padding(horizontal = Dimens.ScreenHorizontalPadding, vertical = Dimens.SpaceMD),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)
            ) {
                Text(stringResource(R.string.user_create_type_label), fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                    FilterChip(
                        selected = !isSubscription,
                        onClick = { type = SystemClass.NORMAL.name },
                        enabled = !state.isCreating,
                        label = { Text(stringResource(R.string.sysclass_normal)) }
                    )
                    FilterChip(
                        selected = isSubscription,
                        onClick = { type = SystemClass.SUBSCRIPTION.name },
                        enabled = !state.isCreating,
                        label = { Text(stringResource(R.string.sysclass_subscription)) }
                    )
                }
                Text(
                    stringResource(R.string.user_create_owner_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(Modifier.weight(1f)) {
                if (isSubscription) {
                    // الحقول المناسبة لدورة حياة الاشتراك فقط (مدة/بدء مجدول/خصم/تجديد تلقائي)، ولا تعليق
                    SubscriptionFormContent(
                        profiles = subscriptionProfiles,
                        isCreating = state.isCreating,
                        errorResId = state.createErrorResId,
                        onSubmit = { draft, subscriptionId -> viewModel.createSubscriber(draft, subscriptionId) }
                    )
                } else {
                    NormalUserForm(
                        profiles = normalProfiles,
                        isCreating = state.isCreating,
                        errorResId = state.createErrorResId,
                        onSubmit = { draft, key -> viewModel.createNormalUser(draft, key) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NormalUserForm(
    profiles: List<Profile>,
    isCreating: Boolean,
    errorResId: Int?,
    onSubmit: (NewNormalUser, String) -> Unit
) {
    // مفتاح idempotency: يُولَّد مرة لكل فتح للنموذج، فإعادة المحاولة بعد فشل شبكة لا تكرّر السجل ولا الحساب
    val operationKey = rememberSaveable { UUID.randomUUID().toString() }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") } // سر: لا يدخل Bundle الحالة المحفوظة (يضيع عند تدوير الشاشة عمداً)
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    var comment by rememberSaveable { mutableStateOf("") }
    val currency = LocalCurrencySymbol.current

    val profile = profiles.firstOrNull { it.id == profileId }
    val draft = profile?.let {
        NewNormalUser(
            username = username.trim(),
            password = password,
            profileId = it.id,
            routerProfileName = it.linkedProfileName,
            comment = comment
        )
    }
    val validation = if (draft == null) SingleUserRules.ERR_PROFILE_REQUIRED else SingleUserRules.validateNew(draft)
    val usernameBad = username.isNotBlank() && !SingleUserRules.isUsernameValid(username.trim())
    val passwordBad = password.isNotEmpty() && !SingleUserRules.isPasswordValid(password)
    val canSubmit = draft != null && validation == null && !isCreating

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Dimens.ScreenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)
    ) {
        OutlinedTextField(
            username, { username = it.filter { c -> !c.isWhitespace() } },
            label = { Text(stringResource(R.string.sub_form_username)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(Dimens.RadiusMD),
            isError = usernameBad,
            supportingText = { if (usernameBad) Text(stringResource(R.string.sub_err_username_invalid)) }
        )
        OutlinedTextField(
            password, { password = it.filter { c -> !c.isWhitespace() } },
            label = { Text(stringResource(R.string.sub_form_password)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(Dimens.RadiusMD),
            isError = passwordBad,
            supportingText = { if (passwordBad) Text(stringResource(R.string.sub_err_password_invalid)) },
            trailingIcon = { TextButton(onClick = { password = generatePassword() }) { Text(stringResource(R.string.sub_form_generate_password)) } }
        )

        Text(stringResource(R.string.sub_form_profile), fontWeight = FontWeight.Bold)
        if (profiles.isEmpty()) {
            Text(stringResource(R.string.user_create_no_normal_profiles), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        profiles.forEach { p ->
            Card(
                onClick = { profileId = p.id },
                shape = RoundedCornerShape(Dimens.RadiusMD),
                border = BorderStroke(if (p.id == profileId) 2.dp else 1.dp, if (p.id == profileId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(Modifier.padding(Dimens.SpaceMD).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = p.id == profileId, onClick = { profileId = p.id })
                    Text(p.displayNameAr.ifBlank { p.displayNameEn }.ifBlank { p.linkedProfileName }, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Text("${formatMoney(p.priceMinor)} $currency", fontWeight = FontWeight.Black)
                }
            }
        }

        OutlinedTextField(
            comment, { comment = it },
            label = { Text(stringResource(R.string.user_create_comment)) },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Dimens.RadiusMD), minLines = 1
        )

        errorResId?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        Button(
            onClick = { draft?.let { onSubmit(it, operationKey) } },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(Dimens.RadiusMD)
        ) {
            if (isCreating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.user_create_save), fontWeight = FontWeight.Bold)
        }
    }
}

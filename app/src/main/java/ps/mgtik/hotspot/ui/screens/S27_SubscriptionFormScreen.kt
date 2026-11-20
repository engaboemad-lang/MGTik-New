package ps.mgtik.hotspot.ui.screens

import ps.mgtik.hotspot.ui.util.LocalCurrencySymbol
import androidx.annotation.StringRes
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.subscription.SubscriptionBilling
import ps.mgtik.hotspot.domain.subscription.SubscriptionRules
import ps.mgtik.hotspot.domain.usecase.OwnerInputValidator
import ps.mgtik.hotspot.ui.theme.Dimens
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID

// ───────────────────── إنشاء اشتراك ─────────────────────

/**
 * نموذج إنشاء اشتراك: بيانات العميل وحساب المستخدم، اختيار الباقة (السعر والقيود لقطة منها)، المدة، البدء الفوري أو المجدول،
 * الخصم والتجديد التلقائي. معرّف الاشتراك يُولَّد مرة واحدة لكل فتح للنموذج فيكون مفتاح idempotency.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S27_SubscriptionFormScreen(
    profiles: List<Profile>,
    isCreating: Boolean,
    @StringRes errorResId: Int?,
    onSubmit: (NewSubscription, String) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            MgtikTopAppBar(
                title = { Text(stringResource(R.string.sub_form_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } }
            )
        }
    ) { padding ->
        SubscriptionFormContent(profiles, isCreating, errorResId, onSubmit, Modifier.padding(padding))
    }
}

/**
 * جسم نموذج الاشتراك (الحقول والتحقق وبناء NewSubscription) مستخرَج من S27 ليستعمله S27 وشاشة إنشاء المستخدم من S16 معاً:
 * مصدر واحد للنموذج وقواعده (SubscriptionRules.validateNew)، فلا نسخة ثانية تتباعد عنه.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubscriptionFormContent(
    profiles: List<Profile>,
    isCreating: Boolean,
    @StringRes errorResId: Int?,
    onSubmit: (NewSubscription, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val subscriptionId = rememberSaveable { UUID.randomUUID().toString() }
    var customerName by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") } // سر: لا يدخل Bundle الحالة المحفوظة (يضيع عند تدوير الشاشة عمداً)
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    var unitMonths by rememberSaveable { mutableStateOf(true) }
    var countStr by rememberSaveable { mutableStateOf("1") }
    var scheduled by rememberSaveable { mutableStateOf(false) }
    var startDateStr by rememberSaveable { mutableStateOf("") }
    var discountStr by rememberSaveable { mutableStateOf("") }
    var autoRenew by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf("") }
    val currency = LocalCurrencySymbol.current

    val profile = profiles.firstOrNull { it.id == profileId }
    val count = countStr.toIntOrNull() ?: 0
    val discountMinor = if (discountStr.isBlank()) 0L else OwnerInputValidator.parseAmountMinor(discountStr)
    val startInstant: Instant? = if (!scheduled) null else try {
        LocalDate.parse(startDateStr.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant()
    } catch (e: DateTimeParseException) { null }
    val startInvalid = scheduled && startInstant == null

    val draft = profile?.let {
        NewSubscription(
            customerName = customerName,
            customerPhone = phone,
            username = username.trim(),
            password = password,
            profileId = it.id,
            profileName = it.displayNameAr.ifBlank { it.displayNameEn }.ifBlank { it.linkedProfileName },
            routerProfileName = it.linkedProfileName,
            priceMinor = it.priceMinor,
            discountMinor = discountMinor ?: -1L,
            limitUptimeSeconds = it.limitUptimeSeconds,
            limitTransferBytes = it.limitTransferBytes,
            periodUnit = if (unitMonths) PeriodUnit.MONTHS else PeriodUnit.DAYS,
            periodCount = count,
            // بداية الأمس أو اليوم = فوري (null)؛ المستقبل فقط مجدول
            startsAtIso = startInstant?.takeIf { s -> s.isAfter(Instant.now()) }?.let { s -> SubscriptionRules.iso(s) },
            autoRenewal = autoRenew,
            notes = notes
        )
    }
    val validationCode = if (draft == null) SubscriptionRules.ERR_PROFILE_REQUIRED else SubscriptionRules.validateNew(draft, Instant.now())
    val canSubmit = draft != null && validationCode == null && !startInvalid && !isCreating

    run {
        Column(
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Dimens.ScreenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)
        ) {
            OutlinedTextField(customerName, { customerName = it }, label = { Text(stringResource(R.string.sub_form_customer_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(Dimens.RadiusMD))
            OutlinedTextField(
                phone, { phone = it }, label = { Text(stringResource(R.string.sub_form_phone)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                shape = RoundedCornerShape(Dimens.RadiusMD), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
            )
            OutlinedTextField(
                username, { username = it.filter { c -> !c.isWhitespace() } }, label = { Text(stringResource(R.string.sub_form_username)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(Dimens.RadiusMD),
                isError = username.isNotBlank() && validationCode == SubscriptionRules.ERR_USERNAME_INVALID,
                supportingText = { if (username.isNotBlank() && validationCode == SubscriptionRules.ERR_USERNAME_INVALID) Text(stringResource(R.string.sub_err_username_invalid)) }
            )
            OutlinedTextField(
                password, { password = it.filter { c -> !c.isWhitespace() } }, label = { Text(stringResource(R.string.sub_form_password)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(Dimens.RadiusMD),
                trailingIcon = { TextButton(onClick = { password = generatePassword() }) { Text(stringResource(R.string.sub_form_generate_password)) } }
            )

            Text(stringResource(R.string.sub_form_profile), fontWeight = FontWeight.Bold)
            if (profiles.isEmpty()) {
                Text(stringResource(R.string.sub_form_no_profiles), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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

            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    countStr, { countStr = it.filter { c -> c.isDigit() }.take(4) }, label = { Text(stringResource(R.string.sub_form_period_count)) },
                    modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(Dimens.RadiusMD),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = validationCode == SubscriptionRules.ERR_PERIOD_INVALID
                )
                FilterChip(selected = unitMonths, onClick = { unitMonths = true }, label = { Text(stringResource(R.string.sub_form_period_unit_months)) })
                FilterChip(selected = !unitMonths, onClick = { unitMonths = false }, label = { Text(stringResource(R.string.sub_form_period_unit_days)) })
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = scheduled, onCheckedChange = { scheduled = it })
                Spacer(Modifier.width(Dimens.SpaceSM))
                Text(stringResource(R.string.sub_form_schedule_toggle))
            }
            if (scheduled) {
                OutlinedTextField(
                    startDateStr, { startDateStr = it.take(10) }, label = { Text(stringResource(R.string.sub_form_start_date_hint)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(Dimens.RadiusMD),
                    isError = startInvalid, supportingText = { if (startInvalid) Text(stringResource(R.string.sub_err_start_invalid)) }
                )
            }

            OutlinedTextField(
                discountStr, { new -> if (new.all { it.isDigit() || it == '.' } && new.count { it == '.' } <= 1) discountStr = new },
                label = { Text(stringResource(R.string.sub_form_discount)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                shape = RoundedCornerShape(Dimens.RadiusMD), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = discountStr.isNotBlank() && validationCode == SubscriptionRules.ERR_DISCOUNT_INVALID || discountMinor == null,
                supportingText = { if (validationCode == SubscriptionRules.ERR_DISCOUNT_INVALID || (discountStr.isNotBlank() && discountMinor == null)) Text(stringResource(R.string.sub_err_discount_invalid)) }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = autoRenew, onCheckedChange = { autoRenew = it })
                Spacer(Modifier.width(Dimens.SpaceSM))
                Text(stringResource(R.string.sub_form_auto_renew))
            }
            OutlinedTextField(notes, { notes = it }, label = { Text(stringResource(R.string.sub_form_notes)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Dimens.RadiusMD), minLines = 2)

            if (profile != null && discountMinor != null) {
                val net = SubscriptionBilling.netMinor(profile.priceMinor, discountMinor)
                Text(
                    stringResource(R.string.sub_form_price_summary, formatMoney(profile.priceMinor), formatMoney(discountMinor), formatMoney(net), currency),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                )
            }
            errorResId?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            Button(
                onClick = { draft?.let { onSubmit(it, subscriptionId) } },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(Dimens.RadiusMD)
            ) {
                if (isCreating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.sub_form_save), fontWeight = FontWeight.Bold)
            }
        }
    }
}

internal fun generatePassword(length: Int = 8): String {
    val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
    val random = SecureRandom()
    return (1..length).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
}

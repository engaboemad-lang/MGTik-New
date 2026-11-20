package ps.mgtik.hotspot.ui.screens

import ps.mgtik.hotspot.ui.util.LocalCurrencySymbol
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.generation.CodeFormat
import ps.mgtik.hotspot.domain.generation.GenerationDefaults
import ps.mgtik.hotspot.domain.generation.GenerationMode
import ps.mgtik.hotspot.domain.generation.GenerationRules
import ps.mgtik.hotspot.domain.generation.PasswordType
import ps.mgtik.hotspot.domain.generation.VoucherCodeGenerator
import ps.mgtik.hotspot.domain.model.OwnerAccount
import ps.mgtik.hotspot.domain.model.OwnerStatus
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.viewmodel.GenerationInput
import ps.mgtik.hotspot.ui.viewmodel.GenerationUiState
import ps.mgtik.hotspot.ui.viewmodel.MgtikUiState

/**
 * S4_GeneratorWizardScreen - إعداد دفعة كروت جديدة (كل الحقول موصولة بالمنطق الفعلي):
 *  1) الوكيل 2) الباقة 3) اسم المستخدم: العدد/البادئة/النوع/الطول
 *  5) وضع التوليد (تسلسلي بعدّاد دائم لكل باقة، أو عشوائي) 6) كلمة المرور 7) التعليق.
 *  - الوكيل والباقة اختياران صريحان بلا افتراضي (لا بيع مباشر بلا مالك).
 *  - التحقق من قواعد GenerationRules نفسها التي تطبّقها الساغا مع رسالة لكل حقل.
 *  - طول اسم المستخدم هو طول الجزء المولَّد فقط (لا يشمل البادئة).
 *  - شاشة تأكيد تعرض الباقة/الوكيل/السيرفر/الوضع/الكمية/الإجمالي التقديري قبل الإنشاء.
 */
@Composable
fun S4_GeneratorWizardScreen(
    state: MgtikUiState,
    generationState: GenerationUiState,
    onStartGeneration: (profile: Profile, input: GenerationInput) -> Unit,
    onFieldEdited: (String) -> Unit,
    initialOwnerId: String? = null,
    onProfileSelected: (String?) -> Unit = {},
    /** آخر إعدادات مستعملة للباقة (أو عامة، أو مصنعية)؛ تخصيصها هنا لا يغيّر الباقة نفسها. */
    defaultsFor: (String?) -> GenerationDefaults = { GenerationDefaults.FACTORY }
) {
    val initial = remember { defaultsFor(null) }
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    var ownerId by rememberSaveable { mutableStateOf<String?>(initialOwnerId) }
    var countText by rememberSaveable { mutableStateOf("100") }
    var prefix by rememberSaveable { mutableStateOf(initial.prefix) }
    var lengthText by rememberSaveable { mutableStateOf(initial.codeLength.toString()) }
    var formatName by rememberSaveable { mutableStateOf(initial.format) }
    var modeName by rememberSaveable { mutableStateOf(initial.mode) }
    var passwordTypeName by rememberSaveable { mutableStateOf(initial.passwordType) }
    var passwordLengthText by rememberSaveable { mutableStateOf(initial.passwordLength.takeIf { it > 0 }?.toString() ?: "6") }
    var comment by rememberSaveable { mutableStateOf("") }
    var showConfirm by rememberSaveable { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val currencySymbol = LocalCurrencySymbol.current
    // عند تغيّر الباقة: التسعير يُعاد، وتُطبَّق آخر إعدادات استُعملت لها (تخصيص محلي لا يعدّل الباقة)
    androidx.compose.runtime.LaunchedEffect(profileId) {
        onProfileSelected(profileId)
        if (profileId != null) {
            val d = defaultsFor(profileId)
            prefix = d.prefix; lengthText = d.codeLength.toString(); formatName = d.format; modeName = d.mode
            passwordTypeName = d.passwordType
            if (d.passwordLength > 0) passwordLengthText = d.passwordLength.toString()
        }
    }

    // الاختيار يُحلّ مقابل القوائم الحالية كل مرة (وصولها المتأخر لا يترك الاختيار null للأبد)،
    // وباقة/مالك اختفى بعد الاختيار يُعامل كأنه غير مختار. الباقات غير الصالحة للتوليد (اشتراك/بلا اسم راوتر) لا تُعرض.
    val eligibleProfiles = generationState.profiles.filter { GenerationRules.isProfileGeneratable(it, generationState.categories) }
    val profile = eligibleProfiles.firstOrNull { it.id == profileId }
    // الموقوف والمؤرشف لا يُختاران للتوليد (السحابة ترفضهما أيضاً)
    val generatableOwners = state.owners.filter { it.status == OwnerStatus.ACTIVE }
    val owner = generatableOwners.firstOrNull { it.ownerId == ownerId }

    val count = countText.toIntOrNull() ?: 0
    val length = lengthText.toIntOrNull() ?: 0
    val passwordLength = passwordLengthText.toIntOrNull() ?: 0
    val mode = GenerationMode.parse(modeName) ?: GenerationMode.RANDOM
    val passwordType = PasswordType.parse(passwordTypeName) ?: PasswordType.NUMERIC_ONLY
    val sequential = mode == GenerationMode.SEQUENTIAL
    val errors = generationState.fieldErrors
    // نفاد نطاق العدّاد يُكشف هنا قبل البدء (الفحص نفسه عند الحجز في السحابة)
    val lastUsedSeq = generationState.sequences[profile?.id] ?: 0L
    val seqOverflow = sequential && profile != null && count > 0 && length in GenerationRules.MIN_CODE_LENGTH..GenerationRules.MAX_CODE_LENGTH &&
        !GenerationRules.sequenceFits(lastUsedSeq, count, length)
    val ltr = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)

    fun currentInput() = GenerationInput(
        ownerId = owner?.ownerId, count = count, prefix = prefix, formatName = formatName,
        codeLength = length, mode = modeName, passwordType = passwordTypeName,
        passwordLength = if (passwordType == PasswordType.SAME_AS_USERNAME) 0 else passwordLength, comment = comment.trim()
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = Dimens.MaxFormWidth)
            .verticalScroll(rememberScrollState())
            .padding(Dimens.ScreenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing)
    ) {
        Text(stringResource(R.string.gen_wizard_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        Text(stringResource(R.string.gen_wizard_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // 1) الوكيل (إلزامي)
        SectionTitle(stringResource(R.string.gen_step_owner_title))
        if (generatableOwners.isEmpty()) {
            Text(stringResource(R.string.gen_no_owners_msg), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        } else {
            OwnerDropdown(
                owners = generatableOwners,
                selected = owner,
                isError = errors.containsKey(GenerationRules.Field.OWNER),
                onSelected = { ownerId = it.ownerId; onFieldEdited(GenerationRules.Field.OWNER) }
            )
            errors[GenerationRules.Field.OWNER]?.let { FieldError(it) }
        }

        // 2) الباقة
        SectionTitle(stringResource(R.string.gen_step_package_title))
        if (eligibleProfiles.isEmpty()) {
            Text(stringResource(R.string.gen_no_profiles_msg), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                eligibleProfiles.forEach { p ->
                    ProfileOption(p, selected = p.id == profileId, currencySymbol = currencySymbol, categories = generationState.categories) {
                        profileId = p.id
                        onFieldEdited(GenerationRules.Field.PROFILE)
                    }
                }
            }
            errors[GenerationRules.Field.PROFILE]?.let { FieldError(it) }
        }

        // 3) إعدادات اسم المستخدم
        SectionTitle(stringResource(R.string.gen_step_username_title))
        OutlinedTextField(
            value = countText,
            onValueChange = { v -> countText = v.filter(Char::isDigit).take(4); onFieldEdited(GenerationRules.Field.COUNT) },
            label = { Text(stringResource(R.string.gen_count_label)) },
            isError = errors.containsKey(GenerationRules.Field.COUNT),
            supportingText = { errors[GenerationRules.Field.COUNT]?.let { Text(stringResource(it)) } },
            singleLine = true,
            textStyle = ltr,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Dimens.RadiusMD)
        )

        // وضع التوليد
        Text(stringResource(R.string.gen_field_mode), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
            FilterChip(
                selected = sequential,
                onClick = {
                    modeName = GenerationMode.SEQUENTIAL.name
                    formatName = CodeFormat.NUMERIC_ONLY.name // العدّاد رقمي
                    onFieldEdited(GenerationRules.Field.MODE); onFieldEdited(GenerationRules.Field.FORMAT)
                },
                label = { Text(stringResource(R.string.gen_mode_sequential)) }
            )
            FilterChip(
                selected = !sequential,
                onClick = { modeName = GenerationMode.RANDOM.name; onFieldEdited(GenerationRules.Field.MODE) },
                label = { Text(stringResource(R.string.gen_mode_random)) }
            )
        }
        errors[GenerationRules.Field.MODE]?.let { FieldError(it) }
        if (sequential) {
            val next = (generationState.sequences[profile?.id] ?: 0L) + 1
            Text(
                if (profile == null) stringResource(R.string.gen_sequence_pick_profile)
                else stringResource(R.string.gen_sequence_next_fmt, next),
                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.primary
            )
            if (seqOverflow) FieldError(R.string.gen_error_sequence_exhausted)
        }

        // نوع اسم المستخدم (التسلسلي أرقام فقط)
        Text(stringResource(R.string.gen_field_format), style = MaterialTheme.typography.labelLarge)
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
            // «اسم المستخدم = كلمة المرور» صار خيار كلمة مرور صريحاً (قسم كلمة المرور) لا نمطاً لاسم المستخدم
            CodeFormat.values().filter { it != CodeFormat.USER_PASS_SAME }.forEach { f ->
                FilterChip(
                    selected = formatName == f.name,
                    enabled = !sequential || f == CodeFormat.NUMERIC_ONLY,
                    onClick = { formatName = f.name; onFieldEdited(GenerationRules.Field.FORMAT) },
                    label = { Text(stringResource(formatLabel(f))) }
                )
            }
        }
        errors[GenerationRules.Field.FORMAT]?.let { FieldError(it) }

        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
            OutlinedTextField(
                value = prefix,
                onValueChange = { v -> prefix = v.filterNot(Char::isWhitespace).take(GenerationRules.MAX_PREFIX_LENGTH); onFieldEdited(GenerationRules.Field.PREFIX) },
                label = { Text(stringResource(R.string.gen_prefix_label)) },
                isError = errors.containsKey(GenerationRules.Field.PREFIX),
                supportingText = { errors[GenerationRules.Field.PREFIX]?.let { Text(stringResource(it)) } },
                singleLine = true,
                textStyle = ltr,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Right) }),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(Dimens.RadiusMD)
            )
            OutlinedTextField(
                value = lengthText,
                onValueChange = { v -> lengthText = v.filter(Char::isDigit).take(2); onFieldEdited(GenerationRules.Field.LENGTH) },
                label = { Text(stringResource(R.string.gen_length_label)) },
                isError = errors.containsKey(GenerationRules.Field.LENGTH),
                supportingText = { Text(stringResource(errors[GenerationRules.Field.LENGTH] ?: R.string.gen_length_hint)) },
                singleLine = true,
                textStyle = ltr,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(Dimens.RadiusMD)
            )
        }

        // 5) كلمة المرور
        SectionTitle(stringResource(R.string.gen_step_password_title))
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
            PasswordType.values().forEach { t ->
                FilterChip(
                    selected = passwordTypeName == t.name,
                    onClick = { passwordTypeName = t.name; onFieldEdited(GenerationRules.Field.PASSWORD_TYPE); onFieldEdited(GenerationRules.Field.PASSWORD_LENGTH) },
                    label = { Text(stringResource(passwordTypeLabel(t))) }
                )
            }
        }
        errors[GenerationRules.Field.PASSWORD_TYPE]?.let { FieldError(it) }
        if (passwordType != PasswordType.SAME_AS_USERNAME) {
            OutlinedTextField(
                value = passwordLengthText,
                onValueChange = { v -> passwordLengthText = v.filter(Char::isDigit).take(2); onFieldEdited(GenerationRules.Field.PASSWORD_LENGTH) },
                label = { Text(stringResource(R.string.gen_password_length_label)) },
                isError = errors.containsKey(GenerationRules.Field.PASSWORD_LENGTH),
                supportingText = { errors[GenerationRules.Field.PASSWORD_LENGTH]?.let { Text(stringResource(it)) } },
                singleLine = true,
                textStyle = ltr,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimens.RadiusMD)
            )
        }

        // 6) التعليق (يُطبَّق على كل الحسابات الناتجة ويُرسل إلى RouterOS)
        SectionTitle(stringResource(R.string.gen_step_comment_title))
        OutlinedTextField(
            value = comment,
            onValueChange = { v -> comment = v.filterNot { it == '\n' || it == '\r' }.take(GenerationRules.MAX_COMMENT_LENGTH); onFieldEdited(GenerationRules.Field.COMMENT) },
            label = { Text(stringResource(R.string.gen_comment_label)) },
            isError = errors.containsKey(GenerationRules.Field.COMMENT),
            supportingText = { Text(stringResource(errors[GenerationRules.Field.COMMENT] ?: R.string.gen_comment_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Dimens.RadiusMD)
        )

        // معاينة حقيقية: التسلسلي من الرقم التالي في عدّاد الباقة، والعشوائي من المولّد نفسه
        val previewFormat = CodeFormat.parse(formatName)
        val nextSeq = (generationState.sequences[profile?.id] ?: 0L) + 1
        val preview = remember(formatName, prefix, length, sequential, passwordTypeName, passwordLength, nextSeq) {
            val validPrefix = prefix.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }
            val pwOk = passwordType == PasswordType.SAME_AS_USERNAME ||
                passwordLength in GenerationRules.MIN_PASSWORD_LENGTH..GenerationRules.MAX_PASSWORD_LENGTH
            if (previewFormat == null || length !in GenerationRules.MIN_CODE_LENGTH..GenerationRules.MAX_CODE_LENGTH || !validPrefix || !pwOk) null
            else runCatching {
                if (sequential) VoucherCodeGenerator.generateSequential(1, nextSeq, 1, prefix, length, emptySet(), java.util.Random(1), passwordType, passwordLength).credentials.first()
                else VoucherCodeGenerator.generate(1, previewFormat, prefix, length, random = java.util.Random(1), passwordType = passwordType, passwordLength = passwordLength).first()
            }.getOrNull()
        }
        preview?.let {
            Text(
                stringResource(R.string.gen_preview_label, if (it.username == it.password) it.username else "${it.username} / ${it.password}"),
                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.primary
            )
        }

        generationState.errorMessageResId?.let { FieldError(it) }

        Button(
            onClick = {
                val input = currentInput()
                val violations = GenerationRules.validate(
                    profile?.id.orEmpty(), input.ownerId, input.count, input.formatName, input.prefix, input.codeLength,
                    input.mode, input.passwordType, input.passwordLength, input.comment
                )
                // نفاد نطاق العدّاد: الخطأ ظاهر تحت الحقل؛ لا نبدأ تنفيذاً مآله الفشل
                if (violations.isEmpty() && profile != null && seqOverflow) Unit
                else if (violations.isEmpty() && profile != null) showConfirm = true
                else onStartGeneration(profile ?: Profile(), input) // يعرض أخطاء الحقول عبر الـ VM
            },
            enabled = !generationState.isSubmitting,
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.ButtonHeightLarge),
            shape = RoundedCornerShape(Dimens.RadiusMD)
        ) {
            if (generationState.isSubmitting) CircularProgressIndicator(Modifier.size(Dimens.IconSizeLarge), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text(stringResource(R.string.gen_btn_confirm), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(Dimens.SpaceLG))
    }

    if (showConfirm && profile != null && owner != null) {
        // نفس قاعدة المحرك: سعر خاص نشط للوكيل المختار ← السعر الأساسي (تقدير؛ المحرك يثبّت القيمة النهائية)
        // توليد لمالك = جملة: سعر خاص للمالك ← سعر الجملة في الفئة (نفس منطق المحرك السحابي)
        val quote = ps.mgtik.hotspot.domain.usecase.PricingPolicy.resolveFor(
            ps.mgtik.hotspot.domain.model.SaleContext.WHOLESALE, owner.ownerId,
            generationState.categories.firstOrNull { it.id == profile.categoryId },
            profile.priceMinor, generationState.pricingRules, currencySymbol
        )
        val estimate = quote?.let { ps.mgtik.hotspot.core.util.formatMinor(it.unitMinor * count) } ?: "—"
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.gen_confirm_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                    Text(stringResource(R.string.gen_confirm_message, count, profile.displayNameAr.ifBlank { profile.linkedProfileName }, owner.name))
                    Text(
                        stringResource(
                            R.string.gen_confirm_details,
                            stringResource(if (sequential) R.string.gen_mode_sequential else R.string.gen_mode_random)
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    // ما سيصل فعلاً إلى محرك التنفيذ: البادئة/الطول/كلمة المرور + أول وآخر اسم (تسلسلي) أو مثال (عشوائي)
                    val cfg = currentInput()
                    Text(
                        stringResource(
                            R.string.gen_confirm_config,
                            cfg.prefix.ifBlank { "—" }, cfg.codeLength,
                            stringResource(passwordTypeLabel(passwordType)) + if (passwordType == PasswordType.SAME_AS_USERNAME) "" else " (${cfg.passwordLength})"
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    val first = CodeFormat.parse(formatName)?.let { f ->
                        runCatching { VoucherCodeGenerator.generate(1, f, prefix, length, random = java.util.Random(1)).first().username }.getOrNull()
                    }
                    if (sequential) {
                        val last = (lastUsedSeq + count).toString().padStart(length, '0')
                        Text(
                            stringResource(R.string.gen_confirm_names_seq, prefix + (lastUsedSeq + 1).toString().padStart(length, '0'), prefix + last),
                            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr)
                        )
                    } else if (first != null) {
                        Text(stringResource(R.string.gen_confirm_names_random, first), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                    }
                    Text(stringResource(R.string.gen_confirm_estimate, estimate, currencySymbol), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(onClick = {
                    showConfirm = false
                    onStartGeneration(profile, currentInput())
                }) { Text(stringResource(R.string.gen_btn_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

private fun passwordTypeLabel(t: PasswordType): Int = when (t) {
    PasswordType.NUMERIC_ONLY -> R.string.gen_pw_numeric
    PasswordType.LETTERS_ONLY -> R.string.gen_pw_letters
    PasswordType.ALPHANUMERIC -> R.string.gen_pw_alnum
    PasswordType.MIXED_CASE_ALNUM -> R.string.gen_pw_mixed
    PasswordType.SAME_AS_USERNAME -> R.string.gen_pw_same
}

private fun formatLabel(f: CodeFormat): Int = when (f) {
    CodeFormat.NUMERIC_ONLY -> R.string.gen_format_numeric
    CodeFormat.ALPHANUMERIC -> R.string.gen_format_alnum
    CodeFormat.UPPERCASE_ONLY -> R.string.gen_format_upper
    CodeFormat.USER_PASS_SAME -> R.string.gen_format_user_pass
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

@Composable
private fun FieldError(@androidx.annotation.StringRes res: Int) =
    Text(stringResource(res), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

@Composable
private fun ProfileOption(profile: Profile, selected: Boolean, currencySymbol: String, categories: List<ps.mgtik.hotspot.domain.model.ProfileCategory>, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(Dimens.RadiusMD),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    ) {
        Row(Modifier.padding(Dimens.SpaceMD), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(Dimens.SpaceMD))
            Column(Modifier.weight(1f)) {
                Text(profile.displayNameAr.ifBlank { profile.linkedProfileName }, fontWeight = FontWeight.Bold)
                Text(profile.linkedProfileName, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                categories.firstOrNull { it.id == profile.categoryId }?.wholesalePriceMinor?.let {
                    "${ps.mgtik.hotspot.core.util.formatMinor(it)} $currencySymbol"
                } ?: if (profile.priceMinor > 0L) "${ps.mgtik.hotspot.core.util.formatMinor(profile.priceMinor)} $currencySymbol" else "—",
                style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr),
                fontWeight = FontWeight.Black
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OwnerDropdown(owners: List<OwnerAccount>, selected: OwnerAccount?, isError: Boolean, onSelected: (OwnerAccount) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.let { "${it.name}  ${it.phone}" }.orEmpty(),
            onValueChange = {},
            readOnly = true,
            isError = isError,
            placeholder = { Text(stringResource(R.string.gen_owner_placeholder)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            shape = RoundedCornerShape(Dimens.RadiusMD),
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            owners.forEach { o ->
                DropdownMenuItem(text = { Text("${o.name}  ${o.phone}") }, onClick = { onSelected(o); expanded = false })
            }
        }
    }
}

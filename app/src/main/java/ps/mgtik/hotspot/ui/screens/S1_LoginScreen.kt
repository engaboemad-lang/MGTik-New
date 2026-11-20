package ps.mgtik.hotspot.ui.screens

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import ps.mgtik.hotspot.ui.components.brand.MgtikBrandLogo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.model.AuthCredentials
import ps.mgtik.hotspot.domain.usecase.AuthValidator
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.theme.MgtikTheme
import ps.mgtik.hotspot.ui.util.ErrorTranslator
import ps.mgtik.hotspot.ui.viewmodel.AuthUiState

@StringRes
private fun emailErrorOf(email: String): Int? = when {
    email.isEmpty() -> R.string.login_email_required
    !AuthValidator.isEmailValid(email) -> R.string.login_invalid_email
    else -> null
}

@StringRes
private fun passwordErrorOf(password: String): Int? = when {
    password.isEmpty() -> R.string.login_password_required
    !AuthValidator.isPasswordValid(password) -> R.string.login_password_min_length
    else -> null
}

/**
 * S1 — تسجيل الدخول.
 *
 * - قواعد التحقق من [AuthValidator] (مصدر واحد مع SignInUseCase).
 * - أخطاء الحقول تُحفظ كمعرّفات موارد (لا نصوصاً) فتتبع لغة الجهاز بعد التدوير.
 * - كلمة المرور لا تُحفظ في saved-state عمداً.
 * - [onInputChanged] يُستدعى عند أي تعديل ليُمسح خطأ المصادقة العام.
 * - الانتقال بعد النجاح مسؤولية المخطط (NavGraph) وليس هذه الشاشة.
 */
@Composable
fun S1_LoginScreen(
    state: AuthUiState,
    onLogin: (AuthCredentials) -> Unit,
    modifier: Modifier = Modifier,
    onInputChanged: () -> Unit = {},
    onResetPassword: (String) -> Unit = {},
    onResetDialogClosed: () -> Unit = {}
) {
    var showReset by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var emailError by rememberSaveable { mutableStateOf<Int?>(null) }
    var passwordError by rememberSaveable { mutableStateOf<Int?>(null) }

    val focusManager = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    val globalErrorMessage = ErrorTranslator.translate(state.errorMessage)
    val fieldTextStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)

    fun submit() {
        if (state.isLoading) return
        val cleanEmail = email.trim()
        emailError = emailErrorOf(cleanEmail)
        passwordError = passwordErrorOf(password)
        if (emailError == null && passwordError == null) {
            focusManager.clearFocus()
            onLogin(AuthCredentials(cleanEmail, password))
        }
    }

    // الترويسة داكنة دائماً: أيقونات شريط الحالة فاتحة فوقها حتى في الثيم الفاتح
    DarkHeroSystemBars()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .imePadding()
    ) {
        // منطقة Branding: الشعار الموحّد نفسه (الحجم والنسبة والهالة) المستخدم في شاشة البدء، على تدرج داكن ثابت
        // في الوضعين (الحرف الأبيض في الشعار لا يظهر على خلفية فاتحة، فلا يُلوَّن الشعار ولا يُعدَّل).
        // على الشاشات القصيرة (< 640dp ارتفاعاً) تتقلص المسافات الرأسية كي لا تُدفع الحقول خارج الشاشة.
        val compactHeight = LocalConfiguration.current.screenHeightDp < 640
        val heroVerticalPadding = if (compactHeight) Dimens.SpaceXXL else Dimens.SpaceXXL + Dimens.SpaceLG
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = Dimens.BrandHeroCornerRadius, bottomEnd = Dimens.BrandHeroCornerRadius))
                .background(
                    Brush.verticalGradient(
                        listOf(colorResource(R.color.splash_background), colorResource(R.color.brand_hero_end))
                    )
                )
        ) {
            MgtikBrandLogo(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(
                        start = Dimens.SpaceXXL,
                        end = Dimens.SpaceXXL,
                        top = heroVerticalPadding,
                        // مساحة إضافية أسفل الشعار تُقتطع منها بطاقة الدخول المتداخلة
                        bottom = heroVerticalPadding + Dimens.SpaceXXL
                    )
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .fillMaxWidth()
                .widthIn(max = Dimens.MaxLoginCardWidth)
                // البطاقة تتداخل مع أسفل الترويسة فتبدو الصفحة قطعة واحدة في الوضعين
                .offset(y = -Dimens.SpaceXXL * 2)
                .navigationBarsPadding()
                .padding(Dimens.SpaceXXL),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXL)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = Dimens.MaxLoginCardWidth),
                shape = RoundedCornerShape(Dimens.RadiusLG),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
                elevation = CardDefaults.cardElevation(defaultElevation = Dimens.ElevationLevel1)
            ) {
                Column(
                    modifier = Modifier.padding(Dimens.SpaceXXL),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceLG)
                ) {
                    Text(
                        text = stringResource(R.string.login_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = colors.primary
                    )
                    Text(
                        text = stringResource(R.string.login_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = email,
                        onValueChange = {
                            email = it
                            emailError = null
                            onInputChanged()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.login_email_label)) },
                        leadingIcon = { Icon(Icons.Rounded.Email, contentDescription = null) },
                        isError = emailError != null,
                        supportingText = { emailError?.let { Text(stringResource(it)) } },
                        singleLine = true,
                        enabled = !state.isLoading,
                        textStyle = fieldTextStyle,
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                        shape = RoundedCornerShape(Dimens.RadiusMD)
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            passwordError = null
                            onInputChanged()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.login_password_label)) },
                        leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
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
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        isError = passwordError != null,
                        supportingText = { passwordError?.let { Text(stringResource(it)) } },
                        singleLine = true,
                        enabled = !state.isLoading,
                        textStyle = fieldTextStyle,
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        shape = RoundedCornerShape(Dimens.RadiusMD)
                    )

                    AnimatedVisibility(visible = !globalErrorMessage.isNullOrBlank()) {
                        Surface(
                            color = colors.errorContainer,
                            shape = RoundedCornerShape(Dimens.RadiusSM),
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { liveRegion = LiveRegionMode.Polite }
                        ) {
                            Text(
                                text = globalErrorMessage.orEmpty(),
                                color = colors.onErrorContainer,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(Dimens.SpaceMD)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(Dimens.SpaceSM))

                    Button(
                        onClick = ::submit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Dimens.ButtonHeightLarge),
                        shape = RoundedCornerShape(Dimens.RadiusMD),
                        enabled = !state.isLoading
                    ) {
                        if (state.isLoading) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(Dimens.IconSizeLarge),
                                    color = colors.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(Dimens.SpaceSM))
                                Text(stringResource(R.string.login_button_loading), fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null)
                            Spacer(modifier = Modifier.width(Dimens.SpaceSM))
                            Text(stringResource(R.string.login_button_text), fontWeight = FontWeight.Bold)
                        }
                    }

                    androidx.compose.material3.TextButton(
                        onClick = { showReset = true },
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.login_forgot_password)) }
                }
            }
        }
    }

    if (showReset) {
        ResetPasswordDialog(
            initialEmail = email,
            state = state,
            onSend = onResetPassword,
            onClose = { showReset = false; onResetDialogClosed() }
        )
    }
}

@Composable
private fun DarkHeroSystemBars() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose {
            if (controller != null && previous != null) controller.isAppearanceLightStatusBars = previous
        }
    }
}

@Composable
private fun ResetPasswordDialog(
    initialEmail: String,
    state: AuthUiState,
    onSend: (String) -> Unit,
    onClose: () -> Unit
) {
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    val sent = state.passwordNoticeResId != null
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!state.isPasswordActionLoading) onClose() },
        title = { Text(stringResource(R.string.password_reset_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                if (sent) {
                    Text(stringResource(R.string.password_reset_sent))
                } else {
                    Text(stringResource(R.string.password_reset_hint), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(stringResource(R.string.login_email_label)) },
                        singleLine = true,
                        enabled = !state.isPasswordActionLoading,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                        shape = RoundedCornerShape(Dimens.RadiusMD),
                        modifier = Modifier.fillMaxWidth()
                    )
                    state.passwordErrorResId?.let {
                        Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        confirmButton = {
            if (sent) {
                androidx.compose.material3.TextButton(onClick = onClose) { Text(stringResource(R.string.common_close)) }
            } else {
                androidx.compose.material3.TextButton(
                    onClick = { onSend(email) },
                    enabled = !state.isPasswordActionLoading && AuthValidator.isEmailValid(email.trim())
                ) { Text(stringResource(R.string.password_reset_send)) }
            }
        },
        dismissButton = {
            if (!sent) androidx.compose.material3.TextButton(onClick = onClose, enabled = !state.isPasswordActionLoading) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Preview(name = "Login · فاتح", showSystemUi = true, locale = "ar")
@Composable
private fun LoginLightPreview() {
    MgtikTheme(darkTheme = false) {
        S1_LoginScreen(state = AuthUiState(), onLogin = {})
    }
}

@Preview(name = "Login · داكن + خطأ", showSystemUi = true, locale = "ar")
@Composable
private fun LoginDarkErrorPreview() {
    MgtikTheme(darkTheme = true) {
        S1_LoginScreen(
            state = AuthUiState(errorMessage = "ERR_AUTH_INVALID_CREDENTIALS"),
            onLogin = {}
        )
    }
}

package ps.mgtik.hotspot.ui.screens.splash

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import ps.mgtik.hotspot.BuildConfig
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.ui.components.brand.MgtikBrandLogo
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.theme.MgtikTheme
import ps.mgtik.hotspot.ui.theme.mgtikExtendedColors

/** أقصى مدة انتظار قبل المتابعة حتى لو لم تكتمل الفحوصات (شبكة بطيئة/خطأ سحابي). */
private const val SAFETY_TIMEOUT_MS = 8_000L

/** مدة إبقاء الشاشة بعد اكتمال الخطوات الثلاث ليرى المستخدم حالة «اكتملت». */
private const val COMPLETE_HOLD_MS = 800L

private const val LOGO_ENTER_MS = 800
private const val GLOW_PULSE_MS = 1_400
private const val GLOW_MIN_SCALE = 0.95f
private const val GLOW_MAX_SCALE = 1.05f
private const val GLOW_ALPHA = 0.18f
private const val CURRENT_STEP_BG_ALPHA = 0.15f
private const val BACKGROUND_TAIL_MIX = 0.35f

private enum class StepState { PENDING, ACTIVE, DONE }

private data class SplashStep(
    val icon: ImageVector,
    @StringRes val titleRes: Int,
    val state: StepState
)

/**
 * S0 — شاشة الافتتاح.
 *
 * - مظهرها داكن دائماً ومطابق لشاشة النظام (`Theme.App.Starting`) لتنتقل إليها دون وميض في الوضعين.
 * - تنتهي عند اكتمال الفحوصات الثلاثة أو عند [SAFETY_TIMEOUT_MS]، وتستدعي [onSplashFinished] مرة واحدة.
 * - [onSplashFinished] يُقرأ دائماً بآخر قيمة (rememberUpdatedState) فلا يعمل بحالة قديمة عند انتهاء المؤقّت.
 */
@Composable
fun MgtikSplashScreen(
    isAuthChecked: Boolean,
    isRouterChecked: Boolean,
    isSyncChecked: Boolean,
    onSplashFinished: () -> Unit,
    modifier: Modifier = Modifier,
    /** الراوتر مطلوب فحصه (مسجّل دخول + مُهيّأ + ليس وضع عدم اتصال). */
    routerRequired: Boolean = false,
    /** فشل فحص الراوتر (اتصال/User Manager لا يُعدّ فشلاً هنا بل يُوجَّه إلى S32). */
    routerFailed: Boolean = false,
    failureCode: String? = null,
    onRetry: () -> Unit = {},
    onContinueOffline: () -> Unit = {},
    onOpenRouterSettings: () -> Unit = {}
) {
    val currentOnFinished by rememberUpdatedState(onSplashFinished)
    var timedOut by remember { mutableStateOf(false) }
    // الفشل أو انتهاء المهلة لا يُتجاوزان بصمت: تظهر الخيارات الثلاثة بدل الانتقال التلقائي
    val failed = routerRequired && (routerFailed || (timedOut && !isRouterChecked))
    val allChecked = isAuthChecked && isRouterChecked && isSyncChecked

    LaunchedEffect(allChecked, failed) {
        if (failed) return@LaunchedEffect
        if (allChecked) {
            delay(COMPLETE_HOLD_MS)
            currentOnFinished()
        } else {
            delay(SAFETY_TIMEOUT_MS)
            if (routerRequired && isAuthChecked && !isRouterChecked) timedOut = true else currentOnFinished()
        }
    }

    LightSystemBarIconsWhileVisible()

    val checks = listOf(isAuthChecked, isRouterChecked && !failed, isSyncChecked)
    val activeIndex = checks.indexOfFirst { !it }
    val steps = listOf(
        Icons.Rounded.CloudSync to R.string.splash_step_cloud,
        Icons.Rounded.Router to R.string.splash_step_router,
        Icons.Rounded.Sync to R.string.splash_step_sync
    ).mapIndexed { index, (icon, title) ->
        SplashStep(
            icon = icon,
            titleRes = title,
            state = when {
                checks[index] -> StepState.DONE
                index == activeIndex -> StepState.ACTIVE
                else -> StepState.PENDING
            }
        )
    }

    MgtikTheme(darkTheme = true, layoutDirection = LocalLayoutDirection.current) {
        SplashContent(
            steps = steps,
            failure = if (failed) SplashFailure(failureCode, isTimeout = failureCode == null) else null,
            onRetry = { timedOut = false; onRetry() },
            onContinueOffline = onContinueOffline,
            onOpenRouterSettings = onOpenRouterSettings,
            modifier = modifier
        )
    }
}

private data class SplashFailure(val code: String?, val isTimeout: Boolean)

@Composable
private fun SplashContent(
    steps: List<SplashStep>,
    failure: SplashFailure?,
    onRetry: () -> Unit,
    onContinueOffline: () -> Unit,
    onOpenRouterSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val base = colorResource(R.color.splash_background)

    val logoVisibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    val glowScale by rememberInfiniteTransition(label = "SplashGlow").animateFloat(
        initialValue = GLOW_MIN_SCALE,
        targetValue = GLOW_MAX_SCALE,
        animationSpec = infiniteRepeatable(
            tween(GLOW_PULSE_MS, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "GlowScale"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(base, lerp(base, colors.surfaceVariant, BACKGROUND_TAIL_MIX))))
            .systemBarsPadding()
            .padding(Dimens.SpaceXXL),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // الشعار الموحّد نفسه المستخدم في شاشة الدخول (نفس الحجم والهالة)؛ النبض من هنا
            SplashLogoReveal(visibleState = logoVisibleState) {
                MgtikBrandLogo(glowScale = glowScale)
            }

            Spacer(modifier = Modifier.height(Dimens.SpaceLG))
            Text(
                text = stringResource(id = R.string.splash_app_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(Dimens.SpaceLG))
            Surface(
                shape = RoundedCornerShape(Dimens.RadiusMD),
                color = colors.secondaryContainer.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, colors.outlineVariant)
            ) {
                Text(
                    text = stringResource(id = R.string.splash_version_info, BuildConfig.VERSION_NAME),
                    modifier = Modifier.padding(horizontal = Dimens.SpaceMD, vertical = Dimens.SpaceXS),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSecondaryContainer
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)
        ) {
            steps.forEach { SplashStepRow(it) }
            if (failure != null) {
                Text(
                    text = stringResource(if (failure.isTimeout) R.string.splash_router_timeout else R.string.splash_router_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.error
                )
                failure.code?.let {
                    Text(
                        text = stringResource(ps.mgtik.hotspot.ui.util.RouterErrorTexts.reasonRes(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.router_action_retry))
                }
                OutlinedButton(onClick = onContinueOffline, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.router_action_skip))
                }
                TextButton(onClick = onOpenRouterSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.splash_action_router_settings))
                }
            }
        }
    }
}

@Composable
private fun SplashStepRow(step: SplashStep) {
    val colors = MaterialTheme.colorScheme
    val isDone = step.state == StepState.DONE
    val isActive = step.state == StepState.ACTIVE
    val accent = when {
        isDone -> MaterialTheme.mgtikExtendedColors.success
        isActive -> colors.primary
        else -> colors.outline
    }
    val stateLabel = stringResource(
        when (step.state) {
            StepState.DONE -> R.string.splash_step_state_done
            StepState.ACTIVE -> R.string.splash_step_state_active
            StepState.PENDING -> R.string.splash_step_state_pending
        }
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.RadiusMD))
            .background(if (isActive) colors.primaryContainer.copy(alpha = CURRENT_STEP_BG_ALPHA) else Color.Transparent)
            .padding(horizontal = Dimens.SpaceLG, vertical = Dimens.SpaceMD)
            .semantics(mergeDescendants = true) { stateDescription = stateLabel },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isDone) Icons.Rounded.CheckCircle else step.icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(Dimens.IconSizeMedium)
        )
        Spacer(modifier = Modifier.width(Dimens.SpaceLG))
        Text(
            text = stringResource(id = step.titleRes),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isDone || isActive) colors.onSurface else colors.onSurfaceVariant,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/**
 * الخلفية داكنة دائماً؛ فنجعل أيقونات شريط الحالة/التنقل فاتحة أثناء ظهور السبلاش
 * ثم نعيد الوضع السابق عند مغادرتها (وإلا اختفت الأيقونات في وضع النظام الفاتح).
 */
@Composable
private fun LightSystemBarIconsWhileVisible() {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (controller != null) {
                previousStatus?.let { controller.isAppearanceLightStatusBars = it }
                previousNavigation?.let { controller.isAppearanceLightNavigationBars = it }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Preview(name = "Splash · جارٍ التحميل", showSystemUi = true, locale = "ar")
@Composable
private fun SplashLoadingPreview() {
    MgtikTheme {
        MgtikSplashScreen(
            isAuthChecked = true,
            isRouterChecked = false,
            isSyncChecked = false,
            onSplashFinished = {}
        )
    }
}

@Preview(name = "Splash · مكتمل", showSystemUi = true, locale = "ar")
@Composable
private fun SplashCompletePreview() {
    MgtikTheme {
        MgtikSplashScreen(
            isAuthChecked = true,
            isRouterChecked = true,
            isSyncChecked = true,
            onSplashFinished = {}
        )
    }
}

/**
 * دالة مستقلة بلا مستقبِل ضمني: استدعاء AnimatedVisibility(visibleState=…) مباشرة داخل Box متداخل في Column
 * كان يُحَلّ إلى ColumnScope.AnimatedVisibility فيُرفض (حماية DslMarker)، وبقية أخطاء @Composable كانت أثراً لذلك.
 */
@androidx.compose.runtime.Composable
private fun SplashLogoReveal(
    visibleState: androidx.compose.animation.core.MutableTransitionState<Boolean>,
    content: @androidx.compose.runtime.Composable androidx.compose.animation.AnimatedVisibilityScope.() -> Unit
) {
    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(tween(LOGO_ENTER_MS)) +
            scaleIn(tween(LOGO_ENTER_MS, easing = FastOutSlowInEasing)),
        content = content
    )
}

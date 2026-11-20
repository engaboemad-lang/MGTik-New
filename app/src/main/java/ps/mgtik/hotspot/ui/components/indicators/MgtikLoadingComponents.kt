package ps.mgtik.hotspot.ui.components.indicators

import androidx.compose.ui.res.stringResource
import ps.mgtik.hotspot.R
import androidx.compose.animation.AnimatedVisibility
import ps.mgtik.hotspot.ui.theme.Dimens
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ps.mgtik.hotspot.ui.theme.MgtikTheme

/**
 * 1. Shimmer Effect Modifier for Skeleton Loaders
 * يوفر تأثير لمعان متدرج ناعم لهياكل التحميل (Skeleton UI)
 */
fun Modifier.mgtikShimmer(
    durationMillis: Int = 1200,
    colors: List<Color>? = null
): Modifier = composed {
    // ألوان الهيكل تُشتق من الثيم (كانت كحلية ثابتة فتظهر داكنة على الخلفية الفاتحة)
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val shimmerColors = colors ?: listOf(
        base.copy(alpha = 0.9f),
        base.copy(alpha = 0.45f),
        base.copy(alpha = 0.9f)
    )
    val transition = rememberInfiniteTransition(label = "ShimmerTransition")
    val translateAnim by transition.animateFloat(
        initialValue = -1000f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ShimmerTranslate"
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(x = translateAnim - 300f, y = translateAnim - 300f),
        end = Offset(x = translateAnim + 300f, y = translateAnim + 300f)
    )

    this.background(brush = brush)
}

/**
 * 2. Pulse Loading Indicator (Radial Constellation Glow)
 * مؤشر نبض راداري متوهج بألوان العلامة التجارية Cyan / Teal
 */
@Composable
fun MgtikPulseLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    accentColor: Color = MgtikTheme.extendedColors.info
) {
    val infiniteTransition = rememberInfiniteTransition(label = "PulseTransition")

    val pulseScale1 by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Pulse1"
    )
    val pulseAlpha1 by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Alpha1"
    )

    val pulseScale2 by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, delayMillis = 400, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Pulse2"
    )
    val pulseAlpha2 by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, delayMillis = 400, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Alpha2"
    )

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Rotation"
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // حلقة التموج الخارجية الأولى
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(pulseScale1)
                .clip(CircleShape)
                .border(2.dp, accentColor.copy(alpha = pulseAlpha1), CircleShape)
        )

        // حلقة التموج الداخلية الثانية
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(pulseScale2)
                .clip(CircleShape)
                .border(1.5.dp, primaryColor.copy(alpha = pulseAlpha2), CircleShape)
        )

        // النواة المركزية الدوارة مع أيقونة المزامنة
        Box(
            modifier = Modifier
                .size(size * 0.58f)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(primaryColor.copy(alpha = 0.35f), Color.Transparent)
                    )
                )
                .border(1.5.dp, primaryColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Sync,
                contentDescription = stringResource(R.string.ui_loading),
                tint = primaryColor,
                modifier = Modifier
                    .size(size * 0.34f)
                    .rotate(rotation)
            )
        }
    }
}

/**
 * 3. Modern Linear Progress Bar with Animated Gradient & Percentage
 * شريط تقدم خطي احترافي يدعم الحساب المئوي والسرعة وحالات الإنجاز
 */
@Composable
fun MgtikLinearProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
    showPercentage: Boolean = true,
    statusText: String? = null,
    gradientColors: List<Color> = listOf(
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.primary,
        MgtikTheme.extendedColors.info
    )
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "LinearProgress"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        if (showPercentage || statusText != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                statusText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
                if (showPercentage) {
                    Text(
                        text = "${(animatedProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(height / 2))
                    .background(Brush.horizontalGradient(gradientColors))
            )
        }
    }
}

/**
 * 4. Step Processing Item Model
 */
data class ProcessingStep(
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector = Icons.Rounded.CheckCircle
)

/**
 * 5. Full Processing Dialog Modal (Long-Running Asynchronous Operations)
 * حوار معالجة شامل للعمليات غير المتزامنة (توليد الكروت، مزامنة راوتر MikroTik، تأكيد الاشتراكات)
 */
@Composable
fun MgtikProcessingDialog(
    title: String,
    currentStepText: String,
    progress: Float? = null,
    steps: List<ProcessingStep> = emptyList(),
    currentStepIndex: Int = 0,
    isCancellable: Boolean = false,
    onCancel: () -> Unit = {},
    onDismissRequest: () -> Unit = {}
) {
    Dialog(
        onDismissRequest = {
            if (isCancellable) onDismissRequest()
        },
        properties = DialogProperties(
            dismissOnBackPress = isCancellable,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .clip(RoundedCornerShape(Dimens.RadiusPill)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dimens.SpaceXXL),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // الشعار النبضي المركزي
                MgtikPulseLoadingIndicator(size = 80.dp)

                // العنوان وحالة المعالجة الحالية
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = currentStepText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        ),
                        textAlign = TextAlign.Center
                    )
                }

                // شريط التقدم إذا كان محددًا
                if (progress != null) {
                    MgtikLinearProgressBar(
                        progress = progress,
                        showPercentage = true,
                        modifier = Modifier.padding(horizontal = Dimens.SpaceXS)
                    )
                }

                // قائمة الخطوات التسلسلية
                if (steps.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Dimens.RadiusLG))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(Dimens.SpaceMD),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        steps.forEachIndexed { index, step ->
                            val isCompleted = index < currentStepIndex
                            val isCurrent = index == currentStepIndex
                            val stepColor = when {
                                isCompleted -> MgtikTheme.extendedColors.success
                                isCurrent -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(stepColor.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isCompleted) {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            tint = stepColor,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    } else if (isCurrent) {
                                        MgtikProgressIndicator.Circular(
                                            size = 14.dp,
                                            strokeWidth = 2.dp,
                                            primaryColor = stepColor,
                                            accentColor = stepColor
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(stepColor)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(Dimens.SpaceMD))

                                Column {
                                    Text(
                                        text = step.title,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontWeight = if (isCurrent || isCompleted) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (isCurrent || isCompleted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    )
                                    step.subtitle?.let {
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // زر الإلغاء إذا كانت العملية قابلة للإلغاء
                if (isCancellable) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Dimens.RadiusMD)
                    ) {
                        Text(stringResource(R.string.ui_cancel_operation))
                    }
                }
            }
        }
    }
}

/**
 * 6. Inline Button Loading Progress Spinner
 * مؤشر تحميل مدمج داخل الأزرار التفاعلية
 */
@Composable
fun MgtikButtonLoadingContent(
    text: String,
    modifier: Modifier = Modifier,
    indicatorColor: Color = MaterialTheme.colorScheme.onPrimary
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MgtikProgressIndicator.Circular(
            size = 18.dp,
            strokeWidth = 2.2.dp,
            primaryColor = indicatorColor,
            accentColor = indicatorColor.copy(alpha = 0.7f),
            trackColor = indicatorColor.copy(alpha = 0.2f)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/**
 * 7. Skeleton Placeholders for Content Loading
 */
@Composable
fun MgtikCardSkeleton(
    modifier: Modifier = Modifier,
    height: Dp = 100.dp
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        shape = RoundedCornerShape(Dimens.RadiusLG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Dimens.SpaceLG),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(120.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(Dimens.RadiusXS))
                        .mgtikShimmer()
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .mgtikShimmer()
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(Dimens.RadiusXS))
                    .mgtikShimmer()
            )
        }
    }
}

@Composable
fun MgtikRowSkeleton(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceSM),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .mgtikShimmer()
        )
        Spacer(modifier = Modifier.width(Dimens.SpaceMD))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(Dimens.RadiusXS))
                    .mgtikShimmer()
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(Dimens.RadiusXS))
                    .mgtikShimmer()
            )
        }
        Box(
            modifier = Modifier
                .width(48.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(Dimens.RadiusSM))
                .mgtikShimmer()
        )
    }
}

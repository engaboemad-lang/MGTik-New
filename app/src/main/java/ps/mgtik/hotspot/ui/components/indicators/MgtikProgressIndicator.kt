package ps.mgtik.hotspot.ui.components.indicators

import androidx.compose.ui.res.stringResource
import ps.mgtik.hotspot.R
import androidx.compose.animation.core.*
import ps.mgtik.hotspot.ui.theme.Dimens
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import ps.mgtik.hotspot.ui.theme.MgtikTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * مكون مؤشر التقدم المعتمد لهوية MGTik (MgtikProgressIndicator)
 * يوفر مؤشرات تحميل احترافية (دائرية، خطية، حاويات تحميل كاملة، ومؤشرات للأزرار)
 * لجميع العمليات غير المتزامنة ومزامنة الراوتر والسحابة.
 */
object MgtikProgressIndicator {

    /**
     * 1. Circular Spinner (مؤشر دائري متدرج ونبضي)
     * يدعم الحالتين: غير محدد (Indeterminate) ومحدد بنسبة تقدم (Determinate)
     */
    @Composable
    fun Circular(
        modifier: Modifier = Modifier,
        progress: Float? = null,
        size: Dp = 40.dp,
        strokeWidth: Dp = 3.5.dp,
        primaryColor: Color = MaterialTheme.colorScheme.primary,
        accentColor: Color = MgtikTheme.extendedColors.info,
        trackColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        showPercentage: Boolean = false
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "MgtikCircularSpinner")

        val rotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "SpinnerRotation"
        )

        val sweepAngle by infiniteTransition.animateFloat(
            initialValue = 30f,
            targetValue = 280f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "SpinnerSweep"
        )

        val animatedProgress by animateFloatAsState(
            targetValue = (progress ?: 0f).coerceIn(0f, 1f),
            animationSpec = tween(350, easing = FastOutSlowInEasing),
            label = "DeterminateProgress"
        )

        Box(
            modifier = modifier.size(size),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokePx = strokeWidth.toPx()
                val radius = (this.size.minDimension - strokePx) / 2f
                val topLeft = Offset(
                    (this.size.width - radius * 2) / 2f,
                    (this.size.height - radius * 2) / 2f
                )
                val arcSize = Size(radius * 2, radius * 2)

                // 1. مسار الخلفية الدائرية (Track)
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )

                // 2. القوس المتدرج المتحرك
                val gradientBrush = Brush.sweepGradient(
                    colors = listOf(primaryColor.copy(alpha = 0.2f), primaryColor, accentColor)
                )

                if (progress != null) {
                    // Determinate mode
                    drawArc(
                        brush = gradientBrush,
                        startAngle = -90f,
                        sweepAngle = animatedProgress * 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                } else {
                    // Indeterminate mode
                    drawArc(
                        brush = gradientBrush,
                        startAngle = rotation,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                }
            }

            // عرض النسبة المئوية في المركز إذا كان الوضع محدد الحجم
            if (progress != null && showPercentage && size >= 48.dp) {
                Text(
                    text = "${(animatedProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = (size.value * 0.25f).sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
    }

    /**
     * 2. Linear Progress Bar (شريط تقدم خطي متدرج)
     */
    @Composable
    fun Linear(
        modifier: Modifier = Modifier,
        progress: Float? = null,
        height: Dp = 8.dp,
        primaryColor: Color = MaterialTheme.colorScheme.primary,
        accentColor: Color = MgtikTheme.extendedColors.info,
        trackColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        statusText: String? = null,
        showPercentage: Boolean = false
    ) {
        val animatedProgress by animateFloatAsState(
            targetValue = (progress ?: 0f).coerceIn(0f, 1f),
            animationSpec = tween(350, easing = FastOutSlowInEasing),
            label = "LinearProgressAnim"
        )

        val infiniteTransition = rememberInfiniteTransition(label = "IndeterminateLinear")
        val shimmerOffset by infiniteTransition.animateFloat(
            initialValue = -1f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "ShimmerOffset"
        )

        Column(modifier = modifier.fillMaxWidth()) {
            if (statusText != null || (progress != null && showPercentage)) {
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                    if (progress != null && showPercentage) {
                        Text(
                            text = "${(animatedProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = primaryColor,
                                fontWeight = FontWeight.Bold
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
                    .background(trackColor)
            ) {
                if (progress != null) {
                    // Determinate Linear Bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedProgress)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(height / 2))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(primaryColor, accentColor)
                                )
                            )
                    )
                } else {
                    // Indeterminate Shimmer Beam
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.4f)
                            .fillMaxHeight()
                            .offset(x = (shimmerOffset * 240).dp)
                            .clip(RoundedCornerShape(height / 2))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        primaryColor,
                                        accentColor,
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                }
            }
        }
    }

    /**
     * 3. Loading Container Box (حاوية تحميل كاملة مع نص وصفي للشاشة)
     */
    @Composable
    fun LoadingBox(
        modifier: Modifier = Modifier,
        message: String = stringResource(R.string.ui_loading_sync_message),
        subMessage: String? = null
    ) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(Dimens.SpaceXXL)
            ) {
                MgtikPulseLoadingIndicator(size = 72.dp)

                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    textAlign = TextAlign.Center
                )

                subMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    /**
     * 4. Button Inline Loading Progress Indicator
     */
    @Composable
    fun ButtonLoading(
        text: String,
        modifier: Modifier = Modifier,
        indicatorColor: Color = MaterialTheme.colorScheme.onPrimary
    ) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Circular(
                size = 18.dp,
                strokeWidth = 2.dp,
                primaryColor = indicatorColor,
                accentColor = indicatorColor.copy(alpha = 0.7f),
                trackColor = indicatorColor.copy(alpha = 0.2f)
            )
            Spacer(modifier = Modifier.width(Dimens.SpaceSM))
            Text(
                text = text,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

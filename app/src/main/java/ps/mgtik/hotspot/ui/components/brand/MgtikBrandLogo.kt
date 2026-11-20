package ps.mgtik.hotspot.ui.components.brand

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.ui.theme.Dimens

/** شفافية هالة الشعار (ثابتة في كل الشاشات كي يبدو الشعار هو نفسه عند الانتقال من البدء إلى الدخول). */
private const val BRAND_GLOW_ALPHA = 0.18f
private const val BRAND_TAGLINE_ALPHA = 0.7f

/**
 * شعار MGTik الموحّد: مصدر واحد للحجم والنسبة والهالة والسطر التعريفي في شاشة البدء (S0) والدخول (S1).
 *
 * - الصورة `logo_lockup` هي الشعار الأصلي بعد حذف السطر الإنجليزي منه وقصّ الهوامش؛ السطر نفسه يُرسَم هنا
 *   نصاً قابلاً للقراءة بدل أن يُصغَّر داخل الصورة (كان بحجم ~7dp وغير مقروء).
 * - يُرسم على خلفية داكنة دائماً (الحرف الأبيض لا يظهر على خلفية فاتحة).
 * - الهالة تُرسم خلف المحتوى (drawBehind) فلا تزيد ارتفاع الشعار في التخطيط؛ [glowScale] يحرّكها من الخارج (نبض S0).
 */
@Composable
fun MgtikBrandLogo(
    modifier: Modifier = Modifier,
    glowScale: Float = 1f
) {
    val glow = colorResource(R.color.splash_brand_blue)
    val glowRadiusPx = with(LocalDensity.current) { (Dimens.BrandGlowSize / 2).toPx() }
    Column(
        modifier = modifier.drawBehind {
            val radius = glowRadiusPx * glowScale
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glow.copy(alpha = BRAND_GLOW_ALPHA), Color.Transparent),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.logo_lockup),
            contentDescription = stringResource(R.string.splash_logo_desc),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth(Dimens.BrandLogoWidthFraction)
                .widthIn(max = Dimens.BrandLogoMaxWidth)
                .aspectRatio(Dimens.LogoAspectRatio)
        )
        Spacer(Modifier.height(Dimens.SpaceSM))
        Text(
            text = stringResource(R.string.brand_tagline),
            style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr),
            color = Color.White.copy(alpha = BRAND_TAGLINE_ALPHA),
            textAlign = TextAlign.Center
        )
    }
}

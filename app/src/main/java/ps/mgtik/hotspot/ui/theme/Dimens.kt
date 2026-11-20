package ps.mgtik.hotspot.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * MGTik Design System Spatial & Elevation Tokens.
 * 8-point rhythmic grid system mirroring the React Tailwind metrics.
 */
object Dimens {
    // Spacing scale
    val SpaceNone: Dp = 0.dp
    val SpaceXXS: Dp = 2.dp
    val SpaceXS: Dp = 4.dp
    val SpaceSM: Dp = 8.dp
    val SpaceMD: Dp = 12.dp
    val SpaceLG: Dp = 16.dp
    val SpaceXL: Dp = 20.dp
    val SpaceXXL: Dp = 24.dp
    val Space3XL: Dp = 32.dp

    // Screen padding
    val ScreenHorizontalPadding: Dp = 16.dp
    val ScreenVerticalPadding: Dp = 16.dp
    val SectionSpacing: Dp = 16.dp
    val ItemSpacing: Dp = 12.dp

    // Card & Container Radii
    val RadiusXS: Dp = 4.dp
    val RadiusSM: Dp = 8.dp
    val RadiusMD: Dp = 12.dp
    val RadiusLG: Dp = 16.dp
    val RadiusPill: Dp = 24.dp
    val RadiusFull: Dp = 9999.dp

    // Elevation (Subtle M3 Elevation)
    val ElevationLevel0: Dp = 0.dp
    val ElevationLevel1: Dp = 1.dp
    val ElevationLevel2: Dp = 2.dp
    val ElevationLevel3: Dp = 4.dp

    // Touch targets & Heights
    val MinTouchTarget: Dp = 48.dp
    val ButtonHeight: Dp = 40.dp
    val ButtonHeightLarge: Dp = 54.dp
    val ChipHeight: Dp = 32.dp
    val IconSizeSmall: Dp = 16.dp
    val IconSizeMedium: Dp = 20.dp
    val IconSizeLarge: Dp = 24.dp
    
    // Login Screen specific
    val MaxLoginCardWidth: Dp = 500.dp
    /** أقصى عرض لمحتوى نماذج/نتائج التوليد: يبقى مقروءاً على الأجهزة اللوحية بدل التمدد الكامل. */
    val MaxFormWidth: Dp = 640.dp

    // الشعار الموحّد (S0 + S1): حجم واحد ونسبة واحدة وهالة واحدة
    val BrandLogoMaxWidth: Dp = 280.dp
    const val BrandLogoWidthFraction: Float = 0.72f
    val BrandGlowSize: Dp = 300.dp
    val BrandHeroCornerRadius: Dp = 32.dp

    // نسبة عرض/ارتفاع drawable/logo_lockup.png (1396×448)
    const val LogoAspectRatio: Float = 1396f / 448f
}

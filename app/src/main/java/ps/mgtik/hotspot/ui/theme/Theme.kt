package ps.mgtik.hotspot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * نظام ألوان النمط الداكن (Dark Color Scheme) المعتمد في MGTik.
 * عائلة محايدات Slate/Navy موحّدة مع النمط الفاتح، مع تدرجات Cyan/Teal و Emerald و Amber.
 * يحدد بدقة كافة أدوار Material 3:
 * - primary: أزرق شعار MGTik (مُفتَّح للخلفيات الداكنة) للعلامة والتفاعل الأساسي؛ secondary: سماوي مساعد
 * - background: كحلي داكن عميق (Slate-navy)
 * - surface: كحلي أفتح قليلاً للبطاقات والحاويات المرتفعة
 * - error: أحمر تحذيري فاقع وواضح للحالات الحرجة وفشل الاتصال
 */
val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = BluePrimaryDark,
    onPrimary = BlueOnPrimaryDark,
    primaryContainer = BlueContainerDark,
    onPrimaryContainer = BlueOnContainerDark,
    inversePrimary = InversePrimaryDark,

    secondary = CyanSecondaryDark,
    onSecondary = CyanOnSecondaryDark,
    secondaryContainer = CyanSecondaryContainerDark,
    onSecondaryContainer = CyanOnSecondaryContainerDark,

    tertiary = AmberTertiaryDark,
    onTertiary = AmberOnTertiaryDark,
    tertiaryContainer = AmberContainerDark,
    onTertiaryContainer = AmberOnTertiaryContainerDark,

    background = BackgroundDark,
    onBackground = OnBackgroundDark,

    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,

    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    surfaceDim = SurfaceDimDark,
    surfaceBright = SurfaceBrightDark,

    inverseSurface = InverseSurfaceDark,
    inverseOnSurface = InverseOnSurfaceDark,

    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    scrim = ScrimDark,

    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark
)

/**
 * نظام ألوان النمط الفاتح (Light Color Scheme) المعتمد في MGTik.
 * عالي التباين والوضوح الميداني بتدرجات Slate / Cyan / Emerald.
 * يحدد بدقة كافة أدوار Material 3:
 * - primary: أزرق شعار MGTik بتباين عالٍ على الخلفيات الفاتحة (≥5.7:1)؛ secondary: سماوي مساعد
 * - background: رمادي ناصع مريح للعين (Slate 50)
 * - surface: أبيض نقي للبطاقات والبطاقات التفاعلية (Pure White)
 * - error: أحمر عميق للرسائل التنبيهية
 */
val LightColorScheme: ColorScheme = lightColorScheme(
    primary = BluePrimaryLight,
    onPrimary = BlueOnPrimaryLight,
    primaryContainer = BlueContainerLight,
    onPrimaryContainer = BlueOnContainerLight,
    inversePrimary = InversePrimaryLight,

    secondary = CyanSecondaryLight,
    onSecondary = CyanOnSecondaryLight,
    secondaryContainer = CyanSecondaryContainerLight,
    onSecondaryContainer = CyanOnSecondaryContainerLight,

    tertiary = AmberTertiaryLight,
    onTertiary = AmberOnTertiaryLight,
    tertiaryContainer = AmberContainerLight,
    onTertiaryContainer = AmberOnTertiaryContainerLight,

    background = BackgroundLight,
    onBackground = OnBackgroundLight,

    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,

    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
    surfaceDim = SurfaceDimLight,
    surfaceBright = SurfaceBrightLight,

    inverseSurface = InverseSurfaceLight,
    inverseOnSurface = InverseOnSurfaceLight,

    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    scrim = ScrimLight,

    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight
)

/**
 * المزود الموحد للمظهر (Unified Theme Provider) لتطبيق MGTik Android Native (Jetpack Compose).
 * يوفر دعمًا مدمجًا لـ:
 * 1. Material 3 ColorScheme للوضعين الفاتح والداكن (Light & Dark Mode)
 * 2. ألوان الحالات والشبكة الممتدة (Extended Semantic Status Palette)
 * 3. التوجيه التلقائي RTL لدعم اللغة العربية بشكل كامل
 * 4. منظومة الخطوط والأشكال (Typography & Shapes)
 */
@Composable
fun MgtikTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // الاتجاه يتبع لغة التطبيق المختارة (RTL للعربية، LTR للإنجليزية) بدل تثبيته على RTL
    layoutDirection: LayoutDirection =
        if (androidx.compose.ui.platform.LocalConfiguration.current.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL)
            LayoutDirection.Rtl else LayoutDirection.Ltr,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(
        LocalLayoutDirection provides layoutDirection,
        LocalMgtikExtendedColors provides extendedColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content
        )
    }
}

/**
 * كائن موحد للوصول المباشر إلى خصائص المظهر (MgtikTheme Unified Object Accessor).
 */
object MgtikTheme {
    val colorScheme: ColorScheme
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme

    val typography: Typography
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography

    val shapes: Shapes
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.shapes

    val extendedColors: MgtikExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalMgtikExtendedColors.current
}

/**
 * وصول مباشر عبر ملحقات MaterialTheme إلى لوحة ألوان MGTik الممتدة.
 */
val MaterialTheme.mgtikExtendedColors: MgtikExtendedColors
    @Composable
    @ReadOnlyComposable
    get() = LocalMgtikExtendedColors.current

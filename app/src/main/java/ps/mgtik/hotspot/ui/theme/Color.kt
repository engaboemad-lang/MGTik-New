package ps.mgtik.hotspot.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ==========================================
// MGTik Material 3 Design System Color Tokens
// ==========================================

// --- Light Theme Tokens ---
// الأساسي = أزرق شعار MGTik (~#0063FB)؛ السماوي لون مساعد (secondary)
val BluePrimaryLight = Color(0xFF0057E7)
val BlueOnPrimaryLight = Color(0xFFFFFFFF)
val BlueContainerLight = Color(0xFFD9E6FF)
val BlueOnContainerLight = Color(0xFF001A41)

val CyanSecondaryLight = Color(0xFF00677F)
val CyanOnSecondaryLight = Color(0xFFFFFFFF)
val CyanSecondaryContainerLight = Color(0xFFB7ECFF)
val CyanOnSecondaryContainerLight = Color(0xFF001F28)

val AmberTertiaryLight = Color(0xFF7B5800)
val AmberOnTertiaryLight = Color(0xFFFFFFFF)
val AmberContainerLight = Color(0xFFFFDEA1)
val AmberOnTertiaryContainerLight = Color(0xFF261900)

val BackgroundLight = Color(0xFFF8FAFC) // Slate 50
val OnBackgroundLight = Color(0xFF0F172A) // Slate 900
val SurfaceLight = Color(0xFFFFFFFF) // Pure White
val OnSurfaceLight = Color(0xFF0F172A) // Slate 900
val SurfaceVariantLight = Color(0xFFF1F5F9) // Slate 100
val OnSurfaceVariantLight = Color(0xFF475569) // Slate 600

// Material 3 Surface Containers (Light Mode)
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFF8FAFC)
val SurfaceContainerLight = Color(0xFFF1F5F9)
val SurfaceContainerHighLight = Color(0xFFE2E8F0)
val SurfaceContainerHighestLight = Color(0xFFCBD5E1)
val SurfaceDimLight = Color(0xFFE2E8F0)
val SurfaceBrightLight = Color(0xFFFFFFFF)

val InverseSurfaceLight = Color(0xFF1E293B)
val InverseOnSurfaceLight = Color(0xFFF8FAFC)
val InversePrimaryLight = Color(0xFF5B9BFF)

val OutlineLight = Color(0xFF94A3B8) // Slate 400
val OutlineVariantLight = Color(0xFFE2E8F0) // Slate 200
val ScrimLight = Color(0x66000000)

val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)

// --- Dark Theme Tokens ---
val BluePrimaryDark = Color(0xFF5B9BFF) // أزرق الشعار مُفتَّح للخلفيات الداكنة
val BlueOnPrimaryDark = Color(0xFF001A41)
val BlueContainerDark = Color(0xFF0A3FA8)
val BlueOnContainerDark = Color(0xFFD9E6FF)

val CyanSecondaryDark = Color(0xFF38BDF8) // Sky 400
val CyanOnSecondaryDark = Color(0xFF003544)
val CyanSecondaryContainerDark = Color(0xFF004D5F)
val CyanOnSecondaryContainerDark = Color(0xFFB7ECFF)

val AmberTertiaryDark = Color(0xFFFBBF24) // Amber 400
val AmberOnTertiaryDark = Color(0xFF422006)
val AmberContainerDark = Color(0xFF78350F) // Amber 900
val AmberOnTertiaryContainerDark = Color(0xFFFEF3C7)

val BackgroundDark = Color(0xFF0B1220) // Slate-navy
val OnBackgroundDark = Color(0xFFF1F5F9) // Slate 100
val SurfaceDark = Color(0xFF111A2B)
val OnSurfaceDark = Color(0xFFF1F5F9) // Slate 100
val SurfaceVariantDark = Color(0xFF1B2638)
val OnSurfaceVariantDark = Color(0xFFA8B4C6)

// Material 3 Surface Containers (Dark Mode)
val SurfaceContainerLowestDark = Color(0xFF080E1A)
val SurfaceContainerLowDark = Color(0xFF0F1729)
val SurfaceContainerDark = Color(0xFF111A2B)
val SurfaceContainerHighDark = Color(0xFF1B2638)
val SurfaceContainerHighestDark = Color(0xFF273449)
val SurfaceDimDark = Color(0xFF0B1220)
val SurfaceBrightDark = Color(0xFF273449)

val InverseSurfaceDark = Color(0xFFF1F5F9)
val InverseOnSurfaceDark = Color(0xFF111A2B)
val InversePrimaryDark = Color(0xFF0057E7)

val OutlineDark = Color(0xFF64748B) // Slate 500
val OutlineVariantDark = Color(0xFF273449)
val ScrimDark = Color(0x99000000)

val ErrorDark = Color(0xFFEF4444) // Rose 500
val OnErrorDark = Color(0xFF450A0A)
val ErrorContainerDark = Color(0xFF7F1D1D)
val OnErrorContainerDark = Color(0xFFFEE2E2)

// ==========================================
// Extended Status Semantic Colors
// ==========================================

@Immutable
data class MgtikExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val offline: Color,
    val onOffline: Color,
    val offlineContainer: Color
)

val DarkExtendedColors = MgtikExtendedColors(
    success = Color(0xFF10B981), // Emerald 500
    onSuccess = Color(0xFF022C22),
    successContainer = Color(0xFF064E3B), // Emerald 900
    onSuccessContainer = Color(0xFFA7F3D0),
    warning = Color(0xFFF59E0B), // Amber 500
    onWarning = Color(0xFF451A03),
    warningContainer = Color(0xFF78350F), // Amber 900
    onWarningContainer = Color(0xFFFDE68A),
    info = Color(0xFF38BDF8), // Sky 400
    onInfo = Color(0xFF082F49),
    infoContainer = Color(0xFF075985), // Sky 800
    onInfoContainer = Color(0xFFBAE6FD),
    offline = Color(0xFF94A3B8), // Slate 400
    onOffline = Color(0xFF0B1220),
    offlineContainer = Color(0xFF1B2638)
)

val LightExtendedColors = MgtikExtendedColors(
    success = Color(0xFF059669), // Emerald 600
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFD1FAE5),
    onSuccessContainer = Color(0xFF065F46),
    warning = Color(0xFFD97706), // Amber 600
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFEF3C7),
    onWarningContainer = Color(0xFF92400E),
    info = Color(0xFF0284C7), // Sky 600
    onInfo = Color(0xFFFFFFFF),
    infoContainer = Color(0xFFE0F2FE),
    onInfoContainer = Color(0xFF0369A1),
    offline = Color(0xFF64748B), // Slate 500
    onOffline = Color(0xFFFFFFFF),
    offlineContainer = Color(0xFFF1F5F9)
)

val LocalMgtikExtendedColors = staticCompositionLocalOf { DarkExtendedColors }


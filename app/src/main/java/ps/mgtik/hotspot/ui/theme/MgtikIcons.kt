package ps.mgtik.hotspot.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R

/**
 * MGTikIcons - سجل الأيقونات الموحد لموديول الـ UI.
 * تم تصحيح الـ import لـ R ليكون محلياً للموديول.
 */
object MgtikIcons {

    // --- Core Branding ---
    @DrawableRes val Logo = R.drawable.logo
    @DrawableRes val Symbol = R.drawable.ic_mgtik_symbol

    // --- Domain & Network Management ---
    @DrawableRes val Router = R.drawable.ic_router_mikrotik
    @DrawableRes val Voucher = R.drawable.ic_voucher_card
    @DrawableRes val HotspotActive = R.drawable.ic_hotspot_active
    @DrawableRes val SpeedProfile = R.drawable.ic_speed_profile
    @DrawableRes val BillingWallet = R.drawable.ic_billing_wallet
    @DrawableRes val OwnerDirectory = R.drawable.ic_owner_directory
    @DrawableRes val SyncCloud = R.drawable.ic_sync_cloud
    @DrawableRes val ConflictWarning = R.drawable.ic_conflict_warning
    @DrawableRes val OfflineStatus = R.drawable.ic_offline_status
    @DrawableRes val GeneratorMagic = R.drawable.ic_generator_magic
    @DrawableRes val QuickSearch = R.drawable.ic_quick_search
    @DrawableRes val NotificationStat = R.drawable.ic_stat_connected

    @Composable
    fun MgtikIcon(
        @DrawableRes resId: Int,
        contentDescription: String?,
        modifier: Modifier = Modifier,
        size: Dp = 24.dp,
        tint: Color = LocalContentColor.current
    ) {
        Icon(
            painter = painterResource(id = resId),
            contentDescription = contentDescription,
            modifier = modifier.size(size),
            tint = tint
        )
    }
}

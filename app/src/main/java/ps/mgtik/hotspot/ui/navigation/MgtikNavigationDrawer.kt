package ps.mgtik.hotspot.ui.navigation

import androidx.annotation.StringRes
import ps.mgtik.hotspot.ui.theme.Dimens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.ui.components.session.LocalSessionActions
import ps.mgtik.hotspot.ui.components.session.canLogout
import ps.mgtik.hotspot.ui.components.session.icon
import ps.mgtik.hotspot.ui.components.session.labelRes
import ps.mgtik.hotspot.ui.viewmodel.SessionAction

/**
 * مضيف القائمة الجانبية لشاشات التطبيق: [open] يفتحها، و[isRoot] = الشاشة وجهة جذرية (شريط سفلي) فيحلّ زر القائمة
 * محل زر الرجوع في شريطها العلوي. null = القائمة غير متاحة (Splash/Login/إعداد الراوتر الأول).
 */
class DrawerHost(val open: () -> Unit, val isRoot: Boolean)

val LocalDrawerHost = staticCompositionLocalOf<DrawerHost?> { null }

/** وجهة في القائمة: كل وجهة هنا مسجَّلة في [MgtikNavGraph] بلا وسائط، فتُفتح مباشرة. */
private class DrawerDestination(
    val screen: Screen,
    @StringRes val labelRes: Int,
    /** مسارات تابعة (تفاصيل/تعديل/إنشاء) يبقى بها هذا العنصر هو المميَّز. */
    val childRoutes: List<String> = emptyList()
)

private class DrawerSection(@StringRes val titleRes: Int, val items: List<DrawerDestination>)

/** SSOT لمحتوى القائمة؛ مرتّبة حسب وحدات التطبيق. */
private val drawerSections = listOf(
    DrawerSection(
        R.string.drawer_section_main, listOf(
            DrawerDestination(Screen.Overview, R.string.nav_overview),
            DrawerDestination(Screen.GeneratorWizard, R.string.nav_generator),
            DrawerDestination(Screen.ActiveUsers, R.string.drawer_item_active_users, listOf(Screen.UserWorkspace.route)),
            DrawerDestination(Screen.UsersSearch, R.string.drawer_item_search)
        )
    ),
    DrawerSection(
        R.string.drawer_section_users, listOf(
            DrawerDestination(
                Screen.UserManagement, R.string.nav_users,
                listOf(Screen.UserDetail.route, Screen.UserEdit.route, Screen.UserCreate.route)
            ),
            DrawerDestination(Screen.SubscriptionForm, R.string.drawer_item_new_subscription, listOf(Screen.SubscriptionDetail.route)),
            DrawerDestination(Screen.HistoricalUsersArchive, R.string.drawer_item_archive)
        )
    ),
    DrawerSection(
        R.string.drawer_section_packages, listOf(
            DrawerDestination(Screen.Profiles, R.string.nav_profiles, listOf(Screen.ProfileDetail.route)),
            DrawerDestination(Screen.ProfileCategory, R.string.drawer_item_categories),
            DrawerDestination(Screen.ProfileClassification, R.string.drawer_item_classifications),
            DrawerDestination(Screen.TemplatePicker, R.string.drawer_item_templates)
        )
    ),
    // الملاك والموزعون وحدة مالية (ملف مالي وفواتير) فنُقلت من «المستخدمون» إلى «المالية»
    DrawerSection(
        R.string.drawer_section_finance, listOf(
            DrawerDestination(Screen.BillingDashboard, R.string.nav_billing),
            DrawerDestination(Screen.OrdersList, R.string.drawer_item_orders, listOf(Screen.OrderDetail.route)),
            DrawerDestination(Screen.InvoicesList, R.string.drawer_item_invoices, listOf(Screen.InvoiceDetail.route)),
            DrawerDestination(
                Screen.OwnerDirectory, R.string.drawer_item_owners,
                listOf(Screen.OwnerForm.route, Screen.OwnerDetail.route)
            ),
            DrawerDestination(Screen.Reports, R.string.nav_reports)
        )
    ),
    // الإعدادات (انتقلت من الشريط السفلي): وحدة إدارية أساسية فتُوضع أول قسم النظام
    DrawerSection(
        R.string.drawer_section_system, listOf(
            DrawerDestination(Screen.Settings, R.string.nav_settings),
            DrawerDestination(Screen.RouterSetup, R.string.drawer_item_router),
            DrawerDestination(Screen.NotificationCenter, R.string.drawer_item_notifications),
            DrawerDestination(Screen.OfflineQueue, R.string.drawer_item_queue),
            DrawerDestination(Screen.HistoryBatches, R.string.drawer_item_history)
        )
    )
)

private fun DrawerDestination.matches(route: String?): Boolean {
    if (route == null) return false
    val all = listOf(screen.route) + childRoutes
    return all.any { route == it || route.startsWith("$it/") }
}

/**
 * التنقل من القائمة: نظام Navigation نفسه ومعايير الشريط السفلي (popUpTo على «نظرة عامة» + singleTop)؛
 * فلا يتراكم المكدّس بين أقسام القائمة والرجوع يعود إلى الرئيسية بصورة صحيحة.
 */
fun NavController.navigateFromDrawer(route: String) {
    navigate(route) {
        popUpTo(Screen.Overview.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun MgtikNavigationDrawerContent(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    /** يُستدعى قبل فتح نافذة تأكيد جلسة (لإغلاق القائمة). */
    onSessionAction: () -> Unit = {}
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = Dimens.SpaceLG)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = Dimens.SpaceXL)
            )
            drawerSections.forEachIndexed { index, section ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp, vertical = Dimens.SpaceSM))
                Text(
                    text = stringResource(section.titleRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = Dimens.SpaceSM)
                )
                section.items.forEach { item ->
                    NavigationDrawerItem(
                        icon = { Icon(item.screen.icon, contentDescription = null) },
                        label = { Text(stringResource(item.labelRes)) },
                        selected = item.matches(currentRoute),
                        onClick = { onNavigate(item.screen.route) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
            }
            // الجلسات والإغلاق: منفصلة بصرياً عن روابط الصفحات، وبالمنطق نفسه المستعمل في الإعدادات
            LocalSessionActions.current?.let { host ->
                HorizontalDivider(
                    thickness = 2.dp, color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = Dimens.SpaceLG, vertical = Dimens.SpaceMD)
                )
                Text(
                    text = stringResource(R.string.session_section_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = Dimens.SpaceSM)
                )
                SessionAction.values().forEach { action ->
                    val enabled = action != SessionAction.ROUTER_LOGOUT || host.routerStatus.canLogout()
                    NavigationDrawerItem(
                        icon = { Icon(action.icon(), contentDescription = null) },
                        label = { Text(stringResource(action.labelRes())) },
                        selected = false,
                        onClick = { if (enabled) { onSessionAction(); host.request(action) } },
                        colors = NavigationDrawerItemDefaults.colors(
                            unselectedIconColor = MaterialTheme.colorScheme.error.copy(alpha = if (enabled) 1f else 0.38f),
                            unselectedTextColor = MaterialTheme.colorScheme.error.copy(alpha = if (enabled) 1f else 0.38f)
                        ),
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
            }
            Spacer(Modifier.height(Dimens.SpaceSM))
        }
    }
}

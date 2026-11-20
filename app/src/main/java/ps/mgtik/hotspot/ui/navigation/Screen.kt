package ps.mgtik.hotspot.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import ps.mgtik.hotspot.R

/**
 * تعريف الشاشات المعتمدة (S0–S32) لتطبيق MGTik. الشريط السفلي: 5 عناصر كحد أقصى (إرشاد Material 3)؛ الإعدادات تُفتح من القائمة الجانبية.
 */
sealed class Screen(
    val id: String,
    val route: String,
    val titleAr: String,
    val icon: ImageVector,
    val isPhase2: Boolean = false,
    /** عنوان قابل للترجمة لعناصر الشريط السفلي (titleAr يبقى للتوثيق الداخلي فقط). */
    @StringRes val titleRes: Int? = null,
    /** نسخة مخطّطة للأيقونة تُعرض في الشريط السفلي لغير المحدد (اختيارية). */
    val iconOutlined: ImageVector? = null
) {
    object Splash : Screen("S0", "s0_splash", "شاشة البدء", Icons.Default.RocketLaunch)
    object Login : Screen("S1", "s1_login", "تسجيل الدخول", Icons.Default.Lock)
    object RouterSetup : Screen("S2", "s2_router_setup", "إعدادات الراوتر", Icons.Default.Router)
    object Overview : Screen("S3", "s3_overview", "نظرة عامة", Icons.Default.Dashboard, titleRes = R.string.nav_overview, iconOutlined = Icons.Outlined.Dashboard)
    object GeneratorWizard : Screen("S4", "s4_generator_wizard", "معالج التوليد", Icons.Default.ConfirmationNumber, titleRes = R.string.nav_generator, iconOutlined = Icons.Outlined.ConfirmationNumber)
    object ExecutionProgress : Screen("S5", "s5_execution_progress", "تقدم التنفيذ", Icons.Default.HourglassTop)
    object GenerationResult : Screen("S6", "s6_generation_result", "نتائج التوليد", Icons.Default.CheckCircle)
    object BillingDashboard : Screen("S7", "s7_billing_dashboard", "المالية والفوترة", Icons.Default.AccountBalanceWallet, titleRes = R.string.nav_billing, iconOutlined = Icons.Outlined.AccountBalanceWallet)
    object OrdersList : Screen("S8", "s8_orders_list", "قائمة الطلبات", Icons.Default.ReceiptLong)
    object OrderDetail : Screen("S9", "s9_order_detail", "تفاصيل الطلب", Icons.Default.Receipt)
    object InvoicesList : Screen("S10", "s10_invoices_list", "قائمة الفواتير", Icons.Default.RequestQuote)
    object InvoiceDetail : Screen("S11", "s11_invoice_detail", "تفاصيل الفاتورة", Icons.Default.Description)
    
    // تصحيح المسميات حسب التكليف (البند 3-19)
    object OwnerDirectory : Screen("S12", "s12_owner_directory", "دليل الملاك والموزعين", Icons.Default.People)
    object OwnerForm : Screen("S13", "s13_owner_form", "إضافة مالك جديد", Icons.Default.PersonAdd)
    object OwnerDetail : Screen("S14", "s14_owner_detail", "ملف المالك المالي", Icons.Default.Badge)
    
    object UsersSearch : Screen("S15", "s15_users_search", "البحث في المتصلين", Icons.Default.Search)
    object UserManagement : Screen("S16", "s16_user_management", "إدارة المستخدمين", Icons.Default.ManageAccounts, titleRes = R.string.nav_users, iconOutlined = Icons.Outlined.ManageAccounts)
    object UserDetail : Screen("S16D", "s16_user_detail", "تفاصيل المستخدم", Icons.Default.Person)
    object UserEdit : Screen("S16E", "s16_user_edit", "تعديل مستخدم", Icons.Default.Edit)
    object UserCreate : Screen("S16C", "s16_user_create", "إنشاء مستخدم", Icons.Default.Add)
    object Profiles : Screen("S17", "s17_profiles", "باقات السرعة", Icons.Default.Speed, titleRes = R.string.nav_profiles, iconOutlined = Icons.Outlined.Speed)
    object ProfileDetail : Screen("S17D", "s17_profile_detail", "تفاصيل الباقة", Icons.Default.Speed)
    object ProfileCategory : Screen("S18", "s18_profile_category", "فئات الباقات", Icons.Default.Category)
    object ProfileClassification : Screen("S19", "s19_profile_classification", "تصنيف الباقات", Icons.Default.Class)
    object UmProfiles : Screen("S34", "s34_um_profiles", "UM Profiles", Icons.Default.Speed)
    object UmLimitations : Screen("S33", "s33_um_limitations", "UM Limitations", Icons.Default.Speed)
    object NotificationCenter : Screen("S20", "s20_notification_center", "مركز الإشعارات", Icons.Default.Notifications, isPhase2 = true)
    object HistoryBatches : Screen("S21", "s21_history_batches", "سجل الدفعات التاريخي", Icons.Default.HistoryEdu, isPhase2 = true)
    object HistoricalUsersArchive : Screen("S22", "s22_historical_users_archive", "أرشيف المستخدمين", Icons.Default.Archive, isPhase2 = true)
    object TemplatePicker : Screen("S23", "s23_template_picker", "قوالب الكروت", Icons.Default.ViewQuilt, isPhase2 = true)
    object Settings : Screen("S24", "s24_settings", "الإعدادات العامة", Icons.Default.Settings, isPhase2 = true, titleRes = R.string.nav_settings, iconOutlined = Icons.Outlined.Settings)
    object Reports : Screen("S25", "s25_reports", "التقارير", Icons.Default.BarChart, titleRes = R.string.nav_reports, iconOutlined = Icons.Outlined.BarChart)

    object SubscriptionForm : Screen("S27", "s27_subscription_form", "اشتراك جديد", Icons.Default.PersonAdd)
    object SubscriptionDetail : Screen("S28", "s28_subscription_detail", "تفاصيل الاشتراك", Icons.Default.Badge)

    object ActiveUsers : Screen("S30", "s30_active_users", "المستخدمون النشطون", Icons.Default.Wifi)
    object UserWorkspace : Screen("S31", "s31_user_workspace", "مساحة عمل المستخدم", Icons.Default.Person)

    object OfflineQueue : Screen("S29", "s29_offline_queue", "طابور المزامنة", Icons.Default.Sync)

    /** إعداد واتصال User Manager (يُفتح من الإعدادات ← الاتصال والخدمات / البيانات والصيانة). */
    object UserManSetup : Screen("S32", "s32_userman_setup", "إعداد User Manager", Icons.Default.Dns)

    /** تشخيص الاتصال: الحالة الحالية وسجل آخر 50 حدثاً مع تصدير. */
    object ConnectionDiagnostics : Screen("S35", "s35_connection_diagnostics", "تشخيص الاتصال", Icons.Default.Dns)

    object VoucherPrintStudio : Screen("S26", "s26_voucher_export", "تصدير الدفعة", Icons.Default.Print)

    companion object {
        val bottomNavItems = listOf(
            Overview,
            GeneratorWizard,
            UserManagement,
            Profiles,
            BillingDashboard
        )

        /** وجهات جذرية خارج الشريط السفلي (زر القائمة بدل الرجوع): الإعدادات في القائمة الجانبية. */
        val drawerRootRoutes: Set<String> = setOf(Settings.route)
    }
}

package ps.mgtik.hotspot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import ps.mgtik.hotspot.data.notification.NotificationLaunch
import ps.mgtik.hotspot.ui.navigation.navigateToNotificationTarget
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import ps.mgtik.hotspot.ui.navigation.DrawerHost
import ps.mgtik.hotspot.ui.components.session.LocalSessionActions
import ps.mgtik.hotspot.ui.components.session.SessionActionDialogs
import ps.mgtik.hotspot.ui.components.session.SessionActionsHost
import androidx.lifecycle.lifecycleScope
import ps.mgtik.hotspot.ui.navigation.LocalDrawerHost
import ps.mgtik.hotspot.ui.navigation.MgtikNavigationDrawerContent
import ps.mgtik.hotspot.ui.navigation.navigateFromDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import ps.mgtik.hotspot.ui.components.router.RouterDecisionDialog
import ps.mgtik.hotspot.ui.components.router.RouterStatusBanner
import ps.mgtik.hotspot.ui.components.router.needsDecisionDialog
import ps.mgtik.hotspot.ui.components.router.shouldShowBanner
import ps.mgtik.hotspot.ui.navigation.MgtikBottomNavigationBar
import ps.mgtik.hotspot.ui.navigation.MgtikNavGraph
import ps.mgtik.hotspot.ui.navigation.Screen
import ps.mgtik.hotspot.ui.theme.MgtikTheme
import ps.mgtik.hotspot.ui.util.ErrorTranslator
import ps.mgtik.hotspot.ui.viewmodel.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import ps.mgtik.hotspot.data.local.prefs.AppPreferences
import ps.mgtik.hotspot.data.local.prefs.ThemeMode
import ps.mgtik.hotspot.ui.util.LocalCurrencySymbol
import ps.mgtik.hotspot.ui.util.LocaleHelper
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appPreferences: AppPreferences

    override fun attachBaseContext(newBase: Context) {
        // قراءة اللغة المحفوظة (SharedPreferences) قبل إنشاء الواجهة لا مفر منها؛ تُستثنى صراحةً من StrictMode
        // كي لا تخفي تحذيراتٍ حقيقية أخرى في مسار الإقلاع
        val oldPolicy = android.os.StrictMode.allowThreadDiskReads()
        try {
            super.attachBaseContext(LocaleHelper.wrap(newBase))
        } finally {
            android.os.StrictMode.setThreadPolicy(oldPolicy)
        }
    }

    private val authViewModel: AuthViewModel by viewModels()
    private val mgtikViewModel: MgtikViewModel by viewModels()
    private val routerViewModel: RouterViewModel by viewModels()
    private val routerStatusViewModel: RouterStatusViewModel by viewModels()
    private val profilesViewModel: ProfilesViewModel by viewModels()
    private val usersViewModel: UsersViewModel by viewModels()
    private val generationViewModel: GenerationViewModel by viewModels()
    private val exportViewModel: ExportViewModel by viewModels()
    private val billingViewModel: BillingViewModel by viewModels()
    private val ordersViewModel: OrdersViewModel by viewModels()
    private val ownersViewModel: OwnersViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val userManViewModel: UserManViewModel by viewModels()
    private val historyViewModel: HistoryViewModel by viewModels()
    private val notificationViewModel: NotificationViewModel by viewModels()
    private val reportsViewModel: ReportsViewModel by viewModels()
    private val subscriptionsViewModel: SubscriptionsViewModel by viewModels()
    private val liveSessionsViewModel: LiveSessionsViewModel by viewModels()
    private val userWorkspaceViewModel: UserWorkspaceViewModel by viewModels()
    private val userDetailViewModel: UserDetailViewModel by viewModels()
    private val sessionActionsViewModel: SessionActionsViewModel by viewModels()

    @Inject lateinit var sessionManager: ps.mgtik.hotspot.domain.repository.ISessionManager

    private companion object {
        const val SYSTEM_SPLASH_MAX_MS = 2_000L
    }

    /** طلب فتح قادم من إشعار نظام (إقلاع بارد أو التطبيق حيّ)؛ يستهلكه MainScreen حين تصير الواجهة جاهزة. */
    private val pendingNotificationLaunch = mutableStateOf<NotificationLaunch?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* الرفض يعطّل إشعارات النظام فقط */ }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationLaunch.from(intent)?.let { pendingNotificationLaunch.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. تفعيل Splash API الرسمي قبل onCreate
        val splashScreen = installSplashScreen()
        
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // فتح من إشعار نظام والتطبيق مغلق (إقلاع بارد). عند إعادة الإنشاء (تدوير) لا نعيد التنقل.
        if (savedInstanceState == null) NotificationLaunch.from(intent)?.let { pendingNotificationLaunch.value = it }
        requestNotificationPermissionIfNeeded()
        // بعد إغلاق منظَّم قد تبقى العملية حيّة: نعيد بناء حالات الاتصال والمزامنة عند فتح الواجهة (آمن للتكرار)
        lifecycleScope.launch { runCatching { sessionManager.resumeServices() } }

        // 2. إبقاء شاشة النظام ظاهرة حتى اكتمال الفحص الأولي للجلسة
        // حارس زمني: لا نحبس المستخدم على سبلاش النظام إن تأخر فحص الجلسة
        val launchedAt = SystemClock.elapsedRealtime()
        splashScreen.setKeepOnScreenCondition {
            !authViewModel.uiState.value.isInitialAuthChecked &&
                SystemClock.elapsedRealtime() - launchedAt < SYSTEM_SPLASH_MAX_MS
        }

        setContent {
            val themeMode by appPreferences.themeMode.collectAsState()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            MgtikTheme(darkTheme = darkTheme) {
                MainScreen(
                    authViewModel = authViewModel,
                    mgtikViewModel = mgtikViewModel,
                    routerViewModel = routerViewModel,
                    routerStatusViewModel = routerStatusViewModel,
                    profilesViewModel = profilesViewModel,
                    usersViewModel = usersViewModel,
                    generationViewModel = generationViewModel,
                    exportViewModel = exportViewModel,
                    billingViewModel = billingViewModel,
                    ordersViewModel = ordersViewModel,
                    ownersViewModel = ownersViewModel,
                    settingsViewModel = settingsViewModel,
                    userManViewModel = userManViewModel,
                    historyViewModel = historyViewModel,
                    notificationViewModel = notificationViewModel,
                    reportsViewModel = reportsViewModel,
                    subscriptionsViewModel = subscriptionsViewModel,
                    liveSessionsViewModel = liveSessionsViewModel,
                    userWorkspaceViewModel = userWorkspaceViewModel,
                    userDetailViewModel = userDetailViewModel,
                    sessionActionsViewModel = sessionActionsViewModel,
                    pendingNotificationLaunch = pendingNotificationLaunch.value,
                    onNotificationLaunchHandled = {
                        pendingNotificationLaunch.value = null
                        intent?.action = null // لا يُعاد التنقل عند إعادة إنشاء النشاط
                    }
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    authViewModel: AuthViewModel,
    mgtikViewModel: MgtikViewModel,
    routerViewModel: RouterViewModel,
    routerStatusViewModel: RouterStatusViewModel,
    profilesViewModel: ProfilesViewModel,
    usersViewModel: UsersViewModel,
    generationViewModel: GenerationViewModel,
    exportViewModel: ExportViewModel,
    billingViewModel: BillingViewModel,
    ordersViewModel: OrdersViewModel,
    ownersViewModel: OwnersViewModel,
    settingsViewModel: SettingsViewModel,
    userManViewModel: UserManViewModel,
    historyViewModel: HistoryViewModel,
    notificationViewModel: NotificationViewModel,
    reportsViewModel: ReportsViewModel,
    subscriptionsViewModel: SubscriptionsViewModel,
    liveSessionsViewModel: LiveSessionsViewModel,
    userWorkspaceViewModel: UserWorkspaceViewModel,
    userDetailViewModel: UserDetailViewModel,
    sessionActionsViewModel: SessionActionsViewModel,
    pendingNotificationLaunch: NotificationLaunch? = null,
    onNotificationLaunchHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    val mgtikState by mgtikViewModel.uiState.collectAsState()
    val currentRoute by navController.currentBackStackEntryAsState()
    val bottomNavRoutes = remember { Screen.bottomNavItems.map { it.route }.toSet() }
    val showBottomBar = currentRoute?.destination?.route in bottomNavRoutes

    // الحالة المركزية للراوتر: شريط واحد وحوار قرار واحد لكل التطبيق (لا تنبيه اتصال خاص بكل شاشة)
    val routerStatus by routerStatusViewModel.status.collectAsState()
    val route = currentRoute?.destination?.route
    val routerUiVisibleHere = mgtikState.session.isAuthenticated &&
        route != null &&
        route != Screen.Splash.route && route != Screen.Login.route && route != Screen.RouterSetup.route
    val showRouterBanner = routerUiVisibleHere && routerStatus.shouldShowBanner()
    val showRouterDecision = routerUiVisibleHere && routerStatus.shouldShowBanner() && routerStatus.needsDecisionDialog()
    // فتح من إشعار نظام: ننتظر جلسة موثّقة وخروجاً من Splash/Login/RouterSetup، ثم مركز الإشعارات فالوجهة (الرجوع يعود للمركز)
    LaunchedEffect(pendingNotificationLaunch, routerUiVisibleHere) {
        val launch = pendingNotificationLaunch ?: return@LaunchedEffect
        if (!routerUiVisibleHere) return@LaunchedEffect
        navController.navigate(Screen.NotificationCenter.route) { launchSingleTop = true }
        val type = launch.targetType
        val id = launch.targetId
        if (!type.isNullOrBlank() && !id.isNullOrBlank()) navController.navigateToNotificationTarget(type, id)
        onNotificationLaunchHandled()
    }
    val openRouterSettings: () -> Unit = {
        navController.navigate(Screen.RouterSetup.route) { launchSingleTop = true }
    }

    // رسائل عامة من الحالة المركزية (إعادة اتصال الراوتر، مزامنة القوالب، أخطاؤها) تظهر من أي شاشة
    val snackbarHostState = remember { SnackbarHostState() }
    val globalMessageResId = mgtikState.successMessageResId
        ?: mgtikState.messageResId
        ?: mgtikState.errorMessage?.let { ErrorTranslator.resIdOf(it) }
    val globalMessage = globalMessageResId?.let { stringResource(it) }
    LaunchedEffect(globalMessageResId) {
        if (globalMessage != null) {
            snackbarHostState.showSnackbar(globalMessage)
            mgtikViewModel.dismissSnackbar()
            mgtikViewModel.clearSnackbar()
        }
    }

    val currencySymbol = settingsViewModel.uiState.collectAsState().value.companyInfo.currencySymbol.ifBlank { "₪" }

    // القائمة الجانبية: متاحة بعد الدخول وخارج Splash/Login/إعداد الراوتر الأول؛ تُغلق بعد اختيار القسم وبزر الرجوع
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val drawerEnabled = routerUiVisibleHere
    BackHandler(enabled = drawerState.isOpen) { drawerScope.launch { drawerState.close() } }
    val isRootDestination = showBottomBar || route in Screen.drawerRootRoutes
    val drawerHost = remember(drawerEnabled, isRootDestination) {
        if (drawerEnabled) DrawerHost(open = { drawerScope.launch { drawerState.open() } }, isRoot = isRootDestination) else null
    }

    // إجراءات الجلسة (خروج سحابي/راوتر/إغلاق): حالة ونوافذ مركزية واحدة تخدم الإعدادات والقائمة الجانبية معاً
    val sessionState by sessionActionsViewModel.state.collectAsState()
    val sessionRouterStatus by sessionActionsViewModel.routerStatus.collectAsState()
    val sessionHost = remember(sessionRouterStatus, drawerEnabled) {
        if (drawerEnabled) SessionActionsHost(sessionRouterStatus, sessionActionsViewModel::request) else null
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val sessionMessageText = sessionState.message?.let { stringResource(it.resId, *it.args.toTypedArray()) }
    LaunchedEffect(sessionState.message) {
        if (sessionMessageText != null) {
            snackbarHostState.showSnackbar(sessionMessageText)
            sessionActionsViewModel.messageShown()
        }
    }
    val exitNoteText = sessionState.exitNote?.let { stringResource(it.resId, *it.args.toTypedArray()) }
    LaunchedEffect(sessionState.exitReady) {
        if (sessionState.exitReady) {
            exitNoteText?.let { android.widget.Toast.makeText(context.applicationContext, it, android.widget.Toast.LENGTH_LONG).show() }
            sessionActionsViewModel.exitHandled()
            // الآلية الرسمية: إنهاء النشاط وإزالة المهمة (لا System.exit ولا قتل عمليات)
            (context as? android.app.Activity)?.finishAndRemoveTask()
        }
    }

    CompositionLocalProvider(
        LocalCurrencySymbol provides currencySymbol,
        LocalDrawerHost provides drawerHost,
        LocalSessionActions provides sessionHost
    ) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerEnabled,
        drawerContent = {
            MgtikNavigationDrawerContent(
                currentRoute = route,
                onNavigate = { target ->
                    drawerScope.launch { drawerState.close() }
                    if (target != route) navController.navigateFromDrawer(target)
                },
                onSessionAction = { drawerScope.launch { drawerState.close() } }
            )
        }
    ) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (showBottomBar) {
                    MgtikBottomNavigationBar(
                        navController = navController,
                        currentRoute = currentRoute?.destination?.route
                    )
                }
            }
        ) { innerPadding ->
            // تدفق الشاشات كاملاً داخل NavHost: Splash -> Login/RouterSetup/Home
            Column(modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)) {
            if (showRouterBanner) {
                RouterStatusBanner(
                    status = routerStatus,
                    onRetry = routerStatusViewModel::retry,
                    onConfigure = openRouterSettings,
                    onOpenDiagnostics = { navController.navigate(Screen.ConnectionDiagnostics.route) { launchSingleTop = true } }
                )
            }
            MgtikNavGraph(
                navController = navController,
                mgtikState = mgtikState,
                authViewModel = authViewModel,
                mgtikViewModel = mgtikViewModel,
                routerViewModel = routerViewModel,
                routerStatusViewModel = routerStatusViewModel,
                profilesViewModel = profilesViewModel,
                usersViewModel = usersViewModel,
                generationViewModel = generationViewModel,
                exportViewModel = exportViewModel,
                billingViewModel = billingViewModel,
                ordersViewModel = ordersViewModel,
                ownersViewModel = ownersViewModel,
                settingsViewModel = settingsViewModel,
                userManViewModel = userManViewModel,
                historyViewModel = historyViewModel,
                notificationViewModel = notificationViewModel,
                reportsViewModel = reportsViewModel,
                subscriptionsViewModel = subscriptionsViewModel,
                liveSessionsViewModel = liveSessionsViewModel,
                userWorkspaceViewModel = userWorkspaceViewModel,
                userDetailViewModel = userDetailViewModel,
                startDestination = Screen.Splash.route,
                // consumeWindowInsets: الشاشات التي تملك Scaffold/TopAppBar خاصاً بها لا تُضيف
                // مسافة شريط الحالة مرة ثانية فوق ما طُبّق هنا (كانت تظهر فجوة مزدوجة في الأعلى)
                // الشريط يتولى مسافة شريط الحالة حين يظهر؛ فلا تكررها الشاشات تحته
                modifier = Modifier.weight(1f).then(
                    if (showRouterBanner) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier
                ),
                onOpenNotifications = {
                    navController.navigate(Screen.NotificationCenter.route) {
                        launchSingleTop = true
                    }
                }
            )
            }
        }
    }
    } // ModalNavigationDrawer

    SessionActionDialogs(
        state = sessionState,
        onConfirm = sessionActionsViewModel::confirm,
        onDismiss = sessionActionsViewModel::dismiss
    )

    if (showRouterDecision) {
        RouterDecisionDialog(
            status = routerStatus,
            onRetry = routerStatusViewModel::retry,
            onConfigure = openRouterSettings,
            onSkip = routerStatusViewModel::skip
        )
    }
    }
}

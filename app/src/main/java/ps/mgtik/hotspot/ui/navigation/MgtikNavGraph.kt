package ps.mgtik.hotspot.ui.navigation

import ps.mgtik.hotspot.domain.model.NotificationTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.model.ProfileStatus
import ps.mgtik.hotspot.domain.model.Subscription
import ps.mgtik.hotspot.domain.model.SubscriptionStatus
import ps.mgtik.hotspot.ui.screens.*
import ps.mgtik.hotspot.ui.screens.splash.MgtikSplashScreen
import ps.mgtik.hotspot.ui.viewmodel.*

/** أقصى انتظار بعد نجاح الدخول لوصول إعداد الراوتر قبل الانتقال للرئيسية. */
private const val POST_LOGIN_TIMEOUT_MS = 8_000L

/**
 * وجهة ما بعد السبلاش (مصدر وحيد للقرار):
 * غير مسجّل ← S1، راوتر غير مُهيّأ ← S2، «متابعة دون اتصال» (قرار صريح) ← S3،
 * فشل اتصال الراوتر ← S2، User Manager غير جاهز ← S32، كل الفحوص ناجحة ← S3.
 * [StartupStage.CHECKING] هنا يعني أن الفحص لم يكتمل (انتهت مهلة السبلاش): لا يُفتح S3 بصمت، بل S2 لمعالجة الاتصال.
 * القيم الافتراضية تُبقي سلوك الاستدعاء بمعاملين كما كان (الفحوص ناجحة).
 */
internal fun resolveStartRoute(
    isAuthenticated: Boolean,
    routerHost: String,
    stage: StartupStage = StartupStage.READY,
    continueOffline: Boolean = false
): String = when {
    !isAuthenticated -> Screen.Login.route
    routerHost.isBlank() -> Screen.RouterSetup.route
    continueOffline -> Screen.Overview.route
    else -> when (stage) {
        StartupStage.READY -> Screen.Overview.route
        StartupStage.USERMAN_NOT_READY -> Screen.UserManSetup.route
        StartupStage.ROUTER_FAILED, StartupStage.CHECKING -> Screen.RouterSetup.route
    }
}

/**
 * MgtikNavGraph - مخطط التوجيه المحدث لدعم دورة حياة الملاك والموزعين (§4.18) والإعدادات (§24).
 * تم الربط الكامل لكافة شاشات التكليف (S7-S11) وشاشة الإعدادات (S24).
 */
@Composable
fun MgtikNavGraph(
    navController: NavHostController,
    mgtikState: MgtikUiState,
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
    userWorkspaceViewModel: ps.mgtik.hotspot.ui.viewmodel.UserWorkspaceViewModel,
    userDetailViewModel: UserDetailViewModel,
    modifier: Modifier = Modifier,
    startDestination: String = Screen.Splash.route,
    onOpenNotifications: () -> Unit = {}
) {
    val authState by authViewModel.uiState.collectAsState()
    val billingState by billingViewModel.uiState.collectAsState()
    val ordersState by ordersViewModel.uiState.collectAsState()
    val notificationState by notificationViewModel.uiState.collectAsState()
    val subscriptionsState by subscriptionsViewModel.uiState.collectAsState()

    // انتهاء الجلسة (تسجيل خروج/انتهاء صلاحية): العودة لشاشة الدخول وتفريغ المكدّس؛ لا تنقّل أثناء الإقلاع (Splash يقرّر)
    val sessionAuthenticated = mgtikState.session.isAuthenticated
    LaunchedEffect(sessionAuthenticated, mgtikState.isInitialAuthChecked) {
        val current = navController.currentBackStackEntry?.destination?.route
        if (mgtikState.isInitialAuthChecked && !sessionAuthenticated &&
            current != null && current != Screen.Login.route && current != Screen.Splash.route
        ) {
            navController.navigate(Screen.Login.route) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        // S0: شاشة البدء
        composable(Screen.Splash.route) {
            MgtikSplashScreen(
                isAuthChecked = mgtikState.isInitialAuthChecked,
                isRouterChecked = mgtikState.isInitialRouterChecked,
                isSyncChecked = mgtikState.isInitialSyncChecked,
                routerRequired = mgtikState.session.isAuthenticated && mgtikState.routerConfig.host.isNotBlank() && !mgtikState.continueOffline,
                routerFailed = mgtikState.startupStage == StartupStage.ROUTER_FAILED,
                failureCode = mgtikState.startupErrorCode,
                onRetry = routerStatusViewModel::retry,
                onContinueOffline = routerStatusViewModel::skip,
                onOpenRouterSettings = {
                    navController.navigate(Screen.RouterSetup.route) { popUpTo(Screen.Splash.route) { inclusive = true } }
                },
                onSplashFinished = {
                    val startRoute = resolveStartRoute(
                        isAuthenticated = mgtikState.session.isAuthenticated,
                        routerHost = mgtikState.routerConfig.host,
                        stage = mgtikState.startupStage,
                        continueOffline = mgtikState.continueOffline
                    )
                    navController.navigate(startRoute) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                }
            )
        }

        // S1: تسجيل الدخول
        composable(Screen.Login.route) {
            val isAuthenticated = mgtikState.session.isAuthenticated
            // جاهز = مصادَق + وصل إعداد الراوتر (لتحديد S2 أو الرئيسية)
            val isReady = isAuthenticated && mgtikState.isInitialRouterChecked
            val latestState by rememberUpdatedState(mgtikState)

            LaunchedEffect(isAuthenticated, isReady) {
                if (!isAuthenticated) return@LaunchedEffect
                if (!isReady) delay(POST_LOGIN_TIMEOUT_MS) // شبكة بطيئة: لا نحبس المستخدم على شاشة الدخول
                // بعد المهلة لا نفتح الرئيسية بصمت: الفحص غير المكتمل يوجّه إلى S2 (انظر resolveStartRoute)
                val route = resolveStartRoute(
                    isAuthenticated = true,
                    routerHost = latestState.routerConfig.host,
                    stage = latestState.startupStage,
                    continueOffline = latestState.continueOffline
                )
                navController.navigate(route) {
                    popUpTo(Screen.Login.route) { inclusive = true }
                }
            }

            S1_LoginScreen(
                // أثناء الانتظار بين نجاح المصادقة والانتقال نُبقي حالة التحميل ظاهرة
                state = authState.copy(isLoading = authState.isLoading || isAuthenticated),
                onLogin = authViewModel::signIn,
                onInputChanged = authViewModel::clearError,
                onResetPassword = authViewModel::resetPassword,
                onResetDialogClosed = authViewModel::clearPasswordMessages
            )
        }

        // S12: دليل الملاك والموزعين
        composable(Screen.OwnerDirectory.route) {
            val ownerState by ownersViewModel.uiState.collectAsState()
            S12_OwnerDirectoryScreen(
                state = ownerState,
                onSearchQueryChanged = { ownersViewModel.onSearchQueryChanged(it) },
                onFilterChanged = ownersViewModel::setFilter,
                onSortChanged = ownersViewModel::setSort,
                onSelectOwner = { owner ->
                    navController.navigate("${Screen.OwnerDetail.route}/${owner.ownerId}")
                },
                onOpenCreateOwner = { navController.navigate(Screen.OwnerForm.route) },
                onMessageShown = ownersViewModel::clearMessages,
                onBack = { navController.popBackStack() }
            )
        }

        // S13: نموذج إضافة مالك
        composable(Screen.OwnerForm.route) {
            val ownerState by ownersViewModel.uiState.collectAsState()
            S13_OwnerFormSheet(
                isSaving = ownerState.isSaving,
                validationErrors = ownerState.validationErrors,
                errorMessageResId = ownerState.errorMessageResId,
                onFieldEdited = ownersViewModel::clearFieldError,
                onMessageShown = ownersViewModel::clearMessages,
                onSave = { name, phone -> ownersViewModel.createOwner(name, phone) },
                onBack = { navController.popBackStack() }
            )

            // الإنشاء الناجح حدث لمرة واحدة (لا يُعاد عند فتح النموذج ثانية كما كانت تفعل رسالة النجاح الدائمة)
            LaunchedEffect(Unit) {
                ownersViewModel.events.collect { event ->
                    if (event is OwnersEvent.OwnerCreated) navController.popBackStack()
                }
            }
        }

        // S13 (تعديل): نفس النموذج بقيم المالك الحالية؛ الحفظ الناجح يعيد لملفه
        composable(
            route = "${Screen.OwnerForm.route}/{ownerId}",
            arguments = listOf(navArgument("ownerId") { type = NavType.StringType })
        ) { backStackEntry ->
            val ownerId = backStackEntry.arguments?.getString("ownerId") ?: ""
            val ownerState by ownersViewModel.uiState.collectAsState()
            val owner = ownerState.ownersSummary.firstOrNull { it.ownerId == ownerId }
            if (owner != null) {
                S13_OwnerFormSheet(
                    isSaving = ownerState.isSaving,
                    validationErrors = ownerState.validationErrors,
                    errorMessageResId = ownerState.errorMessageResId,
                    onFieldEdited = ownersViewModel::clearFieldError,
                    onMessageShown = ownersViewModel::clearMessages,
                    onSave = { name, phone -> ownersViewModel.updateOwner(ownerId, name, phone) },
                    onBack = { navController.popBackStack() },
                    isEdit = true,
                    initialName = owner.name,
                    initialPhone = owner.phone
                )
            }
            LaunchedEffect(Unit) {
                ownersViewModel.events.collect { event ->
                    if (event is OwnersEvent.OwnerUpdated) navController.popBackStack()
                }
            }
        }

        // S14: ملف المالك المالي
        composable(
            route = "${Screen.OwnerDetail.route}/{ownerId}",
            arguments = listOf(navArgument("ownerId") { type = NavType.StringType })
        ) { backStackEntry ->
            val ownerId = backStackEntry.arguments?.getString("ownerId") ?: ""
            val ownerState by ownersViewModel.uiState.collectAsState()

            LaunchedEffect(ownerId) {
                ownersViewModel.selectOwner(ownerId)
            }

            if (ownerState.isLoading && ownerState.selectedOwnerWorkspace == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                ownerState.selectedOwnerWorkspace?.let { workspace ->
                    S14_OwnerDetailScreen(
                        workspace = workspace,
                        priceBook = ownerState.selectedOwnerPriceBook,
                        isSaving = ownerState.isSaving,
                        successMessageResId = ownerState.successMessageResId,
                        errorMessageResId = ownerState.errorMessageResId,
                        onMessageShown = ownersViewModel::clearMessages,
                        onSetOpeningBalance = { ownersViewModel.setOpeningBalance(workspace.ownerId, it) },
                        onRecordAdvance = { amount, notes -> ownersViewModel.recordAdvance(workspace.ownerId, amount, notes) },
                        onRefund = { amount -> ownersViewModel.refundFromCredit(workspace.ownerId, amount) },
                        onSettleFromCredit = ownersViewModel::settleDuesFromCredit,
                        onCollect = { amount, method -> ownersViewModel.collectPayment(amount, method) },
                        onVoidPayment = ownersViewModel::voidPayment,
                        onSetStatus = { status -> ownersViewModel.setStatus(workspace.ownerId, status) },
                        onEdit = { navController.navigate("${Screen.OwnerForm.route}/${workspace.ownerId}") },
                        onGenerate = {
                            generationViewModel.preselectOwner(workspace.ownerId)
                            navController.navigate(Screen.GeneratorWizard.route)
                        },
                        onOpenInvoice = { invoiceId -> navController.navigate("${Screen.InvoiceDetail.route}/$invoiceId") },
                        onArchive = { ownersViewModel.archiveOwner(workspace.ownerId) },
                        onBack = { navController.popBackStack() }
                    )
                } ?: Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.owner_load_failed))
                }

            }

            // بعد الأرشفة يعود المستخدم للدليل (يختفي منه المالك المؤرشف) وتظهر رسالة النجاح هناك.
            // خارج الشرط أعلاه كي لا يتوقف المُجمِّع أثناء حالة التحميل فيضيع الحدث.
            LaunchedEffect(Unit) {
                ownersViewModel.events.collect { event ->
                    if (event is OwnersEvent.OwnerArchived) navController.popBackStack()
                }
            }
        }

        // S2: إعدادات الراوتر
        composable(Screen.RouterSetup.route) {
            // أول تشغيل: S2 هي الوجهة الوحيدة في المكدّس فلا رجوع، وبعد الحفظ نتقدّم للرئيسية.
            // من الإعدادات (S24): رجوع عادي بعد الحفظ.
            val canNavigateBack = navController.previousBackStackEntry != null
            S2_RouterSetupScreen(
                viewModel = routerViewModel,
                canNavigateBack = canNavigateBack,
                onBack = { navController.popBackStack() },
                onSaved = {
                    if (canNavigateBack) {
                        navController.popBackStack()
                    } else {
                        // مسار الإقلاع: لا نفتح S3 مباشرة؛ نعيد سلسلة الفحص (اتصال ثم User Manager) عبر بوابة الإقلاع
                        navController.navigate(Screen.Splash.route) {
                            popUpTo(navController.graph.id) { inclusive = true }
                        }
                    }
                },
                showStartupFailure = !canNavigateBack && mgtikState.startupStage != StartupStage.READY,
                startupFailureCode = mgtikState.startupErrorCode
            )
        }

        // S35: تشخيص الاتصال
        composable(Screen.ConnectionDiagnostics.route) {
            S35_ConnectionDiagnosticsScreen(
                viewModel = routerStatusViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        // S32: إعداد واتصال User Manager
        composable(Screen.UserManSetup.route) {
            // مسار الإقلاع (S32 أول وجهة): عند ثبوت جاهزية User Manager نعيد حسم الوجهة عبر بوابة الإقلاع
            val umStartupMode = navController.previousBackStackEntry == null
            val umState by userManViewModel.uiState.collectAsState()
            val umReady = umState.status?.isReady == true
            LaunchedEffect(umStartupMode, umReady) {
                if (umStartupMode && umReady) {
                    mgtikViewModel.onUserManVerified()
                    navController.navigate(Screen.Splash.route) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                }
            }
            S32_UserManSetupScreen(
                viewModel = userManViewModel,
                onBack = { navController.popBackStack() },
                onOpenRouterSetup = { navController.navigate(Screen.RouterSetup.route) { launchSingleTop = true } }
            )
        }

        // S24: الإعدادات العامة
        composable(Screen.Settings.route) {
            S24_SettingsScreen(
                viewModel = settingsViewModel,
                userManViewModel = userManViewModel,
                onNavigateToRouterSetup = { navController.navigate(Screen.RouterSetup.route) { launchSingleTop = true } },
                onOpenUserManSetup = { navController.navigate(Screen.UserManSetup.route) { launchSingleTop = true } },
                onOpenOwners = { navController.navigate(Screen.OwnerDirectory.route) { launchSingleTop = true } },
                onOpenOrders = { navController.navigate(Screen.OrdersList.route) { launchSingleTop = true } },
                onOpenInvoices = { navController.navigate(Screen.InvoicesList.route) { launchSingleTop = true } },
                onOpenUsers = { navController.navigate(Screen.UserManagement.route) { launchSingleTop = true } },
                onOpenProfiles = { navController.navigate(Screen.Profiles.route) { launchSingleTop = true } },
                onOpenHistory = { navController.navigate(Screen.HistoryBatches.route) { launchSingleTop = true } },
                onOpenArchive = { navController.navigate(Screen.HistoricalUsersArchive.route) { launchSingleTop = true } },
                onOpenTemplates = { navController.navigate(Screen.TemplatePicker.route) { launchSingleTop = true } },
                onOpenQueue = { navController.navigate(Screen.OfflineQueue.route) { launchSingleTop = true } },
                authState = authState,
                onChangePassword = authViewModel::changePassword,
                onPasswordDialogClosed = authViewModel::clearPasswordMessages
            )
        }
        
        // S17: باقات السرعة
        composable(Screen.Profiles.route) {
            S17_ProfilesScreen(
                viewModel = profilesViewModel,
                onBack = { navController.popBackStack() },
                onOpenCategories = { navController.navigate(Screen.ProfileCategory.route) },
                onOpenClassifications = { navController.navigate(Screen.ProfileClassification.route) },
                onOpenUmProfiles = { navController.navigate("${Screen.UmProfiles.route}?focus=") { launchSingleTop = true } },
                onOpenUmLimitations = { navController.navigate("${Screen.UmLimitations.route}?focus=") { launchSingleTop = true } },
                onOpenProfile = { id -> navController.navigate("${Screen.ProfileDetail.route}/$id") { launchSingleTop = true } }
            )
        }

        // S34: User Manager Profiles كمكوّن مستقل (§5)
        composable(
            route = "${Screen.UmProfiles.route}?focus={focus}",
            arguments = listOf(navArgument("focus") { type = NavType.StringType; defaultValue = "" })
        ) { entry ->
            val vm = androidx.hilt.navigation.compose.hiltViewModel<ps.mgtik.hotspot.ui.viewmodel.UmComponentsViewModel>()
            S34_UmProfilesScreen(
                viewModel = vm,
                initialQuery = android.net.Uri.decode(entry.arguments?.getString("focus") ?: ""),
                onBack = { navController.popBackStack() },
                onOpenLimitation = { n -> navController.navigate("${Screen.UmLimitations.route}?focus=${android.net.Uri.encode(n)}") { launchSingleTop = true } }
            )
        }

        // S33: User Manager Limitations كمكوّن مستقل (§6)
        composable(
            route = "${Screen.UmLimitations.route}?focus={focus}",
            arguments = listOf(navArgument("focus") { type = NavType.StringType; defaultValue = "" })
        ) { entry ->
            val vm = androidx.hilt.navigation.compose.hiltViewModel<ps.mgtik.hotspot.ui.viewmodel.UmComponentsViewModel>()
            S33_UmLimitationsScreen(
                viewModel = vm,
                initialQuery = android.net.Uri.decode(entry.arguments?.getString("focus") ?: ""),
                onBack = { navController.popBackStack() },
                onOpenProfile = { n -> navController.navigate("${Screen.UmProfiles.route}?focus=${android.net.Uri.encode(n)}") { launchSingleTop = true } }
            )
        }

        // S17D: صفحة تفاصيل الباقة (بيانات إدارية + تشغيلية + تجارية، وكل إجراءات الباقة)
        composable(
            route = "${Screen.ProfileDetail.route}/{profileId}",
            arguments = listOf(navArgument("profileId") { type = NavType.StringType })
        ) { backStackEntry ->
            S17b_ProfileDetailScreen(
                profileId = backStackEntry.arguments?.getString("profileId") ?: "",
                viewModel = profilesViewModel,
                onBack = { navController.popBackStack() },
                onOpenUmProfile = { n -> navController.navigate("${Screen.UmProfiles.route}?focus=${android.net.Uri.encode(n)}") { launchSingleTop = true } },
                onOpenUmLimitation = { n -> navController.navigate("${Screen.UmLimitations.route}?focus=${android.net.Uri.encode(n)}") { launchSingleTop = true } }
            )
        }

        // S18: فئات الباقات (كان معرّفاً في Screen.kt لكن غير مسجَّل هنا، فلم تكن الشاشة قابلة للوصول إطلاقاً)
        composable(Screen.ProfileCategory.route) {
            S18_ProfileCategoryScreen(
                viewModel = profilesViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        // S19: تصنيف الباقات (نفس حالة S18 — غير مسجَّلة سابقاً)
        composable(Screen.ProfileClassification.route) {
            S19_ProfileClassificationScreen(
                viewModel = profilesViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        // S7: لوحة الفوترة (الاشتراكات: البطاقة تفتح التفاصيل؛ الإنشاء والعمليات في S27/S28)
        composable(Screen.BillingDashboard.route) {
            S7_BillingDashboardScreen(
                state = mgtikState,
                successMessageResId = subscriptionsState.successMessageResId ?: subscriptionsState.warningMessageResId,
                errorMessageResId = subscriptionsState.errorMessageResId,
                onMessageShown = subscriptionsViewModel::clearMessages,
                onOpenCreateSubscription = { navController.navigate(Screen.SubscriptionForm.route) },
                onSelectSubscription = { sub -> navController.navigate("${Screen.SubscriptionDetail.route}/${sub.id}") },
                onSelectInvoice = { inv -> navController.navigate("${Screen.InvoiceDetail.route}/${inv.id}") },
                onSelectOrder = { ord -> navController.navigate("${Screen.OrderDetail.route}/${ord.id}") },
                onPeriodSelected = mgtikViewModel::selectBillingPeriod
            )
        }

        // S27: اشتراك جديد
        composable(Screen.SubscriptionForm.route) {
            val profilesState by profilesViewModel.uiState.collectAsState()
            // الاشتراك لا يُنشأ إلا على باقة فئتها مصنّفة SUBSCRIPTION (الباقة ترث تصنيف فئتها)
            val activeProfiles = profilesState.profiles.map { ps.mgtik.hotspot.domain.usecase.CategoryRules.withRetailDisplay(it.profile, profilesState.categories) }.filter {
                it.status == ProfileStatus.ACTIVE && it.id.isNotBlank() &&
                    ps.mgtik.hotspot.domain.usecase.SystemClassRules.classOf(it, profilesState.categories) == ps.mgtik.hotspot.domain.model.SystemClass.SUBSCRIPTION
            }
            // بعد نجاح الإنشاء: افتح تفاصيل الاشتراك واستبدل النموذج بها (لا عودة إلى نموذج مملوء)
            LaunchedEffect(subscriptionsState.createdId) {
                val createdId = subscriptionsState.createdId
                if (createdId != null) {
                    subscriptionsViewModel.consumeCreated()
                    navController.navigate("${Screen.SubscriptionDetail.route}/$createdId") {
                        popUpTo(Screen.SubscriptionForm.route) { inclusive = true }
                    }
                }
            }
            S27_SubscriptionFormScreen(
                profiles = activeProfiles,
                isCreating = subscriptionsState.isCreating,
                errorResId = subscriptionsState.createErrorResId,
                onSubmit = { draft, subscriptionId -> subscriptionsViewModel.create(draft, subscriptionId) },
                onBack = { navController.popBackStack() }
            )
        }

        // S28: تفاصيل الاشتراك
        composable(
            route = "${Screen.SubscriptionDetail.route}/{subscriptionId}",
            arguments = listOf(navArgument("subscriptionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val subscriptionId = backStackEntry.arguments?.getString("subscriptionId") ?: ""
            LaunchedEffect(subscriptionId) { subscriptionsViewModel.select(subscriptionId) }
            DisposableEffect(subscriptionId) { onDispose { subscriptionsViewModel.clearSelection() } }
            S27_SubscriptionDetailScreen(
                subscription = mgtikState.subscriptions.firstOrNull { it.id == subscriptionId },
                state = subscriptionsState,
                onRenew = { subscriptionsViewModel.renew(subscriptionId) },
                onSuspend = { subscriptionsViewModel.suspend(subscriptionId) },
                onResume = { subscriptionsViewModel.resume(subscriptionId) },
                onCancel = { subscriptionsViewModel.cancel(subscriptionId) },
                onRetrySync = { subscriptionsViewModel.retrySync(subscriptionId) },
                onRefreshRouter = { subscriptionsViewModel.refreshFromRouter(subscriptionId) },
                onRecordPayment = { invoiceId, amount, method -> subscriptionsViewModel.recordPayment(subscriptionId, invoiceId, amount, method) },
                onVoidPayment = { paymentId -> subscriptionsViewModel.voidPayment(subscriptionId, paymentId) },
                onMessageShown = subscriptionsViewModel::clearMessages,
                onBack = { navController.popBackStack() }
            )
        }

        // S8: قائمة الطلبات
        composable(Screen.OrdersList.route) {
            S8_OrdersListScreen(
                orders = mgtikState.orders,
                onSelectOrder = { ord -> navController.navigate("${Screen.OrderDetail.route}/${ord.id}") },
                onBack = { navController.popBackStack() }
            )
        }

        // S9: تفاصيل الطلب
        composable(
            route = "${Screen.OrderDetail.route}/{orderId}",
            arguments = listOf(navArgument("orderId") { type = NavType.StringType })
        ) { backStackEntry ->
            val orderId = backStackEntry.arguments?.getString("orderId") ?: ""
            
            LaunchedEffect(orderId) {
                ordersViewModel.selectOrder(orderId)
            }

            // لا نعرض إلا الطلب المطابق للمعرّف الحالي (لا بقايا طلب سابق)
            val order = ordersState.selectedOrder?.takeIf { it.id == orderId }
            if (order != null) {
                // الفاتورة المرتبطة بالطلب من الحالة المركزية الحية (لا null ثابتة)
                val orderInvoice = mgtikState.invoices.firstOrNull { it.id == order.invoiceId || it.orderId == orderId }
                S9_OrderDetailScreen(
                    order = order,
                    batches = ordersState.selectedOrderBatches,
                    invoice = orderInvoice,
                    owners = mgtikState.owners,
                    isProcessing = ordersState.isProcessing,
                    successMessageResId = ordersState.successMessageResId,
                    errorMessageResId = ordersState.errorMessageResId,
                    onMessageShown = ordersViewModel::clearMessages,
                    onBack = { navController.popBackStack() },
                    // إصدار الفاتورة يتم بإغلاق الطلب (المسار المعتمد في CloseOrderUseCase)
                    onIssueInvoice = { ordersViewModel.closeOrder(it) },
                    onCancelOrder = ordersViewModel::cancelDraftOrder,
                    onReopenOrder = ordersViewModel::reopenOrder,
                    onRetryBilling = ordersViewModel::retryBilling,
                    onCorrectOwner = ordersViewModel::correctOwner,
                    onOpenInvoice = { inv -> navController.navigate("${Screen.InvoiceDetail.route}/${inv.id}") },
                    onOpenBatch = { batch -> navController.navigate("${Screen.GenerationResult.route}/${batch.id}") }
                )
            } else if (ordersState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                MissingDestinationContent(
                    messageResId = R.string.order_not_found,
                    onBack = { navController.popBackStack() }
                )
            }
        }

        // S10: قائمة الفواتير
        composable(Screen.InvoicesList.route) {
            S10_InvoicesListScreen(
                invoices = mgtikState.invoices,
                isLoading = !mgtikState.isInitialSyncChecked,
                onSelectInvoice = { inv -> navController.navigate("${Screen.InvoiceDetail.route}/${inv.id}") },
                onBack = { navController.popBackStack() }
            )
        }

        // S11: تفاصيل الفاتورة
        composable(
            route = "${Screen.InvoiceDetail.route}/{invoiceId}",
            arguments = listOf(navArgument("invoiceId") { type = NavType.StringType })
        ) { backStackEntry ->
            val invoiceId = backStackEntry.arguments?.getString("invoiceId") ?: ""
            val invoice = mgtikState.invoices.find { it.id == invoiceId }

            // ننظّف رسائل عملية سابقة عند فتح فاتورة جديدة
            if (invoice != null) {
                // الدفعات ودفعات الطلب تُتابَع لهذه الفاتورة فتنعكس أي عملية فوراً على التفاصيل وملف PDF
                LaunchedEffect(invoice.id) { billingViewModel.observeInvoice(invoice); billingViewModel.loadInvoiceEvents(invoice.id) }
                S11_InvoiceDetailScreen(
                    invoice = invoice,
                    payments = billingState.invoicePayments,
                    events = billingState.selectedInvoiceEvents,
                    pdf = billingState.pdf,
                    isProcessing = billingState.isLoading,
                    successMessageResId = billingState.successMessageResId,
                    errorMessageResId = billingState.errorMessageResId,
                    onMessageShown = billingViewModel::clearMessages,
                    onRecordPayment = { amount, method -> billingViewModel.recordPayment(invoiceId, amount, method) },
                    onApplyCredit = { amount -> billingViewModel.applyCredit(invoiceId, amount) },
                    onApplyDiscount = { type, value -> billingViewModel.applyDiscount(invoiceId, type, value) },
                    onCancelInvoice = { force, res -> billingViewModel.cancelInvoice(invoiceId, force, res) },
                    onReopen = { billingViewModel.reopenInvoice(invoiceId) },
                    onVoidPayment = { p, reason -> billingViewModel.voidPayment(p.id, reason) },
                    onRefundPayment = { p, amount -> billingViewModel.refundPayment(p.id, amount) },
                    onSetDueDate = { millis -> billingViewModel.setDueDate(invoiceId, millis) },
                    onPreparePdf = { billingViewModel.preparePdf(invoice) },
                    onClearPdf = billingViewModel::clearPdf,
                    onBack = { navController.popBackStack() }
                )
            } else if (!mgtikState.isInitialSyncChecked) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                // كانت الشاشة تبقى فارغة تماماً عند فاتورة غير موجودة
                MissingDestinationContent(
                    messageResId = R.string.invoice_not_found,
                    onBack = { navController.popBackStack() }
                )
            }
        }

        // S3: نظرة عامة — كانت إحدى تبويبات الشريط السفلي الخمسة، وكانت غير مسجَّلة إطلاقاً
        // (الضغط عليها كان يعطّل التطبيق بخطأ "destination not found").
        composable(Screen.Overview.route) {
            val usersState by usersViewModel.uiState.collectAsState()
            val liveState by liveSessionsViewModel.uiState.collectAsState()
            // تحديث دوري للمتصلين ما دامت نظرة عامة مرئية فقط
            ps.mgtik.hotspot.ui.components.live.LiveSessionsPolling(liveSessionsViewModel)
            S3_OverviewScreen(
                state = mgtikState,
                onNavigateToRouterSetup = { navController.navigate(Screen.RouterSetup.route) },
                onNavigateToGenerator = { navController.navigate(Screen.GeneratorWizard.route) },
                onNavigateToProfiles = { navController.navigate(Screen.Profiles.route) },
                onNavigateToSearch = { navController.navigate(Screen.UsersSearch.route) },
                onOpenNotifications = onOpenNotifications,
                onOpenQueue = { navController.navigate(Screen.OfflineQueue.route) { launchSingleTop = true } },
                onRefresh = { mgtikViewModel.refreshCloudData(); liveSessionsViewModel.refresh() },
                // المراقبة العامة: المتصلون ← بحث المتصلين (S15)، والاشتراكات/المدفوعات/الدخل ← المالية والفوترة (S7)
                onOpenActiveUsers = { navController.navigate(Screen.ActiveUsers.route) { launchSingleTop = true } },
                liveSessions = liveState,
                onOpenSubscriptionsOverview = { navController.navigate(Screen.BillingDashboard.route) { launchSingleTop = true } },
                onOpenFinancials = { navController.navigate(Screen.BillingDashboard.route) { launchSingleTop = true } },
                // الوصول السريع: وجهات مسجَّلة فعلاً في هذا المخطط
                onOpenUsersManagement = { navController.navigate(Screen.UserManagement.route) { launchSingleTop = true } },
                onOpenMonthlySubscriptions = { navController.navigate(Screen.BillingDashboard.route) { launchSingleTop = true } },
                onOpenInvoices = { navController.navigate(Screen.InvoicesList.route) { launchSingleTop = true } },
                onOpenReports = { navController.navigate(Screen.Reports.route) { launchSingleTop = true } },
                // إجراءات سريعة: نماذج التنفيذ الموجودة نفسها (لا منطق مكرر)
                onCreateSingleUser = { navController.navigate(Screen.UserCreate.route) { launchSingleTop = true } },
                onCreateMonthlySubscription = { navController.navigate(Screen.SubscriptionForm.route) { launchSingleTop = true } },
                onAddProfile = {
                    profilesViewModel.openCreateDialog() // يفتح حوار الإنشاء نفسه في S17
                    navController.navigate(Screen.Profiles.route) { launchSingleTop = true }
                },
                onAddCategory = {
                    profilesViewModel.openCreateCategoryDialog() // يفتح حوار الإنشاء نفسه في S18
                    navController.navigate(Screen.ProfileCategory.route) { launchSingleTop = true }
                },
                // الفاتورة تُصدَر بإغلاق طلب مسودة (S9 ← CloseOrderUseCase)؛ لا مسار إصدار مستقل خارج الطلب
                onIssueInvoice = { navController.navigate(Screen.OrdersList.route) { launchSingleTop = true } },
                onSyncUsers = usersViewModel::syncWithRouter,
                isSyncingUsers = usersState.isSyncing,
                syncSuccessResId = usersState.successMessageResId,
                syncErrorResId = usersState.errorMessageResId,
                onSyncMessageShown = usersViewModel::clearMessages,
                unreadNotificationsCount = notificationState.unreadCount
            )
        }

        // S4 → S5 → S6 → التصدير. التنفيذ يعمل في الخلفية (WorkManager) ويُستأنف بعد إغلاق التطبيق،
        // لذا S5 وS6 تُفتحان بمعرّف (تنفيذ/دفعة) وتقرآن حالتهما من Room.
        composable(Screen.GeneratorWizard.route) {
            val genState by generationViewModel.uiState.collectAsState()
            // مالك مُختار مسبقاً من ملفه (يُستهلك مرة واحدة)
            val initialOwnerId = remember { generationViewModel.consumePreselectedOwner() }
            S4_GeneratorWizardScreen(
                state = mgtikState,
                generationState = genState,
                initialOwnerId = initialOwnerId,
                onStartGeneration = { profile, input -> generationViewModel.startGeneration(profile, input) },
                onFieldEdited = generationViewModel::clearFieldError,
                onProfileSelected = generationViewModel::observePricing,
                defaultsFor = generationViewModel::defaultsFor
            )
            // التسجيل المحلي نجح (معرّف حقيقي) ⇒ انتقل لشاشة التقدم، حدث لمرة واحدة
            LaunchedEffect(Unit) {
                generationViewModel.events.collect { event ->
                    if (event is GenerationEvent.Started) navController.navigate("${Screen.ExecutionProgress.route}/${event.executionId}")
                }
            }
        }

        composable(
            route = "${Screen.ExecutionProgress.route}/{executionId}",
            arguments = listOf(navArgument("executionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val executionId = backStackEntry.arguments?.getString("executionId") ?: ""
            val genState by generationViewModel.uiState.collectAsState()
            LaunchedEffect(executionId) { generationViewModel.observeExecution(executionId) }

            val execution = genState.activeExecution?.takeIf { it.executionId == executionId }
            if (execution != null) {
                S5_ExecutionProgressScreen(
                    execution = execution,
                    batch = genState.activeBatch?.takeIf { it.id == execution.contextId },
                    failureReasons = genState.failureReasons,
                    onCancel = { generationViewModel.cancel(executionId) },
                    onRetry = { generationViewModel.retry(executionId) },
                    onBackToOverview = {
                        navController.navigate(Screen.Overview.route) { popUpTo(Screen.GeneratorWizard.route) { inclusive = true } }
                    }
                )
                // اكتمل ⇒ النتيجة (يستبدل التقدم في المكدّس فلا يعود إليه المستخدم بزر الرجوع)
                LaunchedEffect(execution.status) {
                    if (execution.status == ExecutionStatus.COMPLETED && !execution.contextId.isNullOrBlank()) {
                        navController.navigate("${Screen.GenerationResult.route}/${execution.contextId}") {
                            popUpTo("${Screen.ExecutionProgress.route}/{executionId}") { inclusive = true }
                        }
                    }
                }
            } else if (genState.executionResolved) {
                MissingDestinationContent(R.string.execution_not_found) { navController.popBackStack() }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            GenerationErrorHost(genState.errorMessageResId, generationViewModel::consumeError)
        }

        composable(
            route = "${Screen.GenerationResult.route}/{batchId}",
            arguments = listOf(navArgument("batchId") { type = NavType.StringType })
        ) { backStackEntry ->
            val batchId = backStackEntry.arguments?.getString("batchId") ?: ""
            val genState by generationViewModel.uiState.collectAsState()
            LaunchedEffect(batchId) { generationViewModel.observeBatch(batchId) }

            val batch = genState.activeBatch?.takeIf { it.id == batchId }
            if (batch != null) {
                S6_GenerationResultScreen(
                    batch = batch,
                    failureReasons = genState.failureReasons,
                    onExport = { navController.navigate("${Screen.VoucherPrintStudio.route}/${batch.id}") },
                    onOpenOrderDetail = { navController.navigate("${Screen.OrderDetail.route}/${batch.orderId}") },
                    onDone = {
                        generationViewModel.resetState()
                        navController.navigate(Screen.Overview.route) { popUpTo(Screen.GeneratorWizard.route) { inclusive = true } }
                    }
                )
            } else if (genState.batchResolved) {
                MissingDestinationContent(R.string.batch_not_found) { navController.popBackStack() }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            GenerationErrorHost(genState.errorMessageResId, generationViewModel::consumeError)
        }

        // تصدير دفعة كاملة (PDF / TXT)
        composable(
            route = "${Screen.VoucherPrintStudio.route}/{batchId}",
            arguments = listOf(navArgument("batchId") { type = NavType.StringType })
        ) { backStackEntry ->
            VoucherExportScreen(
                viewModel = exportViewModel,
                batchId = backStackEntry.arguments?.getString("batchId") ?: "",
                onBack = { navController.popBackStack() }
            )
        }

        // S15: البحث في المتصلين — كانت غير مسجَّلة
        composable(Screen.UsersSearch.route) {
            S15_UsersSearchScreen(
                state = mgtikState,
                onBack = { navController.popBackStack() },
                onKickSession = mgtikViewModel::disconnectSession
            )
        }

        // S16: إدارة المشتركين — كانت غير مسجَّلة (التبويب الوحيد من شاشات المستخدمين القابل للوصول كان S12 الملاك)
        composable(Screen.UserManagement.route) {
            S16_UsersManagementScreen(
                viewModel = usersViewModel,
                owners = mgtikState.owners,
                ownerNameIndex = mgtikState.allOwners,
                onOpenArchive = { navController.navigate(Screen.HistoricalUsersArchive.route) { launchSingleTop = true } },
                onEditUser = { uuid -> navController.navigate("${Screen.UserEdit.route}/$uuid") { launchSingleTop = true } },
                onOpenUser = { uuid -> navController.navigate("${Screen.UserDetail.route}/$uuid") { launchSingleTop = true } },
                onCreateUser = { navController.navigate(Screen.UserCreate.route) { launchSingleTop = true } },
                onOpenQueue = { navController.navigate(Screen.OfflineQueue.route) { launchSingleTop = true } },
                subscriptions = mgtikState.subscriptions,
                subscriptionsLoaded = mgtikState.isInitialSyncChecked,
                onOpenSubscription = { id -> navController.navigate("${Screen.SubscriptionDetail.route}/$id") { launchSingleTop = true } },
                onBack = { navController.popBackStack() }
            )
        }

        // S16D: صفحة تفاصيل المستخدم (إدارية + تشغيلية)؛ تُفتح بالضغط على المستخدم في S16، وتحتفظ بكل إجراءاته
        composable(
            route = "${Screen.UserDetail.route}/{recordUuid}",
            arguments = listOf(navArgument("recordUuid") { type = NavType.StringType })
        ) { backStackEntry ->
            val liveState by liveSessionsViewModel.uiState.collectAsState()
            val profilesState by profilesViewModel.uiState.collectAsState()
            // الجلسة الحية من المصدر المشترك نفسه (تحديث دوري ما دامت الصفحة مرئية)
            ps.mgtik.hotspot.ui.components.live.LiveSessionsPolling(liveSessionsViewModel)
            S16_UserDetailScreen(
                recordUuid = backStackEntry.arguments?.getString("recordUuid") ?: "",
                usersViewModel = usersViewModel,
                detailViewModel = userDetailViewModel,
                liveSessions = liveState,
                owners = mgtikState.owners,
                ownerNameIndex = mgtikState.allOwners,
                subscriptions = mgtikState.subscriptions,
                subscriptionsLoaded = mgtikState.isInitialSyncChecked,
                profiles = profilesState.profiles.map { it.profile },
                categories = profilesState.categories,
                classifications = profilesState.classifications,
                onEdit = { uuid -> navController.navigate("${Screen.UserEdit.route}/$uuid") { launchSingleTop = true } },
                onOpenSubscription = { id -> navController.navigate("${Screen.SubscriptionDetail.route}/$id") { launchSingleTop = true } },
                onOpenQueue = { navController.navigate(Screen.OfflineQueue.route) { launchSingleTop = true } },
                onBack = { navController.popBackStack() }
            )
        }

        // S16 (إنشاء): مستخدم فردي NORMAL أو مشترك شهري SUBSCRIPTION من إدارة المستخدمين
        composable(Screen.UserCreate.route) {
            val profilesState by profilesViewModel.uiState.collectAsState()
            // الباقة ترث تصنيف فئتها: NORMAL للمستخدم العادي، SUBSCRIPTION للمشترك الشهري (لا تخمين لباقات بلا فئة مصنّفة)
            val activeProfiles = profilesState.profiles.map { ps.mgtik.hotspot.domain.usecase.CategoryRules.withRetailDisplay(it.profile, profilesState.categories) }.filter {
                it.status == ProfileStatus.ACTIVE && it.id.isNotBlank()
            }
            fun profilesOf(c: ps.mgtik.hotspot.domain.model.SystemClass) = activeProfiles.filter {
                ps.mgtik.hotspot.domain.usecase.SystemClassRules.classOf(it, profilesState.categories) == c
            }
            S16_UserCreateScreen(
                viewModel = usersViewModel,
                normalProfiles = profilesOf(ps.mgtik.hotspot.domain.model.SystemClass.NORMAL),
                subscriptionProfiles = profilesOf(ps.mgtik.hotspot.domain.model.SystemClass.SUBSCRIPTION),
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }

        // S16 (تعديل): تعديل الحقول المسموحة لمستخدم مُدار
        composable(
            route = "${Screen.UserEdit.route}/{recordUuid}",
            arguments = listOf(navArgument("recordUuid") { type = NavType.StringType })
        ) { backStackEntry ->
            S16_UserEditScreen(
                viewModel = usersViewModel,
                recordUuid = backStackEntry.arguments?.getString("recordUuid") ?: "",
                owners = mgtikState.owners,
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }

        // S20: مركز الإشعارات — كانت غير مسجَّلة
        composable(Screen.NotificationCenter.route) {
            S20_NotificationCenterScreen(
                viewModel = notificationViewModel,
                onOpenTarget = { type, id -> navController.navigateToNotificationTarget(type, id) },
                onBack = { navController.popBackStack() }
            )
        }

        // S25: التقارير — كانت التبويب الثالث المعطَّل من شريط التنقل السفلي
        composable(Screen.Reports.route) {
            S25_ReportsScreen(
                viewModel = reportsViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        // S30: المستخدمون النشطون (الجلسات الحية من RouterOS)
        composable(Screen.ActiveUsers.route) {
            val liveState by liveSessionsViewModel.uiState.collectAsState()
            ps.mgtik.hotspot.ui.components.live.LiveSessionsPolling(liveSessionsViewModel)
            S30_ActiveUsersScreen(
                state = liveState,
                onRefresh = liveSessionsViewModel::refresh,
                onDisconnect = liveSessionsViewModel::disconnect,
                onOpenUser = { session ->
                    navController.navigate("${Screen.UserWorkspace.route}/${android.net.Uri.encode(session.username)}") { launchSingleTop = true }
                },
                onMessageShown = liveSessionsViewModel::clearMessage,
                onBack = { navController.popBackStack() }
            )
        }

        // S31: مساحة عمل المستخدم (من المستخدمين النشطين). الجلسة الحية من المصدر المشترك نفسه.
        composable(
            route = "${Screen.UserWorkspace.route}/{username}",
            arguments = listOf(navArgument("username") { type = NavType.StringType })
        ) { backStackEntry ->
            val username = backStackEntry.arguments?.getString("username").orEmpty()
            val workspaceViewModel = userWorkspaceViewModel
            val liveState by liveSessionsViewModel.uiState.collectAsState()
            val wsState by workspaceViewModel.uiState.collectAsState()
            ps.mgtik.hotspot.ui.components.live.LiveSessionsPolling(liveSessionsViewModel)
            LaunchedEffect(username) { workspaceViewModel.open(username) }
            // بعد فصل/تعطيل/تفعيل: تحديث الجلسات الحية فوراً ليختفي المستخدم أو يظهر دون انتظار الدورة
            var wasBusy by remember { mutableStateOf(false) }
            LaunchedEffect(wsState.busy) {
                if (wsState.busy != null) wasBusy = true
                else if (wasBusy) { wasBusy = false; liveSessionsViewModel.refresh() }
            }
            S31_UserWorkspaceScreen(
                username = username,
                live = liveState,
                ws = wsState,
                onRefresh = { workspaceViewModel.refresh(); liveSessionsViewModel.refresh() },
                onDisconnect = liveSessionsViewModel::disconnect,
                onDisable = workspaceViewModel::disable,
                onEnable = workspaceViewModel::enable,
                onLoadHistory = workspaceViewModel::loadHistory,
                onOpenFullProfile = { uuid -> navController.navigate("${Screen.UserEdit.route}/$uuid") { launchSingleTop = true } },
                onOpenSubscription = { id -> navController.navigate("${Screen.SubscriptionDetail.route}/$id") { launchSingleTop = true } },
                onLiveMessageShown = liveSessionsViewModel::clearMessage,
                onWorkspaceMessageShown = workspaceViewModel::clearMessage,
                onBack = { navController.popBackStack() }
            )
        }

        // S29: طابور المزامنة (الأوامر المعلّقة والفاشلة)
        composable(Screen.OfflineQueue.route) {
            val commands by mgtikViewModel.pendingCommands.collectAsState()
            S29_OfflineQueueScreen(
                commands = commands,
                onRetry = mgtikViewModel::retryCommand,
                onDiscard = mgtikViewModel::discardCommand,
                onBack = { navController.popBackStack() }
            )
        }

        // S21/S22/S23: شاشات مكتملة لكنها لم تكن مسجَّلة في المخطط فلا يمكن الوصول إليها (تُفتح من S24)
        composable(Screen.HistoryBatches.route) {
            S21_HistoryScreen(
                viewModel = historyViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.HistoricalUsersArchive.route) {
            S22_HistoricalUsersArchiveScreen(
                viewModel = usersViewModel,
                owners = mgtikState.owners,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.TemplatePicker.route) {
            S23_TemplatePickerScreen(
                viewModel = mgtikViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

/** أخطاء التحكم بالتوليد (إلغاء/إعادة/مراقبة) كانت تُخزَّن في الحالة دون أن تُعرض في S5/S6. */
@Composable
private fun GenerationErrorHost(@androidx.annotation.StringRes resId: Int?, onShown: () -> Unit) {
    val hostState = remember { androidx.compose.material3.SnackbarHostState() }
    val text = resId?.let { stringResource(it) }
    LaunchedEffect(resId) {
        if (text != null) {
            hostState.showSnackbar(text)
            onShown()
        }
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        androidx.compose.material3.SnackbarHost(hostState)
    }
}

/** وجهة بياناتها غير متاحة (محذوفة/غير موجودة): رسالة واضحة وزر رجوع بدل شاشة فارغة. */
@Composable
private fun MissingDestinationContent(@androidx.annotation.StringRes messageResId: Int, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(messageResId))
            androidx.compose.material3.TextButton(onClick = onBack) {
                Text(stringResource(R.string.common_back))
            }
        }
    }
}

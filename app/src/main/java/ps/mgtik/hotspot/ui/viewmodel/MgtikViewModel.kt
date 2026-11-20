package ps.mgtik.hotspot.ui.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.repository.IMgtikRepository
import ps.mgtik.hotspot.domain.report.BillingPeriod
import ps.mgtik.hotspot.domain.repository.RouterConnectionEvent
import ps.mgtik.hotspot.domain.usecase.*
import javax.inject.Inject

/**
 * MgtikViewModel - المحرك المركزي الموحد للنظام (§8).
 * تم تحديثه لمطابقة العقود النهائية للمستودعات وإزالة الدوال المتقادمة.
 */
@HiltViewModel
class MgtikViewModel @Inject constructor(
    private val repository: IMgtikRepository,
    private val offlineCommandRepository: ps.mgtik.hotspot.domain.repository.IOfflineCommandRepository,
    private val offlineScheduler: ps.mgtik.hotspot.domain.repository.IOfflineScheduler,
    private val getSessionStateUseCase: GetSessionStateUseCase,
    private val disconnectActiveUserUseCase: DisconnectActiveUserUseCase,
    private val observeRouterStatusUseCase: ObserveRouterStatusUseCase,
    private val listAllPaymentsUseCase: ListAllPaymentsUseCase,
    private val userManSource: ps.mgtik.hotspot.data.userman.UserManSource
) : ViewModel() {

    private companion object {
        const val MAX_AUTO_RETRIES = 3
        const val RETRY_BASE_DELAY_MS = 5_000L
    }

    private val _uiState = MutableStateFlow(MgtikUiState())
    val uiState: StateFlow<MgtikUiState> = _uiState.asStateFlow()

    /** الأوامر المعلّقة/الفاشلة لشاشة الطابور (S29). */
    val pendingCommands: StateFlow<List<OfflineCommand>> = offlineCommandRepository.getPendingCommands()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** إعادة أمر (غالباً فاشل نهائياً): تصفير المحاولات والخطأ ثم تشغيل المزامنة. الإدراج REPLACE فالعملية ذرّية. */
    fun retryCommand(command: OfflineCommand) {
        viewModelScope.launch {
            try {
                offlineCommandRepository.addCommand(command.copy(attemptCount = 0, lastError = null))
                offlineScheduler.scheduleSync()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(messageResId = R.string.ERR_UNKNOWN) }
            }
        }
    }

    /** تجاهل أمر نهائياً (لن يُنفَّذ). الواجهة تطلب التأكيد قبل الاستدعاء. */
    fun discardCommand(commandId: String) {
        viewModelScope.launch {
            try {
                offlineCommandRepository.removeCommand(commandId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(messageResId = R.string.ERR_UNKNOWN) }
            }
        }
    }

    /** مراقبات Firestore: تعمل فقط أثناء وجود جلسة مصادَق عليها وتُلغى عند الخروج. */
    private var cloudObserversJob: Job? = null

    private val startupUserMan = MutableStateFlow(StartupUserMan.UNKNOWN)
    private var userManCheckJob: Job? = null

    init {
        observeAuthSession()
        observeRouterConfigState()
        observeRouterOverview()
        observeStartupUserMan()
        observeRouterEvents()
        observeTemplates()
        observeDefaultTemplate()
        observeOfflineCommands()
    }

    private fun observeAuthSession() {
        getSessionStateUseCase()
            .onEach { session ->
                _uiState.update {
                    val justSignedIn = session.isAuthenticated && !it.session.isAuthenticated
                    it.copy(
                        session = session,
                        isInitialAuthChecked = true,
                        // بلا مستخدم لا توجد بيانات سحابية لفحصها؛ ومع دخول جديد نبدأ الفحص من جديد
                        // (خطوة الراوتر تُحسب من الحالة المركزية في observeRouterConfigState)
                        isInitialSyncChecked = !session.isAuthenticated || (it.isInitialSyncChecked && !justSignedIn),
                        // عند الخروج لا تبقى بيانات المستخدم السابق في الذاكرة (تظهر لمن يدخل بعده)
                        subscriptions = if (session.isAuthenticated) it.subscriptions else emptyList(),
                        invoices = if (session.isAuthenticated) it.invoices else emptyList(),
                        orders = if (session.isAuthenticated) it.orders else emptyList(),
                        owners = if (session.isAuthenticated) it.owners else emptyList(),
                        activeSessions = if (session.isAuthenticated) it.activeSessions else emptyList(),
                        financialDashboard = if (session.isAuthenticated) it.financialDashboard else FinancialDashboard(),
                        periodSummary = if (session.isAuthenticated) it.periodSummary else null,
                        todayIncomeMinor = if (session.isAuthenticated) it.todayIncomeMinor else 0L,
                        subscriptionsLoad = if (session.isAuthenticated) it.subscriptionsLoad else DataLoad.LOADING,
                        financialLoad = if (session.isAuthenticated) it.financialLoad else DataLoad.LOADING,
                        todayIncomeLoad = if (session.isAuthenticated) it.todayIncomeLoad else DataLoad.LOADING
                    )
                }
            }
            .map { it.isAuthenticated }
            .distinctUntilChanged()
            .onEach { authenticated ->
                retryJob?.cancel()
                retryAttempt = 0
                cloudObserversJob?.cancel()
                cloudObserversJob = if (authenticated) observeCloudData() else null
            }
            .catch { markInitialChecksDone() }
            .launchIn(viewModelScope)
    }

    // مصادر الأرقام المالية: مستمع واحد لكل مجموعة (اشتراكات/فواتير/دفعات). null = لم تصل بعد أو فشلت.
    // منها يُشتق الملخص المالي ودخل اليوم معاً، بدل مستمعات مكررة لنفس المجموعات (قراءات Firestore مضاعفة).
    private val subscriptionsSource = MutableStateFlow<List<Subscription>?>(null)
    private val invoicesSource = MutableStateFlow<List<Invoice>?>(null)
    private val paymentsSource = MutableStateFlow<List<Payment>?>(null)
    private val subscriptionPaymentsSource = MutableStateFlow<List<SubscriptionPayment>?>(null)
    /** فترة لوحة Billing المختارة؛ الافتراضي «هذا الشهر» وتنتقل للشهر التالي وحدها (تُحسب حدودها من اليوم عند كل تحديث). */
    private val billingPeriodFlow = MutableStateFlow(BillingPeriod())

    fun selectBillingPeriod(period: BillingPeriod) {
        billingPeriodFlow.value = period
        _uiState.update { it.copy(billingPeriod = period) }
    }

    private var retryJob: Job? = null
    private var retryAttempt = 0

    /** إعادة تشغيل مراقبات السحابة يدوياً (زر التحديث) وتصفير عدّاد إعادة المحاولة التلقائية. */
    fun refreshCloudData() {
        retryAttempt = 0
        restartCloudObservers()
    }

    private fun restartCloudObservers() {
        retryJob?.cancel()
        cloudObserversJob?.cancel()
        cloudObserversJob = if (_uiState.value.session.isAuthenticated) observeCloudData() else null
    }

    /** بعد فشل مستمع: إعادة محاولة تلقائية بتأخير متزايد (5ث، 10ث، 20ث) ثم تبقى الحالة FAILED مع زر «إعادة المحاولة». */
    private fun scheduleCloudRetry() {
        if (retryJob?.isActive == true || retryAttempt >= MAX_AUTO_RETRIES) return
        val delayMs = RETRY_BASE_DELAY_MS shl retryAttempt
        retryAttempt++
        retryJob = viewModelScope.launch {
            kotlinx.coroutines.delay(delayMs)
            cloudObserversJob?.cancel()
            cloudObserversJob = if (_uiState.value.session.isAuthenticated) observeCloudData() else null
        }
    }

    private fun onCloudSourceFailed(markFinancial: Boolean = true) {
        _uiState.update {
            it.copy(
                financialLoad = if (markFinancial) DataLoad.FAILED else it.financialLoad,
                todayIncomeLoad = if (markFinancial) DataLoad.FAILED else it.todayIncomeLoad
            )
        }
        scheduleCloudRetry()
    }

    private fun observeCloudData(): Job = viewModelScope.launch {
        // كل قسم من أقسام S3 المعتمدة على السحابة يبدأ «قيد التحميل» (لا صفراً يُقرأ كقيمة حقيقية)
        subscriptionsSource.value = null
        invoicesSource.value = null
        paymentsSource.value = null
        subscriptionPaymentsSource.value = null
        _uiState.update {
            it.copy(subscriptionsLoad = DataLoad.LOADING, financialLoad = DataLoad.LOADING, todayIncomeLoad = DataLoad.LOADING)
        }
        observe(
            repository.getSubscriptionsRealtime(),
            tag = "subscriptions",
            onFailure = {
                markSyncChecked(); subscriptionsSource.value = null
                _uiState.update { it.copy(subscriptionsLoad = DataLoad.FAILED) }
                onCloudSourceFailed()
            }
        ) { list ->
            subscriptionsSource.value = list
            _uiState.update { it.copy(subscriptions = list, isInitialSyncChecked = true, subscriptionsLoad = DataLoad.READY, lastCloudUpdateMillis = System.currentTimeMillis()) }
        }
        observe(
            repository.getInvoicesRealtime(),
            tag = "invoices",
            onFailure = { invoicesSource.value = null; onCloudSourceFailed() }
        ) { list ->
            invoicesSource.value = list
            _uiState.update { it.copy(invoices = list, lastCloudUpdateMillis = System.currentTimeMillis()) }
        }
        observe(
            listAllPaymentsUseCase(),
            tag = "payments",
            onFailure = { paymentsSource.value = null; onCloudSourceFailed() }
        ) { list ->
            paymentsSource.value = list
            _uiState.update { it.copy(lastCloudUpdateMillis = System.currentTimeMillis()) }
        }
        observe(
            repository.getAllSubscriptionPayments(),
            tag = "subscriptionPayments",
            // فشل هذا المصدر لا يُسقط بقية اللوحة: يبقى محصَّل الاشتراكات «—» فقط
            onFailure = { subscriptionPaymentsSource.value = null }
        ) { list -> subscriptionPaymentsSource.value = list }
        observeDerivedFinancials()
        observe(repository.getOrders(), tag = "orders") { orders -> _uiState.update { it.copy(orders = orders) } }
        observe(repository.getOwnersSummary(), tag = "owners") { owners -> _uiState.update { it.copy(owners = owners.filter { o -> o.status != OwnerStatus.ARCHIVED }, allOwners = owners) } }
        observe(repository.getActiveSessions(), tag = "activeSessions") { s -> _uiState.update { it.copy(activeSessions = s) } }
    }

    /**
     * الملخص المالي ودخل اليوم من المصادر الثلاثة المشتركة. المؤقّت (60ث) يبدّل «اليوم» عند منتصف الليل دون انتظار دفعة جديدة.
     * أي مصدر فاشل/غير واصل (null) يُبقي الحالتين على LOADING/FAILED ولا يُنتج أصفاراً مضللة.
     */
    private fun CoroutineScope.observeDerivedFinancials() {
        val ticker = flow { while (true) { emit(Unit); kotlinx.coroutines.delay(60_000L) } }
        launch {
            val periodInputs = combine(subscriptionPaymentsSource, billingPeriodFlow) { sp, period -> sp to period }
            combine(subscriptionsSource, invoicesSource, paymentsSource, periodInputs, ticker) { s, i, p, sp, _ ->
                FinancialInputs(s, i, p, sp.first, sp.second)
            }
                .collect { inputs ->
                    val s = inputs.subscriptions
                    val i = inputs.invoices
                    val p = inputs.payments
                    val subPayments = inputs.subscriptionPayments
                    val period = inputs.period
                    if (s == null || i == null || p == null) return@collect
                    val todayDate = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
                    val today = todayDate.toString()
                    val periodRange = period.range(todayDate)
                    val periodSummary = BillingRules.periodSummary(i, p, subPayments, periodRange)
                    val dashboard = BillingRules.dashboard(i, p, s)
                    val income = BillingRules.incomeForLocalDay(p, today)
                    retryAttempt = 0
                    _uiState.update {
                        it.copy(
                            financialDashboard = dashboard, financialLoad = DataLoad.READY,
                            billingPeriod = period, billingPeriodRange = periodRange, periodSummary = periodSummary,
                            todayIncomeMinor = income, todayIncomeLoad = DataLoad.READY
                        )
                    }
                }
        }
    }

    /**
     * حالة الراوتر في S3: من الحالة المركزية لاتصال RouterOS مباشرة (RouterStatus) ولا شيء غيرها.
     * مستقلة تماماً عن تدفقات Firestore (الاشتراكات/الفواتير/المستخدمون): فشل أيٍّ منها لا يغيّر حالة الاتصال.
     * بلا جلسة مصادَق عليها لا تُعرض بيانات راوتر (لا تتسرب لمن يدخل بعد الخروج).
     */
    private fun observeRouterOverview() {
        combine(getSessionStateUseCase(), observeRouterStatusUseCase()) { session, status ->
            if (session.isAuthenticated) status.toOverview() else RouterOverview(link = RouterLinkState.NOT_CONFIGURED)
        }
            .distinctUntilChanged()
            .onEach { overview -> _uiState.update { it.copy(routerOverview = overview) } }
            .catch { e ->
                android.util.Log.w("MgtikViewModel", "router overview failed: ${e.message}")
                _uiState.update { it.copy(routerOverview = RouterOverview(link = RouterLinkState.DISCONNECTED)) }
            }
            .launchIn(viewModelScope)
    }

    /**
     * يجمع تدفقاً سحابياً بأمان: أي خطأ (صلاحيات/شبكة) لا يُسقط التطبيق ولا يعلّق السبلاش.
     * الخطأ الحقيقي يُسجَّل (لا يُبتلع) ويُبلَّغ عبر [onFailure] ليعرض القسم المتأثر «غير متاح» بدل قيمة افتراضية مضللة.
     */
    private fun <T> CoroutineScope.observe(
        flow: Flow<T>,
        tag: String,
        onFailure: () -> Unit = {},
        onValue: (T) -> Unit
    ) {
        launch {
            flow.catch { e ->
                android.util.Log.w("MgtikViewModel", "$tag flow failed: ${e.message}")
                onFailure()
            }.collect { onValue(it) }
        }
    }

    /**
     * خطوة «الراوتر» في السبلاش = حسم إعدادات الراوتر (يحدّد الوجهة: S2 أو الرئيسية). المصدر الحالة المركزية
     * (لا مراقبة Firestore ثانية). تتغير فقط عند اكتمال التحميل أو انتهاء مهلته، فلا يعلّق السبلاش.
     * تعذّر قراءة الإعداد من السحابة ليس «غير مُهيّأ»: نكمل بالقيم الافتراضية (الحوار المركزي يعرض الخيارات)
     * بدل تحويل المستخدم إلى S2 خطأً.
     */
    private fun observeRouterConfigState() {
        combine(getSessionStateUseCase(), observeRouterStatusUseCase(), startupUserMan) { session, status, um ->
            val stage = if (!session.isAuthenticated) StartupStage.READY else deriveStartupStage(
                configLoad = status.configLoad,
                host = status.config.host,
                state = status.state,
                errorCode = status.errorCode,
                attentionRequired = status.attentionRequired,
                loggedOut = status.loggedOut,
                offlineMode = status.offlineMode,
                userMan = um
            )
            val config = when {
                !session.isAuthenticated -> RouterConfig(host = "")
                status.configLoad == ConfigLoadState.UNAVAILABLE && status.config.host.isBlank() -> RouterConfig()
                else -> status.config
            }
            Triple(stage, config, status)
        }
            .onEach { (stage, config, status) ->
                _uiState.update {
                    it.copy(
                        routerConfig = config,
                        // خطوة «الراوتر» في السبلاش = اتصال حقيقي + فحص User Manager (لا قراءة إعدادات فقط)
                        isInitialRouterChecked = stage != StartupStage.CHECKING,
                        startupStage = stage,
                        startupErrorCode = status.errorCode,
                        continueOffline = status.offlineMode
                    )
                }
            }
            .catch { _uiState.update { it.copy(isInitialRouterChecked = true, startupStage = StartupStage.ROUTER_FAILED) } }
            .launchIn(viewModelScope)
    }

    /**
     * فحص User Manager بعد نجاح اتصال الراوتر (UserManSource.status على جلسة الاتصال الحالية دون مصادقة مستقلة).
     * يُعاد الفحص مع كل اتصال جديد، ويُصفَّر عند فقد الاتصال.
     */
    private fun observeStartupUserMan() {
        observeRouterStatusUseCase()
            .map { it.state == RouterConnectionState.CONNECTED && it.config.host.isNotBlank() }
            .distinctUntilChanged()
            .onEach { connected ->
                userManCheckJob?.cancel()
                startupUserMan.value = StartupUserMan.UNKNOWN
                if (connected) userManCheckJob = viewModelScope.launch { runStartupUserManCheck() }
            }
            .launchIn(viewModelScope)
    }

    private suspend fun runStartupUserManCheck() {
        val config = observeRouterStatusUseCase().value.config
        val res = try {
            userManSource.status(config)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Error(ps.mgtik.hotspot.core.result.ApiError.mapThrowable(e))
        }
        startupUserMan.value = when (res) {
            is AppResult.Success -> if (res.data.isReady) StartupUserMan.READY else StartupUserMan.NOT_READY
            is AppResult.Error -> StartupUserMan.NOT_READY
            AppResult.Loading -> StartupUserMan.UNKNOWN
        }
    }

    /** يُستدعى من S32 عند تأكيد جاهزية User Manager فتُحسم سلسلة الإقلاع دون إعادة فحص. */
    fun onUserManVerified() { startupUserMan.value = StartupUserMan.READY }

    private fun markSyncChecked() = _uiState.update { it.copy(isInitialSyncChecked = true) }
    private fun markInitialChecksDone() = _uiState.update {
        it.copy(isInitialAuthChecked = true, isInitialRouterChecked = true, isInitialSyncChecked = true)
    }

    private fun observeRouterEvents() {
        repository.getConnectionEvents()
            .onEach { event ->
                if (event is RouterConnectionEvent.AutoReconnected) {
                    _uiState.update { it.copy(messageResId = R.string.msg_router_reconnected) }
                    // عاد الراوتر: نفرّغ الطابور غير المتصل الآن (جدولة العامل الموجود فقط؛ المعالج نفسه لم يتغير)
                    offlineScheduler.scheduleSync()
                }
            }.launchIn(viewModelScope)
    }

    fun setDefaultTemplate(templateId: String?) {
        viewModelScope.launch { repository.setDefaultTemplate(templateId) }
    }

    private fun observeDefaultTemplate() {
        repository.getDefaultTemplateId()
            .onEach { id -> _uiState.update { it.copy(defaultTemplateId = id) } }
            .catch { }
            .launchIn(viewModelScope)
    }

    private fun observeTemplates() {
        repository.getLocalTemplates()
            .onEach { list -> _uiState.update { it.copy(templates = list) } }
            .launchIn(viewModelScope)
    }

    private fun observeOfflineCommands() {
        offlineCommandRepository.getPendingCommands()
            .catch { }
            .onEach { commands ->
                _uiState.update { it.copy(pendingCommandsCount = commands.size) }
            }.launchIn(viewModelScope)
    }

    // تسجيل الدخول والخروج حصراً في AuthViewModel (S1/الإعدادات): كانت هنا نسخة مكررة (signIn) بلا أي مستدعٍ
    // تخزّن الخطأ في حالة مختلفة؛ حذفها يمنع مسارين متعارضين للمصادقة.

    fun syncTemplates() {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            // النتيجة كانت تُهمل فيظهر «نجاح» حتى مع فشل التنزيل؛ الآن الفشل يصل للحالة بكوده كبقية العمليات
            when (val result = repository.syncFull()) {
                is AppResult.Success -> _uiState.update { it.copy(isLoading = false, successMessageResId = R.string.msg_templates_synced) }
                is AppResult.Error -> _uiState.update { it.copy(isLoading = false, errorMessage = result.error.code) }
                is AppResult.Loading -> _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /** إنهاء جلسة نشطة على الراوتر؛ النجاح لا يُعلَن إلا بعد تأكيد الطبقة المسؤولة. */
    fun disconnectSession(session: ActiveSession) {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val res = try {
                disconnectActiveUserUseCase(session.mikrotikId, session.user)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.Error(ps.mgtik.hotspot.core.result.ApiError.mapThrowable(e))
            }
            _uiState.update {
                when (res) {
                    is AppResult.Success -> it.copy(isLoading = false, successMessageResId = R.string.msg_session_disconnected)
                    is AppResult.Error -> it.copy(isLoading = false, errorMessage = res.error.code)
                    is AppResult.Loading -> it.copy(isLoading = false)
                }
            }
        }
    }

    fun dismissSnackbar() { _uiState.update { it.copy(messageResId = null, successMessageResId = null) } }
    fun clearSnackbar() { _uiState.update { it.copy(successMessageResId = null, errorMessage = null) } }
}

data class MgtikUiState(
    val session: UserSession = UserSession(user = null, isAuthenticated = false),
    val isInitialAuthChecked: Boolean = false,
    val isInitialRouterChecked: Boolean = false,
    val isInitialSyncChecked: Boolean = false,
    /** مرحلة سلسلة الإقلاع (اتصال الراوتر ثم User Manager)؛ مصدر قرار resolveStartRoute. */
    val startupStage: StartupStage = StartupStage.CHECKING,
    /** رمز آخر فشل اتصال (ApiError.code) لعرضه في S2؛ null إن لم يوجد. */
    val startupErrorCode: String? = null,
    /** اختار المستخدم صراحةً «متابعة دون اتصال». */
    val continueOffline: Boolean = false,
    val templates: List<RuntimeTemplate> = emptyList(),
    /** القالب الافتراضي للتصدير (null = المضمَّن). */
    val defaultTemplateId: String? = null,
    /** حالة الراوتر في S3 (مصدرها اتصال RouterOS المركزي فقط). */
    val routerOverview: RouterOverview = RouterOverview(),
    val subscriptions: List<Subscription> = emptyList(),
    val invoices: List<Invoice> = emptyList(),
    val orders: List<Order> = emptyList(),
    val owners: List<OwnerAccount> = emptyList(),
    /** كل الوكلاء بمن فيهم المؤرشفون: للأسماء فقط (المستخدمون المنسوبون لوكيل مؤرشف يبقون بأسمائه، لا بمعرّف خام). */
    val allOwners: List<OwnerAccount> = emptyList(),
    val activeSessions: List<ActiveSession> = emptyList(),
    val financialDashboard: FinancialDashboard = FinancialDashboard(),
    /** فترة لوحة Billing المختارة وحدودها المحسوبة (null = كل الفترات) وملخص حركتها؛ الملخص null حتى تصل البيانات. */
    val billingPeriod: BillingPeriod = BillingPeriod(),
    val billingPeriodRange: Pair<String, String>? = null,
    val periodSummary: BillingRules.PeriodSummary? = null,
    /** صافي محصَّل دفعات اليوم المحلي (للمراقبة العامة في S3). */
    val todayIncomeMinor: Long = 0L,
    /** حالة تحميل كل قسم سحابي في S3: لا يُعرض رقم إلا عند READY (الصفر الافتراضي ليس قيمة حقيقية). */
    val subscriptionsLoad: DataLoad = DataLoad.LOADING,
    val financialLoad: DataLoad = DataLoad.LOADING,
    val todayIncomeLoad: DataLoad = DataLoad.LOADING,
    /** آخر لحظة (epoch ms) وصلت فيها بيانات سحابية جديدة لـ Overview؛ null = لم تصل بعد. */
    val lastCloudUpdateMillis: Long? = null,
    val notifications: List<MgtikNotification> = emptyList(),
    val pendingCommandsCount: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    @StringRes val successMessageResId: Int? = null,
    @StringRes val messageResId: Int? = null,
    // فارغ حتى يصل الإعداد الفعلي من السحابة (host فارغ = غير مُهيّأ)
    val routerConfig: RouterConfig = RouterConfig(host = "")
)

/** مدخلات اشتقاق الأرقام المالية (مصدر null = لم يصل بعد أو فشل). */
private data class FinancialInputs(
    val subscriptions: List<Subscription>?,
    val invoices: List<Invoice>?,
    val payments: List<Payment>?,
    val subscriptionPayments: List<SubscriptionPayment>?,
    val period: BillingPeriod
)

/** حالة تحميل بيانات قسم سحابي في الواجهة. FAILED = فشل التدفق فعلاً (صلاحيات/شبكة) وليس «لا بيانات». */
enum class DataLoad { LOADING, READY, FAILED }

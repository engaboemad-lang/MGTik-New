package ps.mgtik.hotspot.data.router

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ps.mgtik.hotspot.core.network.RouterConnectionManager
import ps.mgtik.hotspot.core.network.sameEndpoint
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.IdempotencyKeyProvider
import android.os.SystemClock
import ps.mgtik.hotspot.data.router.lifecycle.ConnectionJournal
import ps.mgtik.hotspot.data.router.lifecycle.ConnectionTimeline
import ps.mgtik.hotspot.data.router.lifecycle.FailureWindow
import ps.mgtik.hotspot.data.router.lifecycle.FlapDetector
import ps.mgtik.hotspot.data.router.lifecycle.NetworkMonitor
import ps.mgtik.hotspot.data.router.lifecycle.NoopNetworkMonitor
import ps.mgtik.hotspot.data.router.lifecycle.OffRouterNetworkRule
import ps.mgtik.hotspot.data.router.lifecycle.QualityThresholds
import ps.mgtik.hotspot.data.router.lifecycle.QualityTracker
import ps.mgtik.hotspot.data.router.lifecycle.ReconnectBackoff
import ps.mgtik.hotspot.data.router.lifecycle.ReconnectPolicy
import ps.mgtik.hotspot.data.router.lifecycle.RouterStateInvariants
import ps.mgtik.hotspot.data.router.lifecycle.RouterStateTransitions
import ps.mgtik.hotspot.data.router.lifecycle.StatusFacts
import ps.mgtik.hotspot.data.router.lifecycle.UptimeParser
import ps.mgtik.hotspot.data.userman.UserManSource
import ps.mgtik.hotspot.domain.model.ConfigLoadState
import ps.mgtik.hotspot.domain.model.ConnectionLogEntry
import ps.mgtik.hotspot.domain.model.LinkQualityLevel
import ps.mgtik.hotspot.domain.model.RouterInterruption
import ps.mgtik.hotspot.domain.model.RouterConfig
import ps.mgtik.hotspot.domain.model.RouterConnectionState
import ps.mgtik.hotspot.domain.model.RouterHealth
import ps.mgtik.hotspot.domain.model.RouterInfo
import ps.mgtik.hotspot.domain.model.RouterSaveOutcome
import ps.mgtik.hotspot.domain.model.RouterStateEvent
import ps.mgtik.hotspot.domain.model.RouterStatus
import ps.mgtik.hotspot.domain.repository.IAuthRepository
import ps.mgtik.hotspot.domain.repository.IRouterStateManager
import ps.mgtik.hotspot.domain.repository.ISettingsRepository
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** أزمنة المنظومة (قابلة للتبديل في الاختبارات). */
internal data class RouterTiming(
    /** أقصى انتظار لوصول أول إعداد من السحابة قبل إعلانه «غير متاح» (لا مؤشر تحميل أبدي). */
    val configLoadTimeoutMs: Long = 8_000L,
    /** نبض المراقبة أثناء ظهور التطبيق (المقدمة). */
    val foregroundPollMs: Long = 10_000L,
    /** مهلة قصيرة قبل إعادة الفحص بعد فشل نبضة (تفادي إنذار كاذب من تعثر لحظي). */
    val confirmDelayMs: Long = 1_500L,
    /** عدد الفشلات المتتالية اللازمة ضمن [failureWindowMs] قبل تغيير حالة الواجهة (فشل نبضة واحدة لا يكفي). */
    val failuresToConfirm: Int = 2,
    val failureWindowMs: Long = 30_000L,
    /** فواصل إعادة الاتصال التلقائية بالثواني؛ آخر قيمة تثبت. */
    val backoffSeconds: List<Int> = ReconnectBackoff.DEFAULT_STEPS,
    /** التذبذب: [flapThreshold] انقطاعات مؤكدة خلال [flapWindowMs] ⇒ «الاتصال غير مستقر» وإبطاء المحاولات. */
    val flapThreshold: Int = 3,
    val flapWindowMs: Long = 120_000L,
    val flapFloorSeconds: Int = 30,
    val quality: QualityThresholds = QualityThresholds()
)

/**
 * RouterStateManager - المسؤول المركزي الوحيد عن دورة حياة اتصال الراوتر (مراجعة المنظومة الموحدة).
 *
 * يجمع تنظيمياً دون إنشاء طبقات موازية:
 *  - الإعدادات: يقرأ ISettingsRepository (Firestore) ويحفظ عبره مع التحقق من الاسترجاع.
 *  - الاتصال: يستعمل RouterConnectionManager الموجود (REST/API) ولا يفتح اتصالاً موازياً.
 *  - المراقبة: حلقة نبض واحدة لكل التطبيق (10ث في المقدمة، متوقفة في الخلفية)، وNetworkCallback لكشف فقد شبكة الهاتف.
 *  - إعادة الاتصال: تلقائية بفواصل 2→5→10→30→60ث، وتتوقف عند فشل المصادقة/تسجيل الخروج/وضع غير متصل/فحص إعدادات جديدة.
 *  - الحالة: [status] مصدر وحيد تقرأه كل الشاشات؛ كل تعديل عليها يمرّ عبر [transition] الذي يرفض الانتقالات غير المعرّفة
 *    والتركيبات المتناقضة ويسجّلها في سجل الاتصال (لا تغيير صامت).
 *  - تنسيق العمليات: يغلق بوابة RouterConnectionManager أثناء الانقطاع فتُرفض العمليات فوراً (ERR_ROUTER_OFFLINE)
 *    ولا تُرسل جلسة منتهية؛ العمليات المعلّقة (طابور الأوامر/ساغا التوليد) تعيد المحاولة بعد عودة الاتصال بسياستها.
 *
 * التزامن: كل تعديل لحقول المدير يجري على مرسل متسلسل واحد (parallelism = 1)، فلا سباقات بين الاتصال والمراقبة
 * والحفظ. نتيجة محاولة اتصال قديمة (بعد تغيّر الإعداد أو بدء محاولة أحدث) تُتجاهل عبر رقم الجيل [generation].
 */
@Singleton
class RouterStateManager internal constructor(
    private val settings: ISettingsRepository,
    private val auth: IAuthRepository,
    private val connectionManager: RouterConnectionManager,
    dispatcher: CoroutineDispatcher,
    private val timing: RouterTiming,
    private val networkMonitor: NetworkMonitor,
    /** يُستدعى عند اكتشاف إعادة تشغيل الراوتر (يُسقط ذاكرة User Manager لأن الإصدار قد يتغير). */
    private val onRouterRebooted: () -> Unit,
    private val elapsedClock: () -> Long,
    private val wallClock: () -> Long
) : IRouterStateManager {

    @Inject
    constructor(
        settings: ISettingsRepository,
        auth: IAuthRepository,
        connectionManager: RouterConnectionManager,
        networkMonitor: ps.mgtik.hotspot.data.router.lifecycle.AndroidNetworkMonitor,
        userMan: Provider<UserManSource>
    ) : this(
        settings, auth, connectionManager, serialDispatcher(), RouterTiming(), networkMonitor,
        { userMan.get().invalidate() },
        { SystemClock.elapsedRealtime() },
        { System.currentTimeMillis() }
    )

    private val dispatcher: CoroutineDispatcher = dispatcher
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _status = MutableStateFlow(RouterStatus())
    override val status: StateFlow<RouterStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<RouterStateEvent>(
        extraBufferCapacity = 4,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    override val events: Flow<RouterStateEvent> = _events.asSharedFlow()

    private class Inflight(val config: RouterConfig, val deferred: Deferred<AppResult<RouterInfo>>)

    // ── حالة داخلية: تُلمس فقط على المرسل المتسلسل ─────────────────────────────
    private val started = AtomicBoolean(false)
    @Volatile private var sessionJob: Job? = null
    @Volatile private var eventsJob: Job? = null
    /** تسجيل خروج صريح من الراوتر: يمنع الاتصال التلقائي (مراقبة الإعدادات) حتى طلب المستخدم. */
    private var loggedOut = false
    private var configJob: Job? = null
    private var monitorJob: Job? = null
    private var reconnectJob: Job? = null
    private var inflight: Inflight? = null
    private var generation = 0
    private var lastAttempt: RouterConfig? = null
    /** كان الاتصال سليماً ثم انقطع (لتمييز «إعادة اتصال» عن أول اتصال). */
    private var lostConnection = false
    private var configObserverDead = false
    private var configuring = 0
    private var stateBeforeConfiguring = RouterConnectionState.DISCONNECTED
    private var pendingConfig: RouterConfig? = null
    /** فشل اتصال في عملية تشغيلية / عودة الشبكة / العودة للمقدمة توقظ المراقب فوراً بدل انتظار دورته. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // دورة حياة الاتصال (نواة نقية قابلة للاختبار)
    private val timeline = ConnectionTimeline()
    private val failureWindow = FailureWindow(timing.failuresToConfirm, timing.failureWindowMs)
    private val flap = FlapDetector(timing.flapThreshold, timing.flapWindowMs)
    private val qualityTracker = QualityTracker(timing.quality)
    private var backoff = ReconnectBackoff(timing.backoffSeconds)
    private var lastUptimeSeconds: Long? = null
    private val journal = ConnectionJournal(50)
    private val _log = MutableStateFlow<List<ConnectionLogEntry>>(emptyList())
    override val connectionLog: StateFlow<List<ConnectionLogEntry>> = _log.asStateFlow()
    private val foreground = MutableStateFlow(true)

    private val networkListener = object : NetworkMonitor.Listener {
        override fun onAvailable() { wake.trySend(Unit) } // عادت الشبكة: نبضة/محاولة فورية
        override fun onLost() { scope.launch { onNetworkLost() } }
    }

    // ── العقد العام ───────────────────────────────────────────────────────────

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        sessionJob = scope.launch {
            try {
                auth.sessionState.map { it.isAuthenticated }.distinctUntilChanged().collect { onAuth(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // فشل مراقبة الجلسة لا يُسقط التطبيق؛ الحالة تبقى كما هي
            }
        }
        eventsJob = scope.launch {
            connectionManager.connectionEvents
                .filterIsInstance<RouterConnectionManager.ConnectionEvent.OperationFailed>()
                .collect { wake.trySend(Unit) }
        }
    }

    override fun setAppForeground(foreground: Boolean) {
        this.foreground.value = foreground
        if (foreground) wake.trySend(Unit) // العودة للمقدمة: نبضة فورية قبل تفاعل المستخدم
    }

    override fun exportConnectionLog(): String = journal.export { formatTime(it) }

    override fun retry() {
        scope.launch {
            val st = _status.value
            if (configObserverDead || st.configLoad != ConfigLoadState.LOADED) {
                startConfigObserver()
                return@launch
            }
            if (!st.isConfigured || st.state == RouterConnectionState.CONFIGURING) return@launch
            // إعادة المحاولة اليدوية تصفّر فاصل الانتظار وتبدأ من جديد
            reconnectJob?.cancel(); reconnectJob = null
            backoff.reset()
            connectAsync(st.config)
        }
    }

    override fun skipConnection() {
        scope.launch {
            val st = _status.value
            if (st.state == RouterConnectionState.DISCONNECTED ||
                st.state == RouterConnectionState.CONNECTION_FAILED ||
                st.state == RouterConnectionState.RECONNECTING
            ) {
                // وضع عدم الاتصال يوقف المحاولات التلقائية
                reconnectJob?.cancel(); reconnectJob = null
                connectionManager.setOperationGate(ApiError.routerOffline())
                transition("skip") {
                    it.copy(
                        state = if (it.state == RouterConnectionState.CONNECTION_FAILED) it.state else RouterConnectionState.DISCONNECTED,
                        offlineMode = true,
                        attentionRequired = false,
                        reconnectAttempt = 0,
                        nextRetryAt = null
                    )
                }
            }
        }
    }

    override suspend fun logout() {
        withContext(dispatcher) {
            loggedOut = true
            stopMonitor()
            reconnectJob?.cancel(); reconnectJob = null
            inflight?.deferred?.cancel(); inflight = null
            generation++ // نتيجة أي محاولة جارية قديمة لا تُعتمد
            lastAttempt = null
            lostConnection = false
            pendingConfig = null
            resetLifecycleCore()
            // البوابة أولاً: أي عملية جديدة تُرفض (راوتر غير متصل) بدل أن تفتح اتصالاً جديداً أثناء الإغلاق
            connectionManager.setOperationGate(ApiError.routerOffline())
            connectionManager.reset()
            transition("logout") {
                it.copy(
                    state = RouterConnectionState.DISCONNECTED,
                    info = null,
                    health = RouterHealth(),
                    errorCode = null,
                    attentionRequired = false,
                    offlineMode = false,
                    loggedOut = true,
                    interruption = null,
                    reconnectAttempt = 0,
                    nextRetryAt = null,
                    rttMs = null,
                    quality = null,
                    lastCheckedAtMillis = wallClock()
                )
            }
            log(RouterConnectionState.DISCONNECTED, "ROUTER_LOGOUT", null, null)
        }
    }

    override suspend fun shutdown() {
        withContext(dispatcher) {
            sessionJob?.cancel(); sessionJob = null
            eventsJob?.cancel(); eventsJob = null
            teardown() // يُلغي أيضاً تسجيل NetworkCallback
            connectionManager.setOperationGate(ApiError.routerOffline())
            connectionManager.reset()
            forceStatus(RouterStatus())
            started.set(false) // start() التالي يعيد بناء المراقبة والاتصال
        }
    }

    override suspend fun testConfig(config: RouterConfig): AppResult<RouterInfo> = withContext(dispatcher) {
        beginConfiguring()
        try {
            RouterProbe.info(connectionManager, config)
        } finally {
            endConfiguring()
        }
    }

    override suspend fun saveAndConnect(config: RouterConfig): RouterSaveOutcome = withContext(dispatcher) {
        beginConfiguring()
        try {
            when (val saved = settings.updateRouterConfig(config, IdempotencyKeyProvider.generateKey())) {
                is AppResult.Error -> return@withContext RouterSaveOutcome.SaveFailed(saved.error)
                else -> Unit
            }
            // إعدادات جديدة = محاولة جديدة؛ ليست «إعادة اتصال تلقائية» بعد انقطاع
            lostConnection = false
            configObserverDead = false
            transition("save-config") { it.copy(config = config, configLoad = ConfigLoadState.LOADED) }
            when (val res = awaitConnect(connectAsync(config))) {
                is AppResult.Success -> RouterSaveOutcome.SavedConnected(res.data)
                is AppResult.Error -> RouterSaveOutcome.SavedNotConnected(res.error)
                AppResult.Loading -> RouterSaveOutcome.SavedNotConnected(ApiError.unknownError("Connection attempt did not complete"))
            }
        } finally {
            endConfiguring()
        }
    }

    // ── الحالة: نقطة التعديل الوحيدة ──────────────────────────────────────────

    private fun factsOf(s: RouterStatus) = StatusFacts(
        state = s.state, hasInfo = s.info != null, errorCode = s.errorCode, attentionRequired = s.attentionRequired,
        offlineMode = s.offlineMode, loggedOut = s.loggedOut, connectedSince = s.connectedSince != null
    )

    /** يضيف حقول المدة/التذبذب من النواة إلى أي لقطة جديدة فلا تتناقض معها. */
    private fun withLifecycle(s: RouterStatus) = s.copy(
        connectedSince = timeline.connectedSince,
        lastDisconnectAt = timeline.lastDisconnectAt,
        reconnectCount = timeline.reconnectCount,
        unstable = flap.unstable
    )

    /**
     * كل تعديل للحالة يمرّ من هنا: انتقال غير معرّف في [RouterStateTransitions] أو تركيبة متناقضة
     * ([RouterStateInvariants]) تُرفض وتُسجَّل في سجل الاتصال، والحالة تبقى كما كانت (لا تغيير صامت).
     */
    private fun transition(tag: String, change: (RouterStatus) -> RouterStatus): Boolean {
        val cur = _status.value
        val next = withLifecycle(change(cur))
        if (!RouterStateTransitions.isAllowed(cur.state, next.state)) {
            log(cur.state, "ILLEGAL_TRANSITION:$tag:${cur.state}->${next.state}", null, null)
            return false
        }
        RouterStateInvariants.violation(factsOf(next))?.let { v ->
            log(cur.state, "INCONSISTENT_STATE:$tag:$v", null, null)
            return false
        }
        _status.value = next
        return true
    }

    /** إعادة ضبط كاملة (جلسة جديدة/إغلاق/غير مُهيّأ): خارج جدول الانتقالات عمداً (لقطات ابتدائية متسقة بطبيعتها). */
    private fun forceStatus(next: RouterStatus) {
        _status.value = next
    }

    private fun log(state: RouterConnectionState, reason: String?, durationMs: Long?, result: String?) {
        journal.add(ConnectionLogEntry(wallClock(), state, reason, durationMs, result))
        _log.value = journal.snapshot()
    }

    private fun resetLifecycleCore() {
        timeline.reset(); flap.reset(); failureWindow.onSuccess(); qualityTracker.reset()
        backoff = ReconnectBackoff(timing.backoffSeconds)
        lastUptimeSeconds = null
    }

    private fun formatTime(millis: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ENGLISH).format(java.util.Date(millis))

    private suspend fun awaitForeground() {
        if (!foreground.value) foreground.first { it }
    }

    // ── الجلسة والإعدادات ─────────────────────────────────────────────────────

    private fun onAuth(authenticated: Boolean) {
        teardown()
        forceStatus(RouterStatus())
        if (authenticated) {
            startConfigObserver()
            networkMonitor.start(networkListener)
        }
    }

    private fun teardown() {
        networkMonitor.stop() // إلغاء تسجيل NetworkCallback
        configJob?.cancel(); configJob = null
        monitorJob?.cancel(); monitorJob = null
        reconnectJob?.cancel(); reconnectJob = null
        inflight?.deferred?.cancel(); inflight = null
        generation++
        lastAttempt = null
        lostConnection = false
        configObserverDead = false
        configuring = 0
        pendingConfig = null
        loggedOut = false // جلسة جديدة/منتهية: الحالة كلها تبدأ من الصفر
        resetLifecycleCore()
        connectionManager.setOperationGate(null)
        scope.launch { connectionManager.reset() }
    }

    private fun startConfigObserver() {
        configJob?.cancel()
        transition("config-loading") { it.copy(configLoad = ConfigLoadState.LOADING, attentionRequired = false, errorCode = null) }
        configJob = scope.launch {
            val timeout = launch {
                delay(timing.configLoadTimeoutMs)
                if (_status.value.configLoad == ConfigLoadState.LOADING) onConfigUnavailable(null)
            }
            // مراقبة الإعدادات (سحابة) تُعاد تلقائياً بمهلة متزايدة بعد أي خطأ: ليست محاولات اتصال بالراوتر،
            // ولا يجوز أن تموت نهائياً فتبقى الحالة «غير متاح» حتى يتدخل المستخدم
            var attempt = 0
            while (isActive) {
                try {
                    settings.getRouterConfig().collect { cfg ->
                        attempt = 0
                        timeout.cancel()
                        onConfig(cfg)
                    }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    timeout.cancel()
                    onConfigUnavailable(e)
                    delay(minOf(3_000L * (++attempt), 30_000L))
                }
            }
        }
    }

    /** تعذّرت قراءة الإعدادات (سحابة/شبكة/صلاحيات): ليست «غير مُهيّأ» ولا عطلاً في الراوتر. */
    private fun onConfigUnavailable(cause: Throwable?) {
        configObserverDead = cause != null
        val st = _status.value
        if (st.configLoad == ConfigLoadState.LOADED) return // لدينا إعداد صالح؛ نكمل به
        val code = cause?.let { ApiError.mapThrowable(it).code } ?: ApiError.CODE_TIMEOUT
        connectionManager.setOperationGate(ApiError.routerOffline())
        transition("config-unavailable") {
            it.copy(
                configLoad = ConfigLoadState.UNAVAILABLE,
                state = RouterConnectionState.DISCONNECTED,
                errorCode = code,
                attentionRequired = !it.offlineMode
            )
        }
    }

    private fun onConfig(cfg: RouterConfig) {
        configObserverDead = false
        if (cfg.host.isBlank()) {
            stopMonitor()
            reconnectJob?.cancel(); reconnectJob = null
            inflight?.deferred?.cancel(); inflight = null
            generation++
            lastAttempt = null
            lostConnection = false
            resetLifecycleCore()
            connectionManager.setOperationGate(ApiError.routerNotConfigured())
            scope.launch { connectionManager.reset() }
            forceStatus(
                RouterStatus(
                    state = RouterConnectionState.NOT_CONFIGURED,
                    config = cfg,
                    configLoad = ConfigLoadState.LOADED
                )
            )
            return
        }
        val before = _status.value
        transition("config") { it.copy(config = cfg, configLoad = ConfigLoadState.LOADED) }
        if (loggedOut) return // خرج المستخدم من الراوتر: الإعدادات تُحدَّث فقط، ولا اتصال تلقائي
        if (configuring > 0) {
            pendingConfig = cfg
            return
        }
        if (before.state == RouterConnectionState.NOT_CONFIGURED ||
            before.configLoad != ConfigLoadState.LOADED ||
            !cfg.sameEndpoint(lastAttempt)
        ) {
            connectAsync(cfg)
        }
    }

    // ── الاتصال ───────────────────────────────────────────────────────────────

    /**
     * محاولة اتصال واحدة في كل وقت: طلب مماثل جارٍ لنفس النقطة يُدمج معه (ضغط متكرر ≠ محاولات متوازية)،
     * وطلب لنقطة مختلفة يُلغي الجاري ونتيجته القديمة لا تُعتمد.
     */
    private fun connectAsync(cfg: RouterConfig): Deferred<AppResult<RouterInfo>> {
        inflight?.let { if (it.deferred.isActive && cfg.sameEndpoint(it.config)) return it.deferred }
        stopMonitor()
        reconnectJob?.cancel(); reconnectJob = null // المحاولة الصريحة تحلّ محل الحلقة التلقائية
        inflight?.deferred?.cancel()
        val gen = ++generation
        lastAttempt = cfg
        loggedOut = false // طلب اتصال صريح (إعادة محاولة/حفظ) ينهي حالة تسجيل الخروج
        // اتصال جديد بنقطة جديدة (ليس إعادة اتصال بعد انقطاع): مدة جديدة بلا تاريخ الجلسة السابقة
        if (!lostConnection) { timeline.reset(); flap.reset(); lastUptimeSeconds = null }
        transition("connect") {
            it.copy(
                loggedOut = false,
                state = if (lostConnection) RouterConnectionState.RECONNECTING else RouterConnectionState.CONNECTING,
                errorCode = null,
                attentionRequired = false,
                offlineMode = false,
                nextRetryAt = null
            )
        }
        val deferred = scope.async {
            val result = RouterProbe.info(connectionManager, cfg)
            if (gen == generation) applyConnectResult(result, cfg)
            result
        }
        inflight = Inflight(cfg, deferred)
        return deferred
    }

    private suspend fun awaitConnect(d: Deferred<AppResult<RouterInfo>>): AppResult<RouterInfo> = try {
        d.await()
    } catch (e: CancellationException) {
        // أُلغيت المحاولة لأن أحدث منها بدأت: ليست إلغاءً للمستدعي نفسه
        if (currentCoroutineContext().isActive) AppResult.Error(ApiError.unknownError("Connection attempt superseded"))
        else throw e
    }

    private fun applyConnectResult(result: AppResult<RouterInfo>, cfg: RouterConfig) {
        inflight = null
        val now = wallClock()
        when (result) {
            is AppResult.Success -> onConnectedWith(result.data, reconnectedAfterOutage = lostConnection)
            is AppResult.Error -> {
                connectionManager.setOperationGate(ApiError.routerOffline())
                val retryable = lostConnection && ReconnectPolicy.isRetryable(result.error.code, result.error.retryable)
                transition("connect-failed") {
                    it.copy(
                        state = when {
                            retryable -> RouterConnectionState.RECONNECTING
                            lostConnection -> RouterConnectionState.DISCONNECTED
                            else -> RouterConnectionState.CONNECTION_FAILED
                        },
                        info = null,
                        health = RouterHealth(),
                        errorCode = result.error.code,
                        // نتيجة طلب صريح من المستخدم: القرار (إعادة/ضبط/تخطي) له
                        attentionRequired = true,
                        rttMs = null,
                        quality = null,
                        lastCheckedAtMillis = now
                    )
                }
                log(_status.value.state, result.error.code, null, if (lostConnection) "fail" else null)
                // بعد انقطاع حقيقي: تستمر إعادة الاتصال التلقائية بفواصل جديدة (الإعادة اليدوية صفّرتها)
                if (retryable) startReconnect(cfg, resetBackoff = true)
            }
            AppResult.Loading -> Unit
        }
    }

    /** اتصال ناجح (أول مرة أو إعادة اتصال بعد انقطاع مؤكَّد). */
    private fun onConnectedWith(info: RouterInfo, reconnectedAfterOutage: Boolean) {
        val nowEl = elapsedClock()
        val outageMs = timeline.lastDisconnectAt?.let { nowEl - it }
        failureWindow.onSuccess()
        qualityTracker.reset()
        timeline.onConnected(nowEl)
        lostConnection = false
        lastUptimeSeconds = UptimeParser.seconds(info.uptimeFormatted)
        connectionManager.setOperationGate(null)
        transition("connected") {
            it.copy(
                state = RouterConnectionState.CONNECTED,
                info = info,
                health = RouterProbe.healthFrom(info),
                errorCode = null,
                attentionRequired = false,
                offlineMode = false,
                loggedOut = false,
                interruption = null,
                reconnectAttempt = 0,
                nextRetryAt = null,
                lastCheckedAtMillis = wallClock()
            )
        }
        log(RouterConnectionState.CONNECTED, null, if (reconnectedAfterOutage) outageMs else null, if (reconnectedAfterOutage) "ok" else null)
        if (reconnectedAfterOutage) _events.tryEmit(RouterStateEvent.Reconnected)
        startMonitor()
    }

    // ── المراقبة (نبض) ────────────────────────────────────────────────────────

    private fun stopMonitor() {
        monitorJob?.cancel()
        monitorJob = null
    }

    /**
     * حلقة نبض وحيدة: تعمل فقط والحالة Connected. 10ث في المقدمة؛ في الخلفية تتوقف (لا عمل غير ضروري/Doze)
     * وتُستأنف بنبضة فورية عند العودة. فشل نبضة واحدة لا يغيّر الحالة المعروضة.
     */
    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                awaitForeground()
                withTimeoutOrNull(timing.foregroundPollMs) { wake.receive() }
                awaitForeground()
                if (!isActive) break
                val st = _status.value
                when (st.state) {
                    RouterConnectionState.CONFIGURING -> continue // الفحص/الحفظ يستعمل الاتصال الآن
                    RouterConnectionState.CONNECTED -> Unit
                    else -> break // صاحب القرار الآن حلقة إعادة الاتصال أو محاولة أخرى أو المستخدم
                }
                if (configuring > 0) continue
                val t0 = elapsedClock()
                val probe = RouterProbe.health(connectionManager, st.config)
                val rtt = elapsedClock() - t0
                when (probe) {
                    is AppResult.Success -> if (!onPulseSuccess(st.config, probe.data, rtt)) break
                    is AppResult.Error -> if (!onPulseFailure(st.config, probe.error)) break
                    AppResult.Loading -> Unit
                }
            }
        }
    }

    /** يعيد false إن بدأ انقطاع (إعادة تشغيل الراوتر) فتتوقف حلقة النبض. */
    private fun onPulseSuccess(cfg: RouterConfig, health: RouterHealth, rttMs: Long): Boolean {
        failureWindow.onSuccess()
        flap.onStable(elapsedClock())
        qualityTracker.record(rttMs)
        val up = UptimeParser.seconds(health.uptimeString)
        val previous = lastUptimeSeconds
        lastUptimeSeconds = up
        if (UptimeParser.rebooted(previous, up)) {
            onRouterRebootedDetected(cfg)
            return false
        }
        transition("pulse") {
            if (it.state == RouterConnectionState.CONNECTED) {
                // لا نكتب فوق حالة تغيّرت أثناء النبضة (إعدادات جديدة/محاولة أحدث)
                connectionManager.setOperationGate(null) // نبضة ناجحة: العمليات مسموحة (قد تكون أُغلقت بفشل سابق عابر)
                it.copy(
                    health = health,
                    errorCode = null,
                    rttMs = rttMs,
                    quality = qualityTracker.quality()?.let { q -> LinkQualityLevel.valueOf(q.name) },
                    lastCheckedAtMillis = wallClock()
                )
            } else it
        }
        return true
    }

    /**
     * فشل نبضة: نغلق بوابة العمليات فوراً (حماية العمليات) لكن لا نغيّر حالة الواجهة. نعيد الفحص بعد مهلة قصيرة؛
     * نجاحه تعافٍ صامت (لا يصفّر مدة الاتصال)، وتحقق [RouterTiming.failuresToConfirm] فشلات متتالية ضمن النافذة
     * انقطاع مؤكَّد. يعيد true عند التعافي.
     */
    private suspend fun onPulseFailure(cfg: RouterConfig, error: ApiError): Boolean {
        connectionManager.setOperationGate(ApiError.routerOffline())
        qualityTracker.record(null)
        var lastError = error
        var confirmed = failureWindow.onFailure(elapsedClock())
        var rechecks = 0
        while (!confirmed && rechecks < timing.failuresToConfirm) {
            rechecks++
            delay(timing.confirmDelayMs)
            val current = _status.value.state
            // غيّر أحدٌ الحالة أثناء الانتظار (فقد شبكة/ضبط/إعدادات جديدة): هو صاحب القرار الآن
            if (current != RouterConnectionState.CONNECTED) return current == RouterConnectionState.CONFIGURING
            if (configuring > 0) return true
            connectionManager.reset()
            when (val r = RouterProbe.info(connectionManager, cfg)) {
                is AppResult.Success -> { onSilentRecovery(r.data); return true }
                is AppResult.Error -> { lastError = r.error; confirmed = failureWindow.onFailure(elapsedClock()) }
                AppResult.Loading -> return true
            }
        }
        beginOutage(cfg, lastError, reason = null)
        return false
    }

    /** تعافٍ صامت: لا يصفّر المدة، ويزيد عدّاد إعادة الاتصال. */
    private fun onSilentRecovery(info: RouterInfo) {
        failureWindow.onSuccess()
        timeline.onSilentRecovery()
        lastUptimeSeconds = UptimeParser.seconds(info.uptimeFormatted)
        connectionManager.setOperationGate(null)
        transition("silent-recovery") {
            it.copy(
                state = RouterConnectionState.CONNECTED,
                info = info,
                health = RouterProbe.healthFrom(info),
                errorCode = null,
                lastCheckedAtMillis = wallClock()
            )
        }
        log(RouterConnectionState.CONNECTED, null, null, "ok")
    }

    // ── الانقطاع وإعادة الاتصال التلقائية ─────────────────────────────────────

    /** فقد شبكة الهاتف: RECONNECTING فوراً وبوابة العمليات مغلقة (لا ينتظر النبضة). */
    private fun onNetworkLost() {
        val st = _status.value
        if (st.state != RouterConnectionState.CONNECTED || loggedOut || configuring > 0 || st.offlineMode) return
        stopMonitor()
        beginOutage(
            st.config,
            ApiError(ApiError.CODE_NETWORK_UNAVAILABLE, "Phone network lost", retryable = true),
            reason = RouterInterruption.NETWORK_LOST
        )
    }

    /** إعادة تشغيل الراوتر: جلسة API القديمة وذاكرة User Manager لم تعودا صالحتين. */
    private fun onRouterRebootedDetected(cfg: RouterConfig) {
        // الجلسة القديمة تُسقط في حلقة إعادة الاتصال قبل كل محاولة (reset)، والبوابة تُغلق في beginOutage
        try { onRouterRebooted() } catch (_: Exception) { /* لا يُسقط المراقبة */ }
        _events.tryEmit(RouterStateEvent.RouterRebooted)
        beginOutage(
            cfg,
            ApiError(ApiError.CODE_ROUTER_OFFLINE, "Router restarted", retryable = true),
            reason = RouterInterruption.ROUTER_REBOOTED,
            countAsFlap = false
        )
    }

    /**
     * بداية انقطاع مؤكَّد: ينتهي مدة الاتصال، ويُسجَّل الوقت، ثم تبدأ إعادة الاتصال التلقائية إن كان الخطأ قابلاً لها.
     * التنبيه مرة واحدة لكل انقطاع (وأثناء التذبذب لأول مرة في الحلقة فقط).
     */
    private fun beginOutage(cfg: RouterConfig, error: ApiError, reason: RouterInterruption?, countAsFlap: Boolean = true) {
        val nowEl = elapsedClock()
        val connectedFor = timeline.connectedForMs(nowEl)
        timeline.onConfirmedDisconnect(nowEl)
        lostConnection = true
        val retryable = ReconnectPolicy.isRetryable(error.code, error.retryable)
        val outage = if (countAsFlap) flap.onConfirmedOutage(nowEl) else null
        val interruption = reason ?: classifyInterruption(cfg)
        connectionManager.setOperationGate(ApiError.routerOffline())
        qualityTracker.reset()
        backoff = if (outage?.flapping == true) {
            ReconnectBackoff(timing.backoffSeconds, timing.flapFloorSeconds)
        } else ReconnectBackoff(timing.backoffSeconds)
        // إعادة تشغيل الراوتر ليست خطأً يتطلب قراراً: لا حوار، فقط سبب مختلف في الحالة
        val alert = (!retryable || outage == null || outage.alertAllowed) && reason != RouterInterruption.ROUTER_REBOOTED
        transition("outage") {
            it.copy(
                state = if (retryable) RouterConnectionState.RECONNECTING else RouterConnectionState.DISCONNECTED,
                info = null,
                health = RouterHealth(),
                errorCode = error.code,
                attentionRequired = alert && !it.offlineMode,
                interruption = interruption,
                reconnectAttempt = 0,
                nextRetryAt = null,
                rttMs = null,
                quality = null,
                lastCheckedAtMillis = wallClock()
            )
        }
        log(_status.value.state, error.code, connectedFor, null)
        if (retryable) startReconnect(cfg, resetBackoff = false)
    }

    private fun classifyInterruption(cfg: RouterConfig): RouterInterruption =
        if (OffRouterNetworkRule.isOffRouterNetwork(networkMonitor.transports(), cfg.host, routerReachable = false)) {
            RouterInterruption.OFF_ROUTER_NETWORK
        } else RouterInterruption.ROUTER_UNREACHABLE

    /**
     * إعادة الاتصال التلقائية: فاصل 2→5→10→30→60ث ثم يثبت، وتتوقف عند فشل المصادقة (خطأ غير قابل للإعادة)
     * أو تسجيل الخروج أو وضع عدم الاتصال أو غياب الإعداد، وتُعلَّق أثناء فحص/حفظ إعدادات جديدة وأثناء الخلفية.
     * عودة الشبكة أو الصورة للمقدمة توقظها فوراً.
     */
    private fun startReconnect(cfg: RouterConfig, resetBackoff: Boolean) {
        reconnectJob?.cancel()
        if (resetBackoff) backoff.reset()
        val gen = generation
        reconnectJob = scope.launch {
            while (isActive && gen == generation) {
                if (shouldStopReconnect()) return@launch
                while (configuring > 0) delay(300L)
                awaitForeground()
                val delayMs = backoff.nextDelayMs()
                val attempt = backoff.attemptsMade
                transition("retry-scheduled") { it.copy(reconnectAttempt = attempt, nextRetryAt = elapsedClock() + delayMs) }
                withTimeoutOrNull(delayMs) { wake.receive() }
                while (configuring > 0) delay(300L)
                awaitForeground()
                if (gen != generation || shouldStopReconnect()) return@launch
                connectionManager.reset()
                val result = RouterProbe.info(connectionManager, cfg)
                if (gen != generation) return@launch
                when (result) {
                    is AppResult.Success -> { onConnectedWith(result.data, reconnectedAfterOutage = true); return@launch }
                    is AppResult.Error -> {
                        log(_status.value.state, result.error.code, null, "fail")
                        if (!ReconnectPolicy.isRetryable(result.error.code, result.error.retryable)) {
                            // مصادقة/إعداد: لا إعادة تلقائية، القرار للمستخدم
                            transition("reconnect-stopped") {
                                it.copy(
                                    state = RouterConnectionState.DISCONNECTED,
                                    errorCode = result.error.code,
                                    attentionRequired = !it.offlineMode,
                                    reconnectAttempt = 0,
                                    nextRetryAt = null
                                )
                            }
                            return@launch
                        }
                        transition("reconnect-failed") {
                            it.copy(errorCode = result.error.code, interruption = classifyInterruption(cfg))
                        }
                    }
                    AppResult.Loading -> Unit
                }
            }
        }
    }

    private fun shouldStopReconnect(): Boolean {
        val st = _status.value
        return ReconnectPolicy.shouldStop(
            loggedOut = loggedOut,
            offlineMode = st.offlineMode,
            configuring = false, // أثناء الفحص نُعلّق ولا نُنهي (تُستأنف بعده)
            notConfigured = st.state == RouterConnectionState.NOT_CONFIGURED
        ) || st.state == RouterConnectionState.CONNECTED
    }

    // ── وضع الضبط (فحص/حفظ) ──────────────────────────────────────────────────

    private fun beginConfiguring() {
        if (configuring++ == 0) {
            stateBeforeConfiguring = _status.value.state
            transition("configuring-begin") { it.copy(state = RouterConnectionState.CONFIGURING) }
        }
    }

    private fun endConfiguring() {
        if (--configuring > 0) return
        configuring = 0
        transition("configuring-end") {
            if (it.state == RouterConnectionState.CONFIGURING) it.copy(state = stateBeforeConfiguring) else it
        }
        val pending = pendingConfig
        pendingConfig = null
        if (pending != null) {
            if (pending.host.isBlank() || !pending.sameEndpoint(lastAttempt)) onConfig(pending)
        }
    }

    private companion object {
        @OptIn(ExperimentalCoroutinesApi::class)
        fun serialDispatcher(): CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    }
}

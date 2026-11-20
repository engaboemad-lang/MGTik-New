package ps.mgtik.hotspot.ui.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.DisconnectOutcome
import ps.mgtik.hotspot.domain.model.LiveSession
import ps.mgtik.hotspot.domain.usecase.DisconnectSessionUseCase
import ps.mgtik.hotspot.domain.usecase.GetLiveSessionsUseCase
import ps.mgtik.hotspot.domain.usecase.GetRouterConfigUseCase
import ps.mgtik.hotspot.domain.usecase.GetSessionStateUseCase
import ps.mgtik.hotspot.domain.usecase.SessionTracker
import ps.mgtik.hotspot.domain.repository.IAuditRepository
import ps.mgtik.hotspot.domain.model.LogSeverity
import ps.mgtik.hotspot.domain.model.SessionEndReason
import ps.mgtik.hotspot.ui.util.ErrorTranslator
import javax.inject.Inject

/** مصدر الأرقام المعروضة: LIVE = آخر جلب نجح، CACHED = آخر جلب فشل فنعرض آخر نتيجة ناجحة (قديمة) مُعلَّمة صراحةً. */
enum class LiveDataSource { NONE, LIVE, CACHED }

data class LiveSessionsUiState(
    val sessions: List<LiveSession> = emptyList(),
    /** وقت آخر جلب ناجح (ملّي ثانية)؛ null = لم ينجح أي جلب بعد (لا أرقام تُعرض). */
    val lastSuccessAt: Long? = null,
    val source: LiveDataSource = LiveDataSource.NONE,
    /** جلب جارٍ الآن (تحميل أول أو تحديث). */
    val isRefreshing: Boolean = false,
    /** خطأ آخر محاولة جلب؛ null عند النجاح. */
    @StringRes val errorResId: Int? = null,
    /** true = الخطأ انقطاع اتصال بالراوتر/عدم تهيئته؛ false = فشل جلب البيانات نفسها. */
    val errorIsConnectivity: Boolean = false,
    /** معرّف الجلسة التي يُنفَّذ فصلها الآن (يمنع الضغط المتكرر). */
    val disconnectingId: String? = null,
    @StringRes val messageResId: Int? = null,
    /** اسم المستخدم لتنسيق رسالة النتيجة (%1$s). */
    val messageArg: String? = null,
    val messageIsError: Boolean = false
) {
    val hasData: Boolean get() = lastSuccessAt != null
    val profileNames: List<String> get() = sessions.mapNotNull { it.profile }.distinct().sorted()
}

/**
 * LiveSessionsViewModel - المتصلون الآن من RouterOS عبر الخدمات الحالية (RouterConnectionManager)، مشترك بين
 * بطاقة Overview وشاشة المستخدمين النشطين.
 *
 * التحديث الدوري يعمل فقط ما دامت شاشة مرئية: كل شاشة تستدعي [acquire] عند ظهورها و[release] عند اختفائها أو ذهاب
 * التطبيق للخلفية؛ عند بلوغ العدّاد صفراً يُلغى الطلب الجاري ولا يبقى مؤقّت. حلقة واحدة متسلسلة تمنع تداخل الجلبات،
 * والتحديث اليدوي يوقظها فوراً دون طلب موازٍ. الأرقام المخزنة في الذاكرة فقط (لا تُعرض كمباشرة أبداً).
 */
@HiltViewModel
class LiveSessionsViewModel @Inject constructor(
    private val getLiveSessions: GetLiveSessionsUseCase,
    private val disconnectSession: DisconnectSessionUseCase,
    private val getRouterConfig: GetRouterConfigUseCase,
    private val getSessionState: GetSessionStateUseCase,
    private val sessionTracker: SessionTracker,
    private val auditRepository: IAuditRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LiveSessionsUiState())
    val uiState: StateFlow<LiveSessionsUiState> = _uiState.asStateFlow()

    private var holders = 0
    private var pollJob: Job? = null
    private var trackingJob: Job? = null
    private val refreshNow = Channel<Unit>(Channel.CONFLATED)

    companion object {
        const val POLL_INTERVAL_MS = 15_000L
    }

    init {
        // خروج المستخدم: لا تبقى جلسات الراوتر في الذاكرة لمن يدخل بعده
        getSessionState()
            .map { it.isAuthenticated }
            .distinctUntilChanged()
            .onEach { authed ->
                if (!authed) {
                    _uiState.value = LiveSessionsUiState()
                    sessionTracker.reset()
                }
            }
            .launchIn(viewModelScope)
    }

    fun acquire() {
        holders++
        if (holders == 1) {
            // العودة للشاشة: ما بين أيدينا من غيابها لا يُعدّ مباشراً حتى يؤكده أول جلب جديد
            if (_uiState.value.hasData) _uiState.value = _uiState.value.copy(source = LiveDataSource.CACHED)
            pollJob = viewModelScope.launch {
                while (true) {
                    fetchOnce()
                    // ننتظر الدورة التالية أو طلب تحديث يدوي، أيهما أسبق
                    withTimeoutOrNull(POLL_INTERVAL_MS) { refreshNow.receive() }
                }
            }
        }
    }

    fun release() {
        if (holders == 0) return
        holders--
        if (holders == 0) {
            pollJob?.cancel()
            pollJob = null
            _uiState.value = _uiState.value.copy(isRefreshing = false)
        }
    }

    /** تحديث يدوي: يوقظ الحلقة الجارية؛ لا طلب موازٍ لما هو جارٍ. */
    fun refresh() {
        if (holders > 0) refreshNow.trySend(Unit)
    }

    private suspend fun fetchOnce() {
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        val config = try { getRouterConfig().first() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        val result: AppResult<List<LiveSession>> = when {
            config == null || config.host.isBlank() -> AppResult.Error(ApiError.routerNotConfigured())
            else -> try {
                getLiveSessions(config)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.Error(ApiError.mapThrowable(e))
            }
        }
        // التسجيل التاريخي من قراءة حية ناجحة فقط؛ فشله لا يمنع عرض المتصلين (يُعاد تلقائياً بالجولة التالية)
        // غير حاجب للعرض (معاملات Firestore قد تتأخر دون اتصال)، وجولة واحدة في الوقت: التخطي آمن لأن الحسبة قائمة على الحالة.
        if (result is AppResult.Success && trackingJob?.isActive != true) {
            val snapshot = result.data
            trackingJob = viewModelScope.launch {
                try { sessionTracker.onLiveSessions(snapshot) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }
        _uiState.value = when (result) {
            is AppResult.Success -> _uiState.value.copy(
                sessions = result.data, lastSuccessAt = System.currentTimeMillis(), source = LiveDataSource.LIVE,
                isRefreshing = false, errorResId = null, errorIsConnectivity = false
            )
            is AppResult.Error -> _uiState.value.copy(
                // آخر نتيجة ناجحة (إن وُجدت) تبقى ظاهرة لكن مُعلَّمة «مخزنة»؛ لا تُقدَّم كمباشرة
                source = if (_uiState.value.hasData) LiveDataSource.CACHED else LiveDataSource.NONE,
                isRefreshing = false,
                errorResId = ErrorTranslator.resIdOf(result.error),
                errorIsConnectivity = ApiError.isRouterConnectivityCode(result.error.code) ||
                    result.error.code == ApiError.CODE_ROUTER_NOT_CONFIGURED
            )
            is AppResult.Loading -> _uiState.value.copy(isRefreshing = false)
        }
    }

    /** فصل الجلسة الحالية فقط (لا تعطيل للحساب). الواجهة تطلب التأكيد قبل الاستدعاء. */
    fun disconnect(session: LiveSession) {
        if (_uiState.value.disconnectingId != null) return
        _uiState.value = _uiState.value.copy(disconnectingId = session.sessionId, messageResId = null)
        viewModelScope.launch {
            val config = try { getRouterConfig().first() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            val res: AppResult<DisconnectOutcome> = if (config == null || config.host.isBlank()) {
                AppResult.Error(ApiError.routerNotConfigured())
            } else try {
                disconnectSession(config, session.sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.Error(ApiError.mapThrowable(e))
            }
            _uiState.value = when (res) {
                is AppResult.Success -> {
                    sessionTracker.hintEndReason(session.username, SessionEndReason.DISCONNECTED_BY_ADMIN)
                    auditRepository.logAction("DISCONNECT_SESSION", "Disconnected session of user ${session.username}", LogSeverity.INFO)
                    // تُزال فوراً من القائمة المعروضة؛ جلب التحقق التالي يؤكد حالة الراوتر الفعلية
                    _uiState.value.copy(
                        disconnectingId = null,
                        sessions = _uiState.value.sessions.filterNot { it.sessionId == session.sessionId },
                        messageResId = if (res.data == DisconnectOutcome.ALREADY_GONE) R.string.live_msg_already_gone else R.string.live_msg_disconnected,
                        messageArg = session.username, messageIsError = false
                    )
                }
                is AppResult.Error -> _uiState.value.copy(
                    disconnectingId = null, messageResId = ErrorTranslator.resIdOf(res.error), messageArg = null, messageIsError = true
                )
                is AppResult.Loading -> _uiState.value.copy(disconnectingId = null)
            }
            refresh()
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(messageResId = null, messageArg = null)
    }
}

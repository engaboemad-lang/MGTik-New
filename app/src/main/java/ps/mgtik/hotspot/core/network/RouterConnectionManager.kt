package ps.mgtik.hotspot.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RouterConnectionManager - المدير المركزي لدورة حياة اتصال RouterOS (§14 - §21)
 *
 * يدعم RouterOS 7.x (REST) و6.x (API الثنائي). في وضع AUTO تُجرَّب REST أولاً ثم API إن لم تتوفر،
 * ويُخزَّن الاتصال الناجح (مع TTL) فلا يُعاد الاكتشاف في كل طلب.
 */
@Singleton
class RouterConnectionManager @Inject constructor(
    private val restClient: RouterOsRestClient
) {
    // تُقرأ من خيوط متعددة (الساغا تنفّذ عدة طلبات متوازية، والواجهة تستعلم isConnected) فتُعلَّم @Volatile
    @Volatile private var activeApi: RouterApi? = null
    @Volatile private var lastUsedTimestamp: Long = 0
    @Volatile private var currentConfig: RouterConfig? = null
    @Volatile private var isLastConnectionHealthy: Boolean = false
    // آخر وسيلة نقل نجح بها الاكتشاف التلقائي لهذا الإعداد بالذات: راوتر v6 (أو v7 بلا www) لا يُعاد انتظار مهلة REST في كل إعادة اتصال
    @Volatile private var autoResolved: Pair<RouterConfig, RouterConnectionType>? = null
    private val mutex = Mutex()

    /**
     * بوابة العمليات التشغيلية: غير null = الراوتر غير متاح الآن (منقطع/وضع غير متصل/غير مُهيّأ) فتُرفض الطلبات فوراً
     * قبل أي اتصال بدل انتظار مهلة جديدة. يضبطها مدير الحالة المركزي وحده؛ الفحص والمراقبة يتجاوزانها (bypassGate).
     */
    @Volatile private var gateError: ApiError? = null

    fun setOperationGate(error: ApiError?) { gateError = error }

    // tryEmit بمخزن صغير: emit المعلِّق كان يُنفَّذ داخل القفل، فمشترك بطيء كان يجمّد كل طلبات الراوتر
    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents.asSharedFlow()

    suspend fun <T> execute(
        config: RouterConfig,
        bypassGate: Boolean = false,
        kind: RouterOpKind = RouterOpKind.STANDARD,
        block: suspend (RouterApi) -> T
    ): AppResult<T> {
        if (!bypassGate) gateError?.let { return AppResult.Error(it) }
        // كل عمل الراوتر (اتصال + تبادل + فك JSON) على Dispatchers.IO لا على خيط الاستدعاء (قد يكون الرئيسي)،
        // بمهلة قراءة حسب النوع ومهلة كلية تمنع استمرار العملية بلا حدود
        val result = withContext(Dispatchers.IO + RouterOpTimeout(kind)) {
            withTimeoutOrNull(kind.totalTimeoutMs) { executeInternal(config, block) }
        } ?: run {
            dropApi(null) // اتصال علق: يُغلق ليُعاد بناؤه في الطلب التالي
            AppResult.Error(ApiError.timeout())
        }
        // فشل اتصال حقيقي أثناء عملية تشغيلية: نبلّغ المراقب المركزي ليتحقق فوراً (لا ينتظر دورته)
        if (!bypassGate && result is AppResult.Error && ApiError.isRouterConnectivityCode(result.error.code)) {
            _connectionEvents.tryEmit(ConnectionEvent.OperationFailed(result.error))
        }
        return result
    }

    private suspend fun <T> executeInternal(
        config: RouterConfig,
        block: suspend (RouterApi) -> T
    ): AppResult<T> {
        val api = try {
            mutex.withLock { getOrConnect(config) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RouterApiException) {
            dropApi(null)
            return AppResult.Error(e.error)
        } catch (e: Exception) {
            dropApi(null)
            return AppResult.Error(ApiError.mapThrowable(e))
        }

        return try {
            val result = block(api)
            isLastConnectionHealthy = true
            AppResult.Success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RouterCommandRejected) {
            // الراوتر رفض هذا الأمر فقط؛ الاتصال سليم ولا يُسقط
            isLastConnectionHealthy = true
            AppResult.Error(
                ApiError(code = ApiError.CODE_ROUTER_REJECTED, message = e.routerMessage, retryable = false)
            )
        } catch (e: RouterApiException) {
            // خطأ مصنَّف من الراوتر (مثل 401): نُسقط الاتصال المخزَّن كي لا يُعاد استخدامه بمصادقة خاطئة
            dropApi(api)
            AppResult.Error(e.error)
        } catch (e: Exception) {
            dropApi(api)
            AppResult.Error(ApiError.mapThrowable(e))
        }
    }

    /**
     * يُسقط الاتصال المخزَّن، لكن فقط إن كان هو الذي فشل بنفسه ([failed]؛ null = الفشل قبل الحصول على اتصال).
     * طلبات متوازية على اتصال قديم قد تفشل بعد أن أُنشئ اتصال جديد سليم؛ إسقاطه كان يقطع الطلبات الجارية للجميع بلا سبب.
     */
    private suspend fun dropApi(failed: RouterApi?) {
        mutex.withLock {
            if (failed == null || activeApi === failed) {
                activeApi?.close()
                activeApi = null
                currentConfig = null
                isLastConnectionHealthy = false
            }
        }
    }

    private suspend fun getOrConnect(config: RouterConfig): RouterApi {
        val now = System.currentTimeMillis()

        val existing = activeApi
        if (existing != null &&
            existing.isUsable() &&
            config.sameEndpoint(currentConfig) &&
            (now - lastUsedTimestamp) < NetworkConfig.ROUTER_CONNECTION_TTL_MS
        ) {
            lastUsedTimestamp = now
            return existing
        }

        val isReconnect = existing != null && config.sameEndpoint(currentConfig)
        existing?.close()
        activeApi = null
        currentConfig = null

        val api = open(config)
        activeApi = api
        currentConfig = config
        lastUsedTimestamp = now

        if (isReconnect) {
            _connectionEvents.tryEmit(ConnectionEvent.AutoReconnected)
        }

        return api
    }

    /** نقطة حقن للاختبارات فقط: إن عُيّنت تُستعمل بدل فتح اتصال REST/API حقيقي. لا تُضبط في الإنتاج. */
    @Volatile internal var apiOverride: RouterApi? = null

    private suspend fun open(config: RouterConfig): RouterApi = apiOverride ?: when (config.connectionType) {
        RouterConnectionType.REST -> RestRouterApi(restClient.createClient(config), config.port)
        RouterConnectionType.API -> BinaryRouterApi.connect(config)
        RouterConnectionType.AUTO -> openAuto(config)
    }

    /**
     * REST أولاً (RouterOS 7.1+)، وإن لم تتوفر (لا خدمة www / 404 / انقطاع) فـ API الثنائي (RouterOS 6.x).
     * رفض المصادقة في REST يوقف الاكتشاف فوراً: كلمة المرور خاطئة ولا جدوى من تجربة وسيلة أخرى.
     */
    private suspend fun openAuto(config: RouterConfig): RouterApi {
        // وسيلة ناجحة سابقاً لنفس الإعداد: API أولاً (بلا انتظار مهلة REST)؛ أي فشل غير المصادقة يعيدنا للاكتشاف الكامل
        if (autoResolved?.takeIf { config.sameEndpoint(it.first) }?.second == RouterConnectionType.API) {
            try {
                return BinaryRouterApi.connect(config)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if ((e as? RouterApiException)?.error?.code == ApiError.CODE_ROUTER_AUTH_FAILED) throw e
                autoResolved = null
            }
        }

        val rest = RestRouterApi(restClient.createClient(config), config.port)
        val restError: ApiError = try {
            rest.printSingle("/system/resource")
            autoResolved = config to RouterConnectionType.REST
            return rest
        } catch (e: CancellationException) {
            rest.close()
            throw e
        } catch (e: Exception) {
            rest.close()
            (e as? RouterApiException)?.error ?: ApiError.mapThrowable(e)
        }
        if (restError.code == ApiError.CODE_ROUTER_AUTH_FAILED) throw RouterApiException(restError)

        try {
            val api = BinaryRouterApi.connect(config)
            autoResolved = config to RouterConnectionType.API
            return api
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val apiError = (e as? RouterApiException)?.error ?: ApiError.mapThrowable(e)
            throw RouterApiException(
                // v6 (لا REST) وكلمة المرور خاطئة: خطأ المصادقة من API هو الأدق؛ غير ذلك خطأ REST
                // (REST_UNAVAILABLE رسالته موحّدة وتذكر خدمتي REST وAPI معاً)
                if (apiError.code == ApiError.CODE_ROUTER_AUTH_FAILED) apiError else restError
            )
        }
    }

    fun isConnected(): Boolean {
        return activeApi?.isUsable() == true &&
                isLastConnectionHealthy &&
                (System.currentTimeMillis() - lastUsedTimestamp) < NetworkConfig.ROUTER_CONNECTION_TTL_MS
    }

    fun disconnect() {
        activeApi?.close()
        activeApi = null
        currentConfig = null
        isLastConnectionHealthy = false
    }

    /** إسقاط الاتصال المخزَّن تحت القفل (آمن أثناء وجود طلبات جارية)؛ يُستعمل عند انقطاع مؤكَّد أو تغيّر الجلسة. */
    suspend fun reset() = dropApi(null)

    sealed class ConnectionEvent {
        object AutoReconnected : ConnectionEvent()
        /** فشل اتصال (شبكة/مهلة/مصادقة/خدمة) في عملية تشغيلية؛ لا يشمل رفض الراوتر لأمر بعينه. */
        data class OperationFailed(val error: ApiError) : ConnectionEvent()
    }
}

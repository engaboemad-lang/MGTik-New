package ps.mgtik.hotspot.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import ps.mgtik.hotspot.core.network.RouterConnectionManager
import ps.mgtik.hotspot.core.network.bool
import ps.mgtik.hotspot.core.network.long
import ps.mgtik.hotspot.core.network.string
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.map
import ps.mgtik.hotspot.domain.model.ConfigLoadState
import ps.mgtik.hotspot.domain.model.DisconnectOutcome
import ps.mgtik.hotspot.domain.model.LiveSession
import ps.mgtik.hotspot.domain.model.LiveSnapshot
import ps.mgtik.hotspot.domain.usecase.LiveSessionRules
import ps.mgtik.hotspot.core.network.RouterApiException
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.domain.model.RouterConfig
import ps.mgtik.hotspot.domain.model.RouterHealth
import ps.mgtik.hotspot.domain.model.RouterInfo
import ps.mgtik.hotspot.domain.model.RouterInterface
import ps.mgtik.hotspot.domain.repository.IRouterRepository
import ps.mgtik.hotspot.domain.repository.IRouterStateManager
import ps.mgtik.hotspot.domain.repository.RouterConnectionEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RouterRepositoryImpl - تطبيق مراقبة وعمليات راوتر MikroTik (§19)
 * كل العمليات تمر عبر RouterConnectionManager وواجهة RouterApi: REST على RouterOS 7.x وAPI الثنائي على 6.x.
 */
@Singleton
class RouterRepositoryImpl @Inject constructor(
    private val stateManager: IRouterStateManager,
    private val connectionManager: RouterConnectionManager,
    private val userMan: ps.mgtik.hotspot.data.userman.UserManSource
) : IRouterRepository {

    /**
     * صحة الراوتر مشتقة من الحالة المركزية (مراقب واحد في التطبيق كله)، لا من استطلاع خاص بكل مشترك.
     * أثناء الاتصال/إعادة الاتصال تُعلَّم النتيجة [RouterHealth.isChecking] كي لا تُقرأ كانقطاع نهائي.
     */
    override fun getRouterHealth(): Flow<RouterHealth> =
        stateManager.status
            .map { st ->
                RouterHealth(
                    isOnline = st.isConnected,
                    cpuLoadPercent = if (st.isConnected) st.health.cpuLoadPercent else 0,
                    freeMemoryMb = if (st.isConnected) st.health.freeMemoryMb else 0L,
                    uptimeString = if (st.isConnected) st.health.uptimeString else "",
                    lastCheckedAt = st.health.lastCheckedAt,
                    isChecking = st.configLoad == ConfigLoadState.LOADING || st.isBusy
                )
            }
            .distinctUntilChanged()

    /** «عادت الاتصال» تأتي من المدير المركزي بعد انقطاع حقيقي فقط (لا من تجديد صلاحية الاتصال المخزَّن). */
    override fun getConnectionEvents(): Flow<RouterConnectionEvent> =
        stateManager.events
            .filter { it is ps.mgtik.hotspot.domain.model.RouterStateEvent.Reconnected }
            .map { RouterConnectionEvent.AutoReconnected }

    override suspend fun testConnection(config: RouterConfig): AppResult<RouterInfo> =
        stateManager.testConfig(config)

    override suspend fun getInterfaces(config: RouterConfig): AppResult<List<RouterInterface>> =
        connectionManager.execute(config) { api ->
            api.print("/interface").map { item ->
                RouterInterface(
                    name = item.string("name") ?: "unknown",
                    type = item.string("type") ?: "ether",
                    isRunning = item.bool("running"),
                    disabled = item.bool("disabled"),
                    macAddress = item.string("mac-address").orEmpty(),
                    rxBytes = item.long("rx-byte") ?: 0L,
                    txBytes = item.long("tx-byte") ?: 0L
                )
            }
        }

    override suspend fun rebootRouter(config: RouterConfig): AppResult<Unit> =
        connectionManager.execute(config) { api ->
            api.action("/system", "reboot")
        }.let { r ->
            // إعادة التشغيل لا تُعاد تلقائياً أبداً (قد تنفَّذ مرتين)
            if (r is AppResult.Error) AppResult.Error(
                ps.mgtik.hotspot.data.router.lifecycle.RouterOperationPolicy.classify(
                    ps.mgtik.hotspot.data.router.lifecycle.RouterOperation.REBOOT_ROUTER, r.error
                )
            ) else r
        }


    /** الجلسات الحيّة من User Manager (المصدر) مؤكَّدة على NAS؛ العالقة مستبعدة من القائمة (انظر [getLiveSnapshot]). */
    override suspend fun getLiveSessions(config: RouterConfig): AppResult<List<LiveSession>> =
        getLiveSnapshot(config).map { it.sessions }

    /**
     * الجلسات الحيّة من User Manager (مصدر الجلسات) ثم تأكيدها على NAS بقراءة فقط: جلسة يراها UM نشطة ولا يعرفها NAS (قُرئ بنجاح)
     * عالقة (تتأخر حزمة إيقاف المحاسبة) فلا تُعرض متصلة وتُرجَع في [LiveSnapshot.stale]؛ فشل قراءة NAS لا يُسقط القائمة (UNVERIFIED).
     */
    suspend fun getLiveSnapshot(config: RouterConfig): AppResult<LiveSnapshot> =
        userMan.withDialect(config, ps.mgtik.hotspot.core.network.RouterOpKind.BULK) { api, d ->
            val live = d.liveSessions(api)
            // الباقة قراءة ثانوية: فشلها لا يُسقط القائمة (تبقى الباقة غير معروفة)
            val profiles: Map<String, String> = if (live.isEmpty()) emptyMap() else try {
                d.userPackages(api)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyMap()
            }
            val sessions = live.mapNotNull { s ->
                if (s.id.isBlank()) return@mapNotNull null
                LiveSession(
                    sessionId = s.id,
                    username = s.username,
                    address = s.address,
                    macAddress = s.macAddress,
                    profile = profiles[s.username],
                    uptimeSeconds = s.uptimeSeconds,
                    uploadBytes = s.uploadBytes,
                    downloadBytes = s.downloadBytes,
                    nasAddress = s.nasAddress,
                    startedRaw = s.startedRaw
                )
            }
            val nasRows: List<LiveSessionRules.NasRow>? = if (sessions.isEmpty()) emptyList() else try {
                api.print(NAS_ACTIVE_PATH, props = listOf("user", "address", "mac-address"))
                    .map { LiveSessionRules.NasRow(it.string("user").orEmpty(), it.string("mac-address").orEmpty(), it.string("address").orEmpty()) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            LiveSessionRules.classify(sessions, nasRows)
        }

    /** سجل جلسات مستخدم من User Manager (تاريخي وجارٍ) بلا تكرار؛ الأحدث أولاً. */
    suspend fun getUserSessionRecords(config: RouterConfig, username: String, limit: Int = 200): AppResult<List<ps.mgtik.hotspot.data.userman.UmSession>> =
        userMan.withDialect(config, ps.mgtik.hotspot.core.network.RouterOpKind.BULK) { api, d -> d.sessionHistory(api, username, limit) }

    /**
     * فصل جلسة واحدة: تُحدَّد من User Manager ثم تُفصل على NAS بمطابقة المستخدم والـ MAC/العنوان (لا بمعرّف مشترك بين النظامين).
     * اختفاء الجلسة قبل الأمر، أو غياب مطابقتها على NAS = [DisconnectOutcome.ALREADY_GONE] (متحقَّق منه على NAS).
     */
    override suspend fun disconnectSession(config: RouterConfig, sessionId: String): AppResult<DisconnectOutcome> =
        userMan.withDialect(config, ps.mgtik.hotspot.core.network.RouterOpKind.BULK) { api, d ->
            val all = d.liveSessions(api)
            val session = all.firstOrNull { it.id == sessionId } ?: return@withDialect DisconnectOutcome.ALREADY_GONE
            // بلا MAC ولا عنوان لا يمكن تمييز هذه الجلسة عن غيرها: لو للمستخدم أكثر من جلسة لقُطعت كلها خطأً، فنرفض بدل التخمين
            if (session.macAddress.isBlank() && session.address.isBlank() &&
                all.count { it.username.equals(session.username, ignoreCase = true) } > 1
            ) {
                throw RouterApiException(ApiError(code = ApiError.CODE_ROUTER_REJECTED, message = "Session of ${session.username} is ambiguous (no MAC/address, multiple live sessions); use disconnect-all", retryable = false))
            }
            outcomeOf(disconnectOnNas(api, session.username, session.macAddress, session.address), session.username)
        }

    /** قطع كل جلسات مستخدم على NAS (للتعطيل والأرشفة). نتيجة الفصل منفصلة عن نتيجة تعطيل الحساب. */
    override suspend fun disconnectUserSessions(config: RouterConfig, username: String): AppResult<DisconnectOutcome> =
        userMan.withDialect(config) { api, _ -> outcomeOf(disconnectOnNas(api, username), username) }

    private fun outcomeOf(r: NasDisconnect, username: String): DisconnectOutcome {
        if (r.remaining > 0) {
            throw RouterApiException(ApiError(code = ApiError.CODE_ROUTER_REJECTED, message = "${r.remaining} session(s) of $username still active on the NAS after disconnect", retryable = true))
        }
        return if (r.removed > 0) DisconnectOutcome.DISCONNECTED else DisconnectOutcome.ALREADY_GONE
    }

    companion object {
        /** مسار تحكم NAS: يُستعمل للفصل والتحقق منه وتأكيد وجود الجلسة الحية (قراءة فقط)، لا مصدراً للجلسات ولا لحساب الاستهلاك. */
        private const val NAS_ACTIVE_PATH = "/ip/hotspot/active"

        /**
         * الدالة الوحيدة التي تفصل جلسات على NAS (الهوتسبوت يبقى بوابة الوصول). تُحذف فقط الصفوف التي يطابق مستخدمها [username]
         * (دون حساسية لحالة الأحرف)، وإن عُرف [macAddress] وجب أن يطابق، وإلا يُطابق [address] إن عُرف، وإلا يكفي المستخدم (كل جلساته).
         * بعد الحذف تُقرأ القائمة مجدداً: [NasDisconnect.remaining] > 0 = الفصل لم يتحقق.
         */
        internal suspend fun disconnectOnNas(api: ps.mgtik.hotspot.core.network.RouterApi, username: String, macAddress: String = "", address: String = ""): NasDisconnect {
            fun matches(row: kotlinx.serialization.json.JsonObject): Boolean {
                if (!row.string("user").orEmpty().equals(username, ignoreCase = true)) return false
                return when {
                    macAddress.isNotBlank() -> row.string("mac-address").orEmpty().equals(macAddress, ignoreCase = true)
                    address.isNotBlank() -> row.string("address").orEmpty() == address
                    else -> true
                }
            }
            val props = listOf(".id", "user", "address", "mac-address")
            // بلا استعلام على الراوتر: مطابقة الاسم هناك حساسة لحالة الأحرف وقد يختلف رسم الاسم على NAS عنه في User Manager
            val targets = api.print(NAS_ACTIVE_PATH, props = props).filter(::matches)
            var removed = 0
            for (row in targets) {
                val id = row.string(".id")?.takeIf(String::isNotBlank) ?: continue
                if (api.remove(NAS_ACTIVE_PATH, id)) removed++
            }
            val ids = targets.mapNotNull { it.string(".id") }.toSet()
            val remaining = if (targets.isEmpty()) 0 else api.print(NAS_ACTIVE_PATH, props = props)
                .count { it.string(".id") in ids }
            return NasDisconnect(matched = targets.size, removed = removed, remaining = remaining)
        }
    }

    internal data class NasDisconnect(val matched: Int, val removed: Int, val remaining: Int)
}

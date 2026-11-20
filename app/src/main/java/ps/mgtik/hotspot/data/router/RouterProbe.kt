package ps.mgtik.hotspot.data.router

import kotlinx.serialization.json.JsonObject
import ps.mgtik.hotspot.core.network.RouterApiException
import ps.mgtik.hotspot.core.network.RouterCommandRejected
import ps.mgtik.hotspot.core.network.RouterConnectionManager
import ps.mgtik.hotspot.core.network.RouterOpKind
import ps.mgtik.hotspot.core.network.int
import ps.mgtik.hotspot.core.network.long
import ps.mgtik.hotspot.core.network.printSingle
import ps.mgtik.hotspot.core.network.string
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.RouterConfig
import ps.mgtik.hotspot.domain.model.RouterHealth
import ps.mgtik.hotspot.domain.model.RouterInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * فحوص الاتصال الفعلية بالراوتر، مشتركة بين اختبار الإعدادات (RouterRepositoryImpl) والمراقبة المركزية
 * (RouterStateManager) كي لا يوجد منطق فحص مكرر. كلها تتجاوز بوابة العمليات: الفحص لا يُحجب بالانقطاع.
 */
internal object RouterProbe {

    /** اتصال كامل + معلومات الراوتر. /system/resource إلزامي؛ /system/routerboard اختياري (غير متاح على CHR). */
    suspend fun info(manager: RouterConnectionManager, config: RouterConfig): AppResult<RouterInfo> =
        manager.execute(config, bypassGate = true, kind = RouterOpKind.PROBE) { api ->
            val resource = api.printSingle("/system/resource")

            val board: JsonObject? = try {
                api.print("/system/routerboard").firstOrNull()
            } catch (e: RouterApiException) {
                if (e.error.code == ApiError.CODE_ROUTER_AUTH_FAILED) throw e
                null
            } catch (e: RouterCommandRejected) {
                null
            }

            val totalMemory = resource.long("total-memory") ?: 0L
            val freeMemory = resource.long("free-memory") ?: 0L
            val memoryUsedPercent =
                if (totalMemory > 0) (((totalMemory - freeMemory) * 100) / totalMemory).toInt().coerceIn(0, 100) else 0

            RouterInfo(
                id = board?.string("serial-number").orEmpty().ifBlank { "UNKNOWN" },
                name = board?.string("model") ?: resource.string("board-name") ?: "MikroTik",
                ip = config.host,
                // المنفذ الفعلي للنقل الذي نجح (REST أو API) وليس منفذ REST دائماً
                port = api.port,
                osMajor = "RouterOS ${resource.string("version").orEmpty()}".trim(),
                isConnected = true,
                cpuUsagePercent = resource.long("cpu-load")?.toInt() ?: 0,
                memoryUsagePercent = memoryUsedPercent,
                totalRamMb = totalMemory / (1024 * 1024),
                uptimeFormatted = resource.string("uptime").orEmpty(),
                lastCheckedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).format(Date())
            )
        }

    /** نبضة مراقبة خفيفة (طلب واحد: /system/resource). */
    suspend fun health(manager: RouterConnectionManager, config: RouterConfig): AppResult<RouterHealth> =
        manager.execute(config, bypassGate = true, kind = RouterOpKind.PROBE) { api ->
            val r = api.printSingle("/system/resource")
            RouterHealth(
                isOnline = true,
                cpuLoadPercent = r.int("cpu-load") ?: 0,
                freeMemoryMb = (r.long("free-memory") ?: 0L) / (1024 * 1024),
                uptimeString = r.string("uptime").orEmpty(),
                lastCheckedAt = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH).format(Date())
            )
        }

    /** صحة تقريبية من معلومات اتصال ناجح (قبل أول نبضة). */
    fun healthFrom(info: RouterInfo): RouterHealth = RouterHealth(
        isOnline = true,
        cpuLoadPercent = info.cpuUsagePercent,
        freeMemoryMb = info.totalRamMb * (100 - info.memoryUsagePercent) / 100,
        uptimeString = info.uptimeFormatted,
        lastCheckedAt = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH).format(Date())
    )
}

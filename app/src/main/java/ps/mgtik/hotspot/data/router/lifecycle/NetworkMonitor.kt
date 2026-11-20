package ps.mgtik.hotspot.data.router.lifecycle

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * مراقب اتصال الهاتف بالشبكة. يكشف تغيّر شبكة الهاتف فقط ولا يثبت أن الراوتر يعمل؛ لذلك تستمر نبضات الراوتر.
 */
interface NetworkMonitor {
    interface Listener {
        fun onAvailable()
        fun onLost()
    }

    fun start(listener: Listener)
    fun stop()
    /** وسائط النقل الحالية للشبكة الافتراضية (فارغة = لا شبكة). */
    fun transports(): Set<TransportKind>
}

/** لا شيء (الاختبارات والحالات التي لا يتوفر فيها Context). */
object NoopNetworkMonitor : NetworkMonitor {
    override fun start(listener: NetworkMonitor.Listener) = Unit
    override fun stop() = Unit
    override fun transports(): Set<TransportKind> = emptySet()
}

/** [ConnectivityManager.registerDefaultNetworkCallback] (API 24+؛ minSdk للمشروع 28). */
@Singleton
class AndroidNetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) : NetworkMonitor {

    private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var current: Set<TransportKind> = emptySet()

    @Synchronized
    override fun start(listener: NetworkMonitor.Listener) {
        stop()
        val manager = cm ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                current = map(manager.getNetworkCapabilities(network))
                listener.onAvailable()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                current = map(caps)
            }

            override fun onLost(network: Network) {
                current = emptySet()
                listener.onLost()
            }
        }
        try {
            manager.registerDefaultNetworkCallback(cb)
            callback = cb
        } catch (e: RuntimeException) {
            // حدّ النظام لعدد الـ callbacks أو سحب الإذن: المراقبة بالنبض تبقى تعمل
        }
    }

    /** يُلغي التسجيل (يُستدعى من shutdown() وteardown() في RouterStateManager). */
    @Synchronized
    override fun stop() {
        val cb = callback ?: return
        callback = null
        current = emptySet()
        try {
            cm?.unregisterNetworkCallback(cb)
        } catch (e: IllegalArgumentException) {
            // لم يكن مسجَّلاً
        }
    }

    override fun transports(): Set<TransportKind> = current

    private fun map(c: NetworkCapabilities?): Set<TransportKind> {
        if (c == null) return emptySet()
        val out = mutableSetOf<TransportKind>()
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) out += TransportKind.WIFI
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) out += TransportKind.CELLULAR
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) out += TransportKind.ETHERNET
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) out += TransportKind.VPN
        if (out.isEmpty()) out += TransportKind.OTHER
        return out
    }
}

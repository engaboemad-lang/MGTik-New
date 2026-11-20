package ps.mgtik.hotspot.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** فحص سريع لوجود شبكة بإنترنت قبل طلب الدخول السحابي (لا يخلط فقد الإنترنت مع خطأ بيانات الدخول). */
interface InternetChecker {
    fun hasInternet(): Boolean
}

@Singleton
class AndroidInternetChecker @Inject constructor(
    @ApplicationContext private val context: Context
) : InternetChecker {
    override fun hasInternet(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true // لا نمنع الدخول عند عدم القدرة على الفحص
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

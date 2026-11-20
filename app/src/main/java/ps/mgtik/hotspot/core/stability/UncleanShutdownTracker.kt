package ps.mgtik.hotspot.core.stability

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UncleanShutdownTracker @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("mgtik_runtime_flags", Context.MODE_PRIVATE)
    private val flag = CleanExitFlag(object : FlagStore {
        override fun get(key: String) = prefs.getBoolean(key, false)
        override fun set(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).commit() }
    })

    fun onLaunch(): Boolean = flag.onLaunch()
    fun onCleanExit() = flag.onCleanExit()
    fun onResumed() = flag.onResumed()
    fun hasPendingReport() = flag.hasPendingReport()
    fun markReported() = flag.markReported()
}

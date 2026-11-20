package ps.mgtik.hotspot

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import ps.mgtik.hotspot.BuildConfig
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.os.StrictMode
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.stability.CrashReporting
import ps.mgtik.hotspot.core.stability.UncleanShutdownTracker
import ps.mgtik.hotspot.domain.model.LogSeverity
import ps.mgtik.hotspot.domain.repository.IAuditRepository
import ps.mgtik.hotspot.domain.repository.IAuthRepository
import ps.mgtik.hotspot.data.local.cache.LocalCacheManager
import ps.mgtik.hotspot.domain.repository.IGenerationRepository
import ps.mgtik.hotspot.domain.repository.IOfflineScheduler
import ps.mgtik.hotspot.domain.repository.IRouterStateManager
import javax.inject.Inject

@HiltAndroidApp
class MgtikApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var offlineScheduler: IOfflineScheduler
    @Inject lateinit var generationRepository: IGenerationRepository
    @Inject lateinit var localCache: LocalCacheManager
    @Inject lateinit var routerStateManager: IRouterStateManager
    @Inject lateinit var uncleanShutdownTracker: UncleanShutdownTracker
    @Inject lateinit var auditRepository: IAuditRepository
    @Inject lateinit var authRepository: IAuthRepository
    @Inject lateinit var connectionNotifier: ps.mgtik.hotspot.data.notification.ConnectionNotifier

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        CrashReporting.install()
        if (BuildConfig.DEBUG) enableStrictMode()
        // علامة الإغلاق غير السليم: تُكتب الآن وتُمسح في prepareExit؛ بقاؤها عند الإقلاع يعني أن الجلسة السابقة لم تُغلق بتنظيم
        val previousWasUnclean = uncleanShutdownTracker.onLaunch()
        if (previousWasUnclean) CrashReporting.setKey("previous_exit", "unclean")
        appScope.launch { runCatching { reportUncleanShutdownWhenSignedIn() } }
        // المنظومة المركزية لاتصال الراوتر: تراقب الجلسة والإعدادات والاتصال طوال حياة العملية (لا مع شاشة بعينها)
        routerStateManager.start()
        connectionNotifier.start(appScope) // الإشعار الدائم (بخيار صريح) أثناء ظهور التطبيق فقط
        // دورة حياة التطبيق: المراقبة نبض كل 10ث في المقدمة وتتوقف في الخلفية (Doze/بطارية)، وتُستأنف بنبضة فورية
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = routerStateManager.setAppForeground(true)
            override fun onStop(owner: LifecycleOwner) = routerStateManager.setAppForeground(false)
        })
        // ما تراكم أثناء إغلاق التطبيق يُنفَّذ الآن، والدورة الدورية شبكة أمان لما يتعطل لاحقاً
        offlineScheduler.schedulePeriodicSync()
        offlineScheduler.scheduleSync()
        // كاش بتواريخ بتنسيق قديم يُحذف (يعاد ملؤه من السحابة) كي لا يختلط بالجديد في الترتيب النصي
        appScope.launch { runCatching { localCache.purgeLegacyDateRows() } }
        appScope.launch { runCatching { localCache.purgeDemoSubscriptions() } }
        // ساغات التوليد التي قُتل تطبيقها أثناءها تُستأنف من آخر نقطة محفوظة
        appScope.launch { runCatching { generationRepository.resumePendingRuns() } }
    }

    /** StrictMode في Debug فقط: يسجّل (لا يُسقط) أي قرص/شبكة على الخيط الرئيسي وتسريبات الموارد. */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
    }

    /** يسجّل «إغلاق غير سليم» في AuditRepository عند أول جلسة مصادَق عليها؛ يبقى معلّقاً إن فشل التسجيل. */
    private suspend fun reportUncleanShutdownWhenSignedIn() {
        if (!uncleanShutdownTracker.hasPendingReport()) return
        authRepository.sessionState.first { it.isAuthenticated }
        val result = auditRepository.logAction(
            "UNCLEAN_SHUTDOWN",
            "Previous session ended without an orderly exit (no prepareExit)",
            LogSeverity.WARNING
        )
        if (result is AppResult.Success) uncleanShutdownTracker.markReported()
    }
}

package ps.mgtik.hotspot.ui.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import ps.mgtik.hotspot.domain.model.RouterStatus
import ps.mgtik.hotspot.domain.usecase.ObserveRouterStatusUseCase
import ps.mgtik.hotspot.domain.usecase.RetryRouterConnectionUseCase
import ps.mgtik.hotspot.domain.usecase.SkipRouterConnectionUseCase
import javax.inject.Inject

/**
 * RouterStatusViewModel - نافذة الواجهة على الحالة المركزية للراوتر (شريط الحالة + حوار الانقطاع).
 * لا يحمل منطق اتصال: كل شيء في IRouterStateManager؛ هنا تمرير للحالة والأفعال فقط.
 */
@HiltViewModel
class RouterStatusViewModel @Inject constructor(
    observeRouterStatus: ObserveRouterStatusUseCase,
    private val retryConnection: RetryRouterConnectionUseCase,
    private val skipConnection: SkipRouterConnectionUseCase,
    private val stateManager: ps.mgtik.hotspot.domain.repository.IRouterStateManager
) : ViewModel() {

    val status: StateFlow<RouterStatus> = observeRouterStatus()

    /** سجل الاتصال التشخيصي (آخر 50 حدثاً). */
    val log: StateFlow<List<ps.mgtik.hotspot.domain.model.ConnectionLogEntry>> = stateManager.connectionLog

    fun exportLog(): String = stateManager.exportConnectionLog()

    fun retry() = retryConnection()
    fun skip() = skipConnection()
}

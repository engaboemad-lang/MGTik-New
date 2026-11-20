package ps.mgtik.hotspot.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test
import ps.mgtik.hotspot.domain.model.ConfigLoadState
import ps.mgtik.hotspot.domain.model.RouterConnectionState
import ps.mgtik.hotspot.ui.viewmodel.StartupStage
import ps.mgtik.hotspot.ui.viewmodel.StartupUserMan
import ps.mgtik.hotspot.ui.viewmodel.deriveStartupStage

/** المهمة 1: قرار وجهة الإقلاع (resolveStartRoute) واشتقاق مرحلة الإقلاع من حالة الراوتر. */
class StartupRouteTest {

    private val host = "10.0.0.1"

    @Test fun notSignedIn_goesToLogin() =
        assertEquals(Screen.Login.route, resolveStartRoute(isAuthenticated = false, routerHost = host, stage = StartupStage.READY))

    @Test fun routerNotConfigured_goesToS2() =
        assertEquals(Screen.RouterSetup.route, resolveStartRoute(isAuthenticated = true, routerHost = " ", stage = StartupStage.READY))

    @Test fun routerConnectionFailed_goesToS2() =
        assertEquals(Screen.RouterSetup.route, resolveStartRoute(true, host, StartupStage.ROUTER_FAILED))

    @Test fun connectedButUserManNotReady_goesToS32() =
        assertEquals(Screen.UserManSetup.route, resolveStartRoute(true, host, StartupStage.USERMAN_NOT_READY))

    @Test fun allChecksPass_goesToOverview() =
        assertEquals(Screen.Overview.route, resolveStartRoute(true, host, StartupStage.READY))

    @Test fun explicitContinueOffline_goesToOverviewEvenWhenFailed() {
        assertEquals(Screen.Overview.route, resolveStartRoute(true, host, StartupStage.ROUTER_FAILED, continueOffline = true))
        assertEquals(Screen.Overview.route, resolveStartRoute(true, host, StartupStage.USERMAN_NOT_READY, continueOffline = true))
    }

    @Test fun unfinishedCheck_neverOpensOverviewSilently() =
        assertEquals(Screen.RouterSetup.route, resolveStartRoute(true, host, StartupStage.CHECKING))

    @Test fun offlineChoiceDoesNotSkipLoginOrSetup() {
        assertEquals(Screen.Login.route, resolveStartRoute(false, host, StartupStage.READY, continueOffline = true))
        assertEquals(Screen.RouterSetup.route, resolveStartRoute(true, "", StartupStage.READY, continueOffline = true))
    }

    // ── اشتقاق المرحلة ──
    private fun stage(
        state: RouterConnectionState,
        um: StartupUserMan = StartupUserMan.UNKNOWN,
        load: ConfigLoadState = ConfigLoadState.LOADED,
        error: String? = null,
        attention: Boolean = false,
        offline: Boolean = false,
        loggedOut: Boolean = false,
        h: String = host
    ) = deriveStartupStage(load, h, state, error, attention, loggedOut, offline, um)

    @Test fun configLoading_isChecking() =
        assertEquals(StartupStage.CHECKING, stage(RouterConnectionState.DISCONNECTED, load = ConfigLoadState.LOADING))

    @Test fun connecting_isChecking() =
        assertEquals(StartupStage.CHECKING, stage(RouterConnectionState.CONNECTING))

    @Test fun disconnectedBeforeAnyAttempt_isCheckingNotFailure() =
        assertEquals(StartupStage.CHECKING, stage(RouterConnectionState.DISCONNECTED))

    @Test fun connectionFailed_isRouterFailed() =
        assertEquals(StartupStage.ROUTER_FAILED, stage(RouterConnectionState.CONNECTION_FAILED, error = "ERR_X", attention = true))

    @Test fun disconnectedWithError_isRouterFailed() =
        assertEquals(StartupStage.ROUTER_FAILED, stage(RouterConnectionState.DISCONNECTED, error = "ERR_X"))

    @Test fun connectedUserManUnknown_isStillChecking() =
        assertEquals(StartupStage.CHECKING, stage(RouterConnectionState.CONNECTED, StartupUserMan.UNKNOWN))

    @Test fun connectedUserManNotReady() =
        assertEquals(StartupStage.USERMAN_NOT_READY, stage(RouterConnectionState.CONNECTED, StartupUserMan.NOT_READY))

    @Test fun connectedUserManReady() =
        assertEquals(StartupStage.READY, stage(RouterConnectionState.CONNECTED, StartupUserMan.READY))

    @Test fun offlineModeIsExplicitDecision() =
        assertEquals(StartupStage.READY, stage(RouterConnectionState.CONNECTION_FAILED, offline = true))

    @Test fun cloudConfigUnavailable_isRouterFailedNotReady() =
        assertEquals(StartupStage.ROUTER_FAILED, stage(RouterConnectionState.DISCONNECTED, load = ConfigLoadState.UNAVAILABLE))
}

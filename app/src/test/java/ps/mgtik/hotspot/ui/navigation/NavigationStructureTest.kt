package ps.mgtik.hotspot.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** بنية التنقل بعد إعادة التنظيم: الشريط السفلي ≤ 5، الإعدادات في القائمة الجانبية، ومسارات فريدة. */
class NavigationStructureTest {

    @Test
    fun bottomBarHasAtMostFiveItems() {
        assertTrue(Screen.bottomNavItems.size in 3..5)
    }

    @Test
    fun settingsIsNotInBottomBarButIsADrawerRoot() {
        assertFalse(Screen.bottomNavItems.contains(Screen.Settings))
        assertTrue(Screen.Settings.route in Screen.drawerRootRoutes)
    }

    @Test
    fun bottomBarItemsAreLocalizedAndDistinct() {
        assertTrue(Screen.bottomNavItems.all { it.titleRes != null })
        assertEquals(Screen.bottomNavItems.size, Screen.bottomNavItems.map { it.route }.toSet().size)
    }

    @Test
    fun startRouteDecision() {
        assertEquals(Screen.Login.route, resolveStartRoute(isAuthenticated = false, routerHost = "10.0.0.1"))
        assertEquals(Screen.RouterSetup.route, resolveStartRoute(isAuthenticated = true, routerHost = " "))
        assertEquals(Screen.Overview.route, resolveStartRoute(isAuthenticated = true, routerHost = "10.0.0.1"))
    }
}

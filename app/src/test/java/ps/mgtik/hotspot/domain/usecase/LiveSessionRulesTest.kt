package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ps.mgtik.hotspot.domain.model.LiveSession
import ps.mgtik.hotspot.domain.usecase.LiveSessionRules.SortBy

class LiveSessionRulesTest {

    private fun s(
        user: String, ip: String = "10.0.0.1", mac: String = "AA:BB:CC:00:00:01",
        profile: String? = "p1", uptime: Long? = 60, up: Long = 0, down: Long = 0
    ) = LiveSession("*$user", user, ip, mac, profile, uptime, up, down)

    @Test fun uptime_parses_routeros_formats() {
        assertEquals(5L, LiveSessionRules.parseUptimeSeconds("5s"))
        assertEquals(3_725L, LiveSessionRules.parseUptimeSeconds("1h2m5s"))
        assertEquals(694_861L, LiveSessionRules.parseUptimeSeconds("1w1d1h1m1s"))
        assertEquals(605L, LiveSessionRules.parseUptimeSeconds("00:10:05"))
        assertEquals(0L, LiveSessionRules.parseUptimeSeconds("0s"))
    }

    @Test fun uptime_unreadable_is_null_not_zero() {
        assertNull(LiveSessionRules.parseUptimeSeconds(null))
        assertNull(LiveSessionRules.parseUptimeSeconds(""))
        assertNull(LiveSessionRules.parseUptimeSeconds("abc"))
        assertNull(LiveSessionRules.parseUptimeSeconds("12"))
    }

    @Test fun search_matches_name_ip_or_mac_ignoring_case() {
        val list = listOf(s("Ahmad", ip = "10.0.0.5"), s("sara", mac = "DE:AD:BE:EF:00:09"), s("omar"))
        assertEquals(listOf("Ahmad"), LiveSessionRules.apply(list, query = "ahm").map { it.username })
        assertEquals(listOf("Ahmad"), LiveSessionRules.apply(list, query = "10.0.0.5").map { it.username })
        assertEquals(listOf("sara"), LiveSessionRules.apply(list, query = "de:ad").map { it.username })
    }

    @Test fun profile_filter_matches_exactly_and_unknown_profile_matches_none() {
        val list = listOf(s("a", profile = "gold"), s("b", profile = "silver"), s("c", profile = null))
        assertEquals(listOf("a"), LiveSessionRules.apply(list, profile = "gold").map { it.username })
        assertEquals(listOf("a", "b", "c"), LiveSessionRules.apply(list, sortBy = SortBy.NAME).map { it.username })
    }

    @Test fun sorting_by_uptime_traffic_and_name() {
        val list = listOf(s("b", uptime = 10, up = 5, down = 5), s("a", uptime = 99, up = 1, down = 1), s("c", uptime = null, up = 50, down = 50))
        assertEquals(listOf("a", "b", "c"), LiveSessionRules.apply(list, sortBy = SortBy.UPTIME).map { it.username })
        assertEquals(listOf("c", "b", "a"), LiveSessionRules.apply(list, sortBy = SortBy.TRAFFIC).map { it.username })
        assertEquals(listOf("a", "b", "c"), LiveSessionRules.apply(list, sortBy = SortBy.NAME).map { it.username })
    }
}

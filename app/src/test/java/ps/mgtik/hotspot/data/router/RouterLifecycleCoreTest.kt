package ps.mgtik.hotspot.data.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.data.router.lifecycle.*
import ps.mgtik.hotspot.domain.model.ConnectionLogEntry
import ps.mgtik.hotspot.domain.model.RouterConnectionState
import ps.mgtik.hotspot.domain.model.RouterConnectionState.*

class RouterLifecycleCoreTest {

    // ── 3.2 Backoff ──
    @Test fun backoffSequenceThenPinsAt60() {
        val b = ReconnectBackoff()
        val seq = List(8) { b.nextDelayMs() / 1000 }
        assertEquals(listOf(2L, 5L, 10L, 30L, 60L, 60L, 60L, 60L), seq)
    }

    @Test fun manualRetryResetsBackoff() {
        val b = ReconnectBackoff()
        repeat(4) { b.nextDelayMs() }
        b.reset()
        assertEquals(2_000L, b.nextDelayMs())
    }

    @Test fun flappingFloorSlowsRetries() {
        val b = ReconnectBackoff().withFloor(30)
        assertEquals(30_000L, b.nextDelayMs())
        assertEquals(30_000L, b.nextDelayMs())
        assertEquals(30_000L, b.nextDelayMs())
        assertEquals(30_000L, b.nextDelayMs())
        assertEquals(60_000L, b.nextDelayMs())
    }

    @Test fun authFailureIsNotRetried() {
        assertFalse(ReconnectPolicy.isRetryable("ERR_ROUTER_AUTH_FAILED", true))
        assertFalse(ReconnectPolicy.isRetryable("ERR_ROUTER_NOT_CONFIGURED", true))
    }

    @Test fun timeoutAndUnreachableAreRetried() {
        assertTrue(ReconnectPolicy.isRetryable("ERR_TIMEOUT", false))
        assertTrue(ReconnectPolicy.isRetryable("ERR_NETWORK_UNAVAILABLE", false))
    }

    @Test fun unknownCodeFollowsApiErrorFlag() {
        assertTrue(ReconnectPolicy.isRetryable("ERR_X", true))
        assertFalse(ReconnectPolicy.isRetryable("ERR_X", false))
    }

    @Test fun stopConditions() {
        assertTrue(ReconnectPolicy.shouldStop(loggedOut = true, offlineMode = false, configuring = false, notConfigured = false))
        assertTrue(ReconnectPolicy.shouldStop(false, true, false, false))
        assertTrue(ReconnectPolicy.shouldStop(false, false, true, false))
        assertFalse(ReconnectPolicy.shouldStop(false, false, false, false))
    }

    // ── 3.3 المدة ──
    @Test fun silentRecoveryKeepsDurationAndCountsReconnect() {
        val t = ConnectionTimeline()
        t.onConnected(1_000)
        t.onSilentRecovery()
        assertEquals(1_000L, t.connectedSince)
        assertEquals(1, t.reconnectCount)
        assertEquals(4_000L, t.connectedForMs(5_000))
    }

    @Test fun confirmedDisconnectThenReconnectStartsNewDuration() {
        val t = ConnectionTimeline()
        t.onConnected(1_000)
        t.onConfirmedDisconnect(9_000)
        assertNull(t.connectedSince)
        assertEquals(9_000L, t.lastDisconnectAt)
        t.onConnected(20_000)
        assertEquals(20_000L, t.connectedSince)
        assertEquals(1, t.reconnectCount)
        assertEquals(9_000L, t.lastDisconnectAt)
    }

    @Test fun firstConnectDoesNotCountAsReconnect() {
        val t = ConnectionTimeline()
        t.onConnected(5)
        assertEquals(0, t.reconnectCount)
    }

    // ── 4.1 الآلة ──
    @Test fun everyDefinedTransitionIsAllowedAndOthersRejected() {
        val all = RouterConnectionState.values()
        for (from in all) for (to in all) {
            val expected = from == to || to in RouterStateTransitions.allowed.getValue(from)
            assertEquals("$from->$to", expected, RouterStateTransitions.isAllowed(from, to))
        }
    }

    @Test fun illegalTransitionsRejected() {
        assertFalse(RouterStateTransitions.isAllowed(DISCONNECTED, CONNECTED))
        assertFalse(RouterStateTransitions.isAllowed(NOT_CONFIGURED, CONNECTED))
        assertFalse(RouterStateTransitions.isAllowed(CONNECTION_FAILED, CONNECTED))
        assertFalse(RouterStateTransitions.isAllowed(CONNECTED, CONNECTION_FAILED))
        assertFalse(RouterStateTransitions.isAllowed(NOT_CONFIGURED, RECONNECTING))
    }

    @Test fun normalLifecyclePathsAllowed() {
        assertTrue(RouterStateTransitions.isAllowed(DISCONNECTED, CONNECTING))
        assertTrue(RouterStateTransitions.isAllowed(CONNECTING, CONNECTED))
        assertTrue(RouterStateTransitions.isAllowed(CONNECTED, RECONNECTING))
        assertTrue(RouterStateTransitions.isAllowed(RECONNECTING, CONNECTED))
        assertTrue(RouterStateTransitions.isAllowed(RECONNECTING, DISCONNECTED))
        assertTrue(RouterStateTransitions.isAllowed(CONNECTED, CONFIGURING))
        assertTrue(RouterStateTransitions.isAllowed(CONFIGURING, CONNECTED))
    }

    private fun facts(
        state: RouterConnectionState, info: Boolean = false, err: String? = null, attention: Boolean = false,
        offline: Boolean = false, loggedOut: Boolean = false, since: Boolean = false
    ) = StatusFacts(state, info, err, attention, offline, loggedOut, since)

    @Test fun contradictoryCombinationsRejected() {
        assertEquals("CONNECTED_WITHOUT_INFO", RouterStateInvariants.violation(facts(CONNECTED)))
        assertEquals("CONNECTED_WITH_ERROR", RouterStateInvariants.violation(facts(CONNECTED, info = true, err = "E")))
        assertEquals("CONNECTED_WHILE_OFFLINE_OR_LOGGED_OUT", RouterStateInvariants.violation(facts(CONNECTED, info = true, offline = true)))
        assertEquals("DURATION_WITHOUT_CONNECTION", RouterStateInvariants.violation(facts(DISCONNECTED, since = true)))
        assertEquals("LOGGED_OUT_AND_OFFLINE", RouterStateInvariants.violation(facts(DISCONNECTED, loggedOut = true, offline = true)))
        assertEquals("LOGGED_OUT_NOT_DISCONNECTED", RouterStateInvariants.violation(facts(CONNECTING, loggedOut = true)))
        assertEquals("OFFLINE_MODE_WITH_ACTIVE_STATE", RouterStateInvariants.violation(facts(CONNECTING, offline = true)))
    }

    @Test fun durationAllowedWhileConfiguring() {
        assertNull(RouterStateInvariants.violation(facts(CONFIGURING, info = true, since = true)))
    }

    @Test fun consistentCombinationsAccepted() {
        assertNull(RouterStateInvariants.violation(facts(CONNECTED, info = true, since = true)))
        assertNull(RouterStateInvariants.violation(facts(DISCONNECTED, err = "E", attention = true)))
        assertNull(RouterStateInvariants.violation(facts(DISCONNECTED, loggedOut = true)))
        assertNull(RouterStateInvariants.violation(facts(CONNECTION_FAILED, offline = true)))
        assertNull(RouterStateInvariants.violation(facts(NOT_CONFIGURED)))
    }

    // ── 4.3 التذبذب ──
    @Test fun singleFailedPulseDoesNotConfirm() {
        val w = FailureWindow(required = 2, windowMs = 15_000)
        assertFalse(w.onFailure(0))
    }

    @Test fun consecutiveFailuresWithinWindowConfirm() {
        val w = FailureWindow(2, 15_000)
        assertFalse(w.onFailure(0))
        assertTrue(w.onFailure(2_000))
    }

    @Test fun failuresOutsideWindowDoNotConfirm() {
        val w = FailureWindow(2, 15_000)
        assertFalse(w.onFailure(0))
        assertFalse(w.onFailure(60_000))
    }

    @Test fun successClearsFailures() {
        val w = FailureWindow(2, 15_000)
        w.onFailure(0); w.onSuccess()
        assertFalse(w.onFailure(1_000))
    }

    @Test fun flapDetectedAfterRepeatedOutagesAndAnnouncedOnce() {
        val f = FlapDetector(threshold = 3, windowMs = 120_000)
        assertFalse(f.onConfirmedOutage(0).flapping)
        assertFalse(f.onConfirmedOutage(10_000).flapping)
        val third = f.onConfirmedOutage(20_000)
        assertTrue(third.flapping); assertTrue(third.announceUnstable); assertTrue(third.alertAllowed)
        val fourth = f.onConfirmedOutage(30_000)
        assertTrue(fourth.flapping); assertFalse(fourth.announceUnstable); assertFalse(fourth.alertAllowed)
        assertTrue(f.unstable)
    }

    @Test fun flappingEndsAfterQuietWindow() {
        val f = FlapDetector(3, 120_000)
        repeat(3) { f.onConfirmedOutage(it * 1_000L) }
        assertTrue(f.unstable)
        f.onStable(500_000)
        assertFalse(f.unstable)
    }

    // ── 4.5 إعادة التشغيل ──
    @Test fun uptimeParsing() {
        assertEquals(1L * 604800 + 2 * 86400 + 3 * 3600 + 4 * 60 + 5, UptimeParser.seconds("1w2d3h4m5s"))
        assertEquals(3L * 3600 + 4 * 60 + 5, UptimeParser.seconds("3h4m5s"))
        assertEquals(45L, UptimeParser.seconds("45s"))
        assertNull(UptimeParser.seconds(""))
        assertNull(UptimeParser.seconds("garbage"))
        assertNull(UptimeParser.seconds("3h junk"))
    }

    @Test fun uptimeDropMeansReboot() {
        assertTrue(UptimeParser.rebooted(10_000, 40))
        assertFalse(UptimeParser.rebooted(10_000, 10_030))
        assertFalse(UptimeParser.rebooted(10_000, 9_998)) // فرق ثوانٍ لا يعني إعادة تشغيل
        assertFalse(UptimeParser.rebooted(null, 40))
        assertFalse(UptimeParser.rebooted(10, null))
    }

    // ── 4.6 خارج الشبكة ──
    @Test fun offRouterNetworkOnlyWhenUnreachableAndNoLocalTransport() {
        assertTrue(OffRouterNetworkRule.isOffRouterNetwork(setOf(TransportKind.CELLULAR), "192.168.88.1", false))
        assertFalse(OffRouterNetworkRule.isOffRouterNetwork(setOf(TransportKind.CELLULAR), "192.168.88.1", true))
        assertFalse(OffRouterNetworkRule.isOffRouterNetwork(setOf(TransportKind.WIFI), "192.168.88.1", false))
        assertFalse(OffRouterNetworkRule.isOffRouterNetwork(setOf(TransportKind.CELLULAR, TransportKind.VPN), "192.168.88.1", false))
        assertFalse(OffRouterNetworkRule.isOffRouterNetwork(setOf(TransportKind.CELLULAR), "router.example.com", false))
        assertFalse(OffRouterNetworkRule.isOffRouterNetwork(setOf(TransportKind.CELLULAR), "8.8.8.8", false))
    }

    // ── 4.7 السجل ──
    @Test fun journalKeepsLast50() {
        val j = ConnectionJournal(50)
        repeat(60) { j.add(ConnectionLogEntry(it.toLong(), CONNECTED, null, null, null)) }
        assertEquals(50, j.size)
        assertEquals(10L, j.snapshot().first().atMillis)
        assertEquals(59L, j.snapshot().last().atMillis)
    }

    @Test fun journalExportHasNoSecretsAndIsOneLinePerEvent() {
        val j = ConnectionJournal()
        j.add(ConnectionLogEntry(1, DISCONNECTED, "ERR_TIMEOUT", 125_000, "fail"))
        j.add(ConnectionLogEntry(2, CONNECTED, null, null, "ok"))
        val lines = j.export().lines()
        assertEquals(2, lines.size)
        assertEquals("1 | DISCONNECTED | ERR_TIMEOUT | 125s | fail", lines[0])
    }

    // ── 4.8 الجودة ──
    @Test fun singleSlowPulseDoesNotChangeQuality() {
        val q = QualityTracker()
        listOf(100L, 120L, 110L, 3_000L, 100L).forEach(q::record)
        assertEquals(LinkQuality.GOOD, q.quality())
    }

    @Test fun sustainedHighRttIsSlow() {
        val q = QualityTracker()
        listOf(1_200L, 1_500L, 1_100L, 1_300L, 1_400L).forEach(q::record)
        assertEquals(LinkQuality.SLOW, q.quality())
    }

    @Test fun repeatedFailedPulsesAreIntermittent() {
        val q = QualityTracker()
        listOf(100L, null, 120L, null, 100L).forEach(q::record)
        assertEquals(LinkQuality.INTERMITTENT, q.quality())
    }

    @Test fun tooFewSamplesGiveNoVerdict() {
        val q = QualityTracker()
        q.record(100); q.record(100)
        assertNull(q.quality())
    }
}

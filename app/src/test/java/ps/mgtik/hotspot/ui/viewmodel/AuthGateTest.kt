package ps.mgtik.hotspot.ui.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import ps.mgtik.hotspot.domain.model.UserSession
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * بوابة المصادقة: المراقبة السحابية تبدأ مع كل دخول (مصدر جديد) وتُلغى عند الخروج مع استدعاء onSignedOut،
 * فلا تموت المراقبة نهائياً بسبب خطأ صلاحيات قبل الدخول، ولا تبقى بيانات مستخدم سابق.
 */
class AuthGateTest {

    private val signedOut = UserSession(isAuthenticated = false)
    private val signedIn = UserSession(isAuthenticated = true)

    @Test
    fun sourceRestartsOnEachSignInAndSignOutCallbackFires() = runBlocking {
        val sessions = MutableSharedFlow<UserSession>()
        val received = CopyOnWriteArrayList<Int>()
        val signOuts = AtomicInteger(0)
        val builds = AtomicInteger(0)

        val job = launch(Dispatchers.Default) {
            sessions.whenAuthenticated(onSignedOut = { signOuts.incrementAndGet() }) {
                val id = builds.incrementAndGet()
                flow { emit(id); awaitCancellation() }
            }.collect { received.add(it) }
        }

        delay(150)
        sessions.emit(signedOut); delay(150)
        sessions.emit(signedIn); delay(150)
        sessions.emit(signedOut); delay(150)
        sessions.emit(signedIn); delay(150)
        job.cancel()

        assertEquals(listOf(1, 2), received.toList())
        assertEquals(2, signOuts.get())
        assertEquals(2, builds.get())
    }

    @Test
    fun repeatedSameStateDoesNotRebuildSource() = runBlocking {
        val sessions = MutableSharedFlow<UserSession>()
        val builds = AtomicInteger(0)
        val job = launch(Dispatchers.Default) {
            sessions.whenAuthenticated { builds.incrementAndGet(); flow<Int> { awaitCancellation() } }.collect { }
        }
        delay(150)
        sessions.emit(signedIn); delay(100)
        sessions.emit(signedIn.copy(lastLoginAt = "later")); delay(100)
        job.cancel()
        assertEquals(1, builds.get())
    }
}

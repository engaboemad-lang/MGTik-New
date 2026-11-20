package ps.mgtik.hotspot.data.cloud.source

import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.safeApiCall
import ps.mgtik.hotspot.data.cloud.util.FirebaseAuthErrorMapper
import ps.mgtik.hotspot.domain.model.AuthCredentials
import ps.mgtik.hotspot.domain.model.AuthUser
import ps.mgtik.hotspot.domain.model.UserSession
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CloudAuthDataSource - تطبيق مصدر بيانات التوثيق السحابي (Firebase Auth).
 */
@Singleton
class CloudAuthDataSource @Inject constructor(
    private val firebaseAuth: FirebaseAuth
) {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())

    /** ينفّذ عملية Firebase ويحوّل أخطاءها إلى AppResult؛ ولا يبتلع إلغاء الـ coroutine. */
    private suspend inline fun <T> authCall(block: () -> T): AppResult<T> = try {
        AppResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppResult.Error(FirebaseAuthErrorMapper.map(e))
    }

    val sessionFlow: Flow<UserSession> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            val firebaseUser = auth.currentUser
            // جلسة مجهولة قديمة (بقيت من نسخة سابقة كان فيها «دخول كضيف») ليست مستخدماً مسجَّلاً: تُنهى ولا تدخل التطبيق
            if (firebaseUser != null && firebaseUser.isAnonymous) {
                auth.signOut()
                trySend(UserSession(user = null, isAuthenticated = false))
            } else if (firebaseUser != null) {
                val user = mapFirebaseUser(firebaseUser)
                trySend(
                    UserSession(
                        user = user,
                        isAuthenticated = true,
                        lastLoginAt = dateFormatter.format(
                            firebaseUser.metadata?.lastSignInTimestamp?.let(Instant::ofEpochMilli) ?: Instant.now()
                        )
                    )
                )
            } else {
                trySend(UserSession(user = null, isAuthenticated = false))
            }
        }
        firebaseAuth.addAuthStateListener(listener)
        awaitClose { firebaseAuth.removeAuthStateListener(listener) }
    }

    suspend fun signInWithEmailAndPassword(credentials: AuthCredentials): AppResult<AuthUser> {
        return authCall {
            val result = firebaseAuth.signInWithEmailAndPassword(credentials.email, credentials.password).await()
            val user = result.user ?: throw IllegalStateException("Firebase user is null")
            mapFirebaseUser(user)
        }
    }

    suspend fun signOut(): AppResult<Unit> = safeApiCall {
        firebaseAuth.signOut()
    }

    fun getCurrentUser(): AuthUser? {
        return firebaseAuth.currentUser?.let { mapFirebaseUser(it) }
    }

    suspend fun reAuthenticate(credentials: AuthCredentials): AppResult<Unit> {
        return authCall {
            val user = firebaseAuth.currentUser ?: throw IllegalStateException("No active user")
            val credential = EmailAuthProvider.getCredential(credentials.email, credentials.password)
            user.reauthenticate(credential).await()
            Unit
        }
    }

    suspend fun changePassword(newPassword: String): AppResult<Unit> {
        return authCall {
            val user = firebaseAuth.currentUser ?: throw IllegalStateException("No active user")
            user.updatePassword(newPassword).await()
            Unit
        }
    }

    suspend fun sendPasswordResetEmail(email: String): AppResult<Unit> {
        return authCall {
            firebaseAuth.sendPasswordResetEmail(email).await()
            Unit
        }
    }

    private fun mapFirebaseUser(user: com.google.firebase.auth.FirebaseUser): AuthUser {
        return AuthUser(
            uid = user.uid,
            email = user.email ?: "",
            displayName = user.displayName ?: "MGTik Admin",
            photoUrl = user.photoUrl?.toString()
        )
    }
}

package ps.mgtik.hotspot.data.userman

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.core.result.ApiError

/** المهمة 4.4: إنشاء مقطوع الرد لا ينتج مستخدماً مكرراً؛ نتحقق من الحالة قبل إعادة التنفيذ. */
class UserOperationVerifierTest {

    private fun user(
        name: String = "u1", disabled: Boolean = false, pkg: String? = "P1", comment: String? = "MGTIK-B1", password: String? = "pw"
    ) = UmUser(id = "*1", name = name, disabled = disabled, packageName = pkg, comment = comment, password = password)

    @Test fun ambiguousErrorsAreOnlyConnectivityOnes() {
        assertTrue(UserOperationVerifier.isAmbiguousSendError(ApiError.CODE_NETWORK_UNAVAILABLE))
        assertTrue(UserOperationVerifier.isAmbiguousSendError(ApiError.CODE_TIMEOUT))
        // رُفضت بالبوابة قبل الإرسال: إعادتها آمنة دون تحقق
        assertFalse(UserOperationVerifier.isAmbiguousSendError(ApiError.CODE_ROUTER_OFFLINE))
        assertFalse(UserOperationVerifier.isAmbiguousSendError(ApiError.CODE_ROUTER_AUTH_FAILED))
        assertFalse(UserOperationVerifier.isAmbiguousSendError(null))
    }

    @Test fun createdUserWithSameAttributesIsOurs() =
        assertTrue(UserOperationVerifier.isSameUser(user(), "MGTIK-B1", "pw", "P1", false, requirePassword = true))

    @Test fun missingUserIsNotOurs() =
        assertFalse(UserOperationVerifier.isSameUser(null, "MGTIK-B1", "pw", "P1", false, requirePassword = false))

    @Test fun foreignCommentIsNotOurs() =
        assertFalse(UserOperationVerifier.isSameUser(user(comment = "OTHER"), "MGTIK-B1", "pw", "P1", false, requirePassword = false))

    @Test fun commentComparisonIgnoresSurroundingSpaces() =
        assertTrue(UserOperationVerifier.isSameUser(user(comment = " MGTIK-B1 "), "MGTIK-B1", "pw", "P1", false, requirePassword = false))

    @Test fun differentPackageOrStateIsNotOurs() {
        assertFalse(UserOperationVerifier.isSameUser(user(pkg = "P2"), "MGTIK-B1", "pw", "P1", false, requirePassword = false))
        assertFalse(UserOperationVerifier.isSameUser(user(disabled = true), "MGTIK-B1", "pw", "P1", false, requirePassword = false))
    }

    @Test fun nullRequestedPackageAcceptsAnyPackage() =
        assertTrue(UserOperationVerifier.isSameUser(user(pkg = null), "MGTIK-B1", "pw", null, false, requirePassword = false))

    @Test fun nameCollisionInSameBatchWithDifferentPasswordIsNotOurs() =
        assertFalse(UserOperationVerifier.isSameUser(user(password = "other"), "MGTIK-B1", "pw", "P1", false, requirePassword = true))

    @Test fun unreadablePasswordOnlyAcceptedAfterConnectivityFailurePath() {
        assertFalse(UserOperationVerifier.isSameUser(user(password = null), "MGTIK-B1", "pw", "P1", false, requirePassword = true))
        assertTrue(UserOperationVerifier.isSameUser(user(password = null), "MGTIK-B1", "pw", "P1", false, requirePassword = false))
    }
}

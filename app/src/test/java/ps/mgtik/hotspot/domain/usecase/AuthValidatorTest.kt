package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthValidatorTest {

    @Test
    fun `valid emails are accepted including long TLDs`() {
        assertTrue(AuthValidator.isEmailValid("admin@mgtik.ps"))
        assertTrue(AuthValidator.isEmailValid("first.last+tag@sub.example.technology"))
    }

    @Test
    fun `invalid emails are rejected`() {
        assertFalse(AuthValidator.isEmailValid(""))
        assertFalse(AuthValidator.isEmailValid("   "))
        assertFalse(AuthValidator.isEmailValid("no-at-sign.com"))
        assertFalse(AuthValidator.isEmailValid("a@b"))
        assertFalse(AuthValidator.isEmailValid("a b@c.com"))
    }

    @Test
    fun `password needs at least six characters`() {
        assertFalse(AuthValidator.isPasswordValid("12345"))
        assertTrue(AuthValidator.isPasswordValid("123456"))
    }
}

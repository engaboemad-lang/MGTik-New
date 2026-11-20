package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerInputValidatorTest {

    @Test
    fun `same number written differently normalizes equal`() {
        val variants = listOf("0599123456", "0599-123-456", "059 912 3456", "+970599123456", "00970599123456", "+972 59 912 3456")
        val normalized = variants.map(OwnerInputValidator::normalizePhone).toSet()
        assertEquals(setOf("0599123456"), normalized)
    }

    @Test
    fun `different numbers stay different`() {
        assertFalse(OwnerInputValidator.normalizePhone("0599123456") == OwnerInputValidator.normalizePhone("0599123457"))
    }

    @Test
    fun `duplicate detection ignores formatting and excludes self`() {
        val existing = listOf("A" to "0599-123-456", "B" to "0568000111")
        assertTrue(OwnerInputValidator.isPhoneTaken("+970599123456", existing))
        assertFalse(OwnerInputValidator.isPhoneTaken("0599123999", existing))
        assertFalse(OwnerInputValidator.isPhoneTaken("0599123456", existing, excludeOwnerId = "A"))
    }

    @Test
    fun `name and phone length rules`() {
        assertTrue(OwnerInputValidator.isNameValid("  علي "))
        assertFalse(OwnerInputValidator.isNameValid("ab"))
        assertTrue(OwnerInputValidator.isPhoneValid("0599123"))
        assertFalse(OwnerInputValidator.isPhoneValid("12-34"))
    }
}

package ps.mgtik.hotspot.domain.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class VoucherCodeGeneratorTest {

    @Test
    fun codes_are_unique_and_avoid_taken_names() {
        val g = VoucherCodeGenerator.generate(2000, CodeFormat.NUMERIC_ONLY, "A-", 5, setOf("a-00001"), Random(1))
        assertEquals(2000, g.map { it.username.lowercase() }.toSet().size)
        assertTrue(g.none { it.username.equals("a-00001", true) })
        assertTrue(g.all { it.username.startsWith("A-") && it.username.length == 7 })
    }

    @Test
    fun formats_follow_their_alphabets() {
        val up = VoucherCodeGenerator.generate(300, CodeFormat.UPPERCASE_ONLY, "", 5, emptySet(), Random(2))
        assertTrue(up.all { u -> u.username.all { it in 'A'..'Z' && it != 'O' && it != 'I' } })
        val same = VoucherCodeGenerator.generate(50, CodeFormat.USER_PASS_SAME, "", 6, emptySet(), Random(3))
        assertTrue(same.all { it.username == it.password })
        val sep = VoucherCodeGenerator.generate(50, CodeFormat.ALPHANUMERIC, "", 6, emptySet(), Random(3))
        assertTrue(sep.all { it.password.length == 6 && it.password != it.username })
    }

    @Test(expected = IllegalStateException::class)
    fun exhausted_keyspace_throws() {
        VoucherCodeGenerator.generate(20, CodeFormat.NUMERIC_ONLY, "", 1, emptySet(), Random(1))
    }

    @Test
    fun rules_validate_every_field() {
        fun v(count: Int = 100, len: Int = 6, prefix: String = "X", owner: String? = "O", fmt: String = "numeric_only", profile: String = "P") =
            GenerationRules.validate(profile, owner, count, fmt, prefix, len)
        assertTrue(v().isEmpty())
        assertEquals(GenerationRules.ERR_OWNER_REQUIRED, v(owner = null)[GenerationRules.Field.OWNER])
        assertTrue(v(owner = "  ").isNotEmpty())
        assertTrue(v(count = 0).containsKey("count") && v(count = -3).containsKey("count"))
        assertEquals(GenerationRules.ERR_COUNT_LIMIT_EXCEEDED, v(count = 5001)["count"])
        assertTrue(v(count = 5000).isEmpty())
        assertTrue(v(len = 3).containsKey("codeLength") && v(len = 13).containsKey("codeLength"))
        assertTrue(v(prefix = "bad prefix").containsKey("prefix") && v(prefix = "عربي").containsKey("prefix") && v(prefix = "123456789").containsKey("prefix"))
        assertEquals(GenerationRules.ERR_FORMAT_INVALID, v(fmt = "weird")["format"])
        assertTrue(v(fmt = "USER_PASS_SAME").isEmpty())
        assertTrue(v(profile = "").containsKey("profileId"))
    }
}

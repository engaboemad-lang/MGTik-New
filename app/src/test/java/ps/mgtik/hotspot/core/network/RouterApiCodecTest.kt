package ps.mgtik.hotspot.core.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

class RouterApiCodecTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun lengthEncodingBoundariesMatchRouterOsSpec() {
        assertArrayEquals(bytes(0x00), RouterApiCodec.encodeLength(0))
        assertArrayEquals(bytes(0x7F), RouterApiCodec.encodeLength(0x7F))
        assertArrayEquals(bytes(0x80, 0x80), RouterApiCodec.encodeLength(0x80))
        assertArrayEquals(bytes(0xBF, 0xFF), RouterApiCodec.encodeLength(0x3FFF))
        assertArrayEquals(bytes(0xC0, 0x40, 0x00), RouterApiCodec.encodeLength(0x4000))
        assertArrayEquals(bytes(0xDF, 0xFF, 0xFF), RouterApiCodec.encodeLength(0x1FFFFF))
        assertArrayEquals(bytes(0xE0, 0x20, 0x00, 0x00), RouterApiCodec.encodeLength(0x200000))
        assertArrayEquals(bytes(0xEF, 0xFF, 0xFF, 0xFF), RouterApiCodec.encodeLength(0x0FFFFFFF))
        assertArrayEquals(bytes(0xF0, 0x10, 0x00, 0x00, 0x00), RouterApiCodec.encodeLength(0x10000000))
    }

    @Test
    fun lengthRoundTripAcrossAllEncodingSizes() {
        for (len in listOf(0, 1, 127, 128, 300, 0x3FFF, 0x4000, 70_000, 0x1FFFFF, 0x200000, 5_000_000, 0x0FFFFFFF, 0x10000000)) {
            val decoded = RouterApiCodec.readLength(DataInputStream(ByteArrayInputStream(RouterApiCodec.encodeLength(len))))
            assertEquals(len, decoded)
        }
    }

    @Test
    fun sentenceRoundTripKeepsUtf8Words() {
        val words = listOf("/user-manager/user/add", "=name=كرت-١", "=comment=a=b=c", "?name=x")
        val out = ByteArrayOutputStream()
        RouterApiCodec.writeSentence(out, words)
        val back = RouterApiCodec.readSentence(DataInputStream(ByteArrayInputStream(out.toByteArray())))
        assertEquals(words, back)
    }

    @Test
    fun attributesSplitOnFirstEqualsOnlyAndIgnoreNonAttributeWords() {
        val attrs = RouterApiCodec.attributes(listOf("!re", "=.id=*1A", "=name=abc", "=comment=x=y", "=empty=", ".tag=7"))
        assertEquals("*1A", attrs[".id"])
        assertEquals("abc", attrs["name"])
        assertEquals("x=y", attrs["comment"])
        assertEquals("", attrs["empty"])
        assertEquals(4, attrs.size)
    }

    @Test
    fun legacyLoginResponseMatchesKnownVector() {
        val response = RouterApiCodec.legacyLoginResponse("secret", "00112233445566778899aabbccddeeff")
        assertEquals("00" + EXPECTED_MD5, response)
    }

    @Test
    fun legacyLoginResponseMatchesOfficialMikroTikExample() {
        // المثال المنشور في توثيق MikroTik API (كلمة مرور فارغة)
        assertEquals(
            "00e134102a9d330dd7b1849fedfea3cb57",
            RouterApiCodec.legacyLoginResponse("", "93b438ec9b80057c06dd9fe67d56aa9a")
        )
    }

    private companion object {
        // md5(0x00 + "secret" + challenge) محسوب خارجياً (Python hashlib)
        const val EXPECTED_MD5 = "c17511d224a5e93170632807d36388aa"
    }
}

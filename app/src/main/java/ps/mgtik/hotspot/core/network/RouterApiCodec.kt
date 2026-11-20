package ps.mgtik.hotspot.core.network

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

/**
 * RouterApiCodec - ترميز بروتوكول API الثنائي لـ RouterOS (المنفذ 8728/8729) كدوال نقية قابلة للاختبار.
 *
 * الجملة = كلمات متتالية تنتهي بكلمة فارغة. طول كل كلمة بترميز متغيّر (1 إلى 5 بايت).
 */
internal object RouterApiCodec {

    fun encodeLength(len: Int): ByteArray {
        require(len >= 0) { "negative word length" }
        return when {
            len < 0x80 -> byteArrayOf(len.toByte())
            len < 0x4000 -> {
                val v = len or 0x8000
                byteArrayOf((v shr 8).toByte(), v.toByte())
            }
            len < 0x200000 -> {
                val v = len or 0xC00000
                byteArrayOf((v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
            }
            len < 0x10000000 -> {
                val v = len or 0xE0000000.toInt()
                byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
            }
            else -> byteArrayOf(
                0xF0.toByte(), (len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()
            )
        }
    }

    fun readLength(input: DataInputStream): Int {
        val b0 = input.readUnsignedByte()
        return when {
            b0 and 0x80 == 0 -> b0
            b0 and 0xC0 == 0x80 -> {
                val b1 = input.readUnsignedByte()
                ((b0 and 0x3F) shl 8) or b1
            }
            b0 and 0xE0 == 0xC0 -> {
                val b1 = input.readUnsignedByte()
                val b2 = input.readUnsignedByte()
                ((b0 and 0x1F) shl 16) or (b1 shl 8) or b2
            }
            b0 and 0xF0 == 0xE0 -> {
                val b1 = input.readUnsignedByte()
                val b2 = input.readUnsignedByte()
                val b3 = input.readUnsignedByte()
                ((b0 and 0x0F) shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            }
            else -> {
                val b1 = input.readUnsignedByte()
                val b2 = input.readUnsignedByte()
                val b3 = input.readUnsignedByte()
                val b4 = input.readUnsignedByte()
                (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
            }
        }
    }

    /** يكتب جملة كاملة (كلماتها + الكلمة الفارغة الختامية) ثم يفرغ المخزن. */
    fun writeSentence(out: OutputStream, words: List<String>) {
        val buffer = ByteArrayOutputStream()
        for (word in words) {
            val bytes = word.toByteArray(Charsets.UTF_8)
            buffer.write(encodeLength(bytes.size))
            buffer.write(bytes)
        }
        buffer.write(0)
        out.write(buffer.toByteArray())
        out.flush()
    }

    /** يقرأ جملة واحدة حتى الكلمة الفارغة. حد الكلمة 16MB حماية من ردّ تالف. */
    fun readSentence(input: DataInputStream): List<String> {
        val words = ArrayList<String>()
        while (true) {
            val len = readLength(input)
            if (len == 0) return words
            if (len < 0 || len > MAX_WORD_BYTES) throw IOException("Invalid router API word length: $len")
            val bytes = ByteArray(len)
            input.readFully(bytes)
            words += String(bytes, Charsets.UTF_8)
        }
    }

    /** "=key=value" ← (key, value)؛ القيمة قد تحوي "=". الكلمات الأخرى (!re، .tag) ليست سمات. */
    fun attributes(sentence: List<String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (word in sentence) {
            if (!word.startsWith("=")) continue
            val sep = word.indexOf('=', 1)
            if (sep < 0) result[word.substring(1)] = "" else result[word.substring(1, sep)] = word.substring(sep + 1)
        }
        return result
    }

    /** رد التحدّي في تسجيل الدخول القديم (RouterOS قبل 6.43): "00" + md5(0x00 + password + challenge). */
    fun legacyLoginResponse(password: String, challengeHex: String): String {
        require(challengeHex.length % 2 == 0) { "invalid challenge" }
        val challenge = ByteArray(challengeHex.length / 2) { i ->
            challengeHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val md = MessageDigest.getInstance("MD5")
        md.update(0)
        md.update(password.toByteArray(Charsets.UTF_8))
        md.update(challenge)
        return "00" + md.digest().joinToString("") { "%02x".format(it) }
    }

    private const val MAX_WORD_BYTES = 16 * 1024 * 1024
}

package ps.mgtik.hotspot.core.util

import java.security.MessageDigest

object HashUtils {
    fun calculateSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun calculateSha256(text: String): String {
        return calculateSha256(text.toByteArray(Charsets.UTF_8))
    }
}

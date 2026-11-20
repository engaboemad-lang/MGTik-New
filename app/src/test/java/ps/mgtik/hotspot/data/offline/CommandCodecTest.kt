package ps.mgtik.hotspot.data.offline

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandCodecTest {

    @Test
    fun `field map keeps numeric types through a round trip`() {
        val original = mapOf<String, Any?>("price_minor" to 1999L, "shared_users" to 2, "ratio" to 1.5, "active" to true, "note" to "x", "gone" to null)
        val restored = CommandCodec.jsonToFields(CommandCodec.fieldsToJson(original))

        assertEquals(1999L, restored["price_minor"])
        assertEquals(2L, restored["shared_users"]) // الأعداد الصحيحة تعود Long (يقبلها Firestore ولا تتحول إلى Double)
        assertEquals(1.5, restored["ratio"])
        assertEquals(true, restored["active"])
        assertEquals("x", restored["note"])
        assertTrue(restored.containsKey("gone"))
        assertNull(restored["gone"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported field values are rejected instead of silently stored`() {
        CommandCodec.fieldsToJson(mapOf("bad" to listOf(1, 2)))
    }

    @Test
    fun `payloads written by the old repositories still decode`() {
        // الصيغة التي كانت تكتبها OwnerRepositoryImpl/UserRepositoryImpl عبر Gson
        val balance = CommandCodec.decode<OwnerBalancePayload>("""{"ownerId":"o1","amountMinor":1000}""")
        assertEquals(OwnerBalancePayload("o1", 1000L), balance)

        val advance = CommandCodec.decode<OwnerAdvancePayload>("""{"ownerId":"o1","amountMinor":500}""")
        assertNull(advance.notes)

        val archive = CommandCodec.decode<UserArchivePayload>("""{"recordUuid":"u1","reason":"expired"}""")
        assertEquals("u1", archive.recordUuid)
    }

    @Test
    fun `payload encode then decode is lossless`() {
        val payload = ProfileUpdatePayload("PRF-1", CommandCodec.fieldsToJson(mapOf("price_minor" to 250L)))
        val decoded = CommandCodec.decode<ProfileUpdatePayload>(CommandCodec.encode(payload))
        assertEquals(payload, decoded)
        assertTrue(decoded.fields is JsonObject)
    }

    @Test
    fun `producer payloads round trip through the same classes the dispatcher decodes`() {
        val adv = CommandCodec.decode<OwnerAdvancePayload>(CommandCodec.encode(OwnerAdvancePayload("o1", 2500L, null)))
        assertEquals(OwnerAdvancePayload("o1", 2500L, null), adv)
        assertEquals(OwnerBalancePayload("o1", -300L), CommandCodec.decode<OwnerBalancePayload>(CommandCodec.encode(OwnerBalancePayload("o1", -300L))))
        assertEquals(UserArchivePayload("u1", "expired"), CommandCodec.decode<UserArchivePayload>(CommandCodec.encode(UserArchivePayload("u1", "expired"))))
    }

    @Test
    fun `user edit request survives the queue round trip `() {
        val original = ps.mgtik.hotspot.domain.model.UserEditRequest(
            recordUuid = "u1", expectedUpdatedAt = "2026-11-01T10:00:00.000Z", ownerId = "o2"
        )
        val restored = CommandCodec.decode<ps.mgtik.hotspot.domain.model.UserEditRequest>(CommandCodec.encode(original))
        assertEquals(original, restored)
    }

    @Test
    fun `an old queued edit with per-user limits still decodes and the limits are ignored`() {
        val old = """{"recordUuid":"u1","expectedUpdatedAt":"x","ownerId":"o2","limitUptimeSeconds":3600,"limitTransferBytes":null}"""
        val restored = CommandCodec.decode<ps.mgtik.hotspot.domain.model.UserEditRequest>(old)
        assertEquals("o2", restored.ownerId)
    }
}

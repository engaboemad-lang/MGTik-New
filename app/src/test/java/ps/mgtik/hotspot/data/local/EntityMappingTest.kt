package ps.mgtik.hotspot.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.data.generation.RunMapping
import ps.mgtik.hotspot.data.local.entity.InvoiceEntity
import ps.mgtik.hotspot.data.local.entity.LocalUserEntity
import ps.mgtik.hotspot.data.local.entity.OfflineCommandEntity
import ps.mgtik.hotspot.domain.model.Invoice
import ps.mgtik.hotspot.domain.model.InvoiceStatus
import ps.mgtik.hotspot.domain.model.LocalUserRecord
import ps.mgtik.hotspot.domain.model.OfflineCommand
import ps.mgtik.hotspot.domain.model.OfflineCommandType
import ps.mgtik.hotspot.domain.model.UserOrigin
import ps.mgtik.hotspot.domain.model.UserRecordStatus
import ps.mgtik.hotspot.domain.model.UserSyncStatus

/**
 * E-04: قيم الـ enum تُخزَّن باسمها (UPPERCASE) في كل الجداول، والقراءة تتسامح مع الصفوف القديمة بأحرف صغيرة.
 * E-03: تواريخ الساغا بتنسيق ISO القابل للترتيب.
 */
class EntityMappingTest {

    @Test
    fun invoice_status_is_stored_as_enum_name_and_round_trips() {
        InvoiceStatus.values().forEach { st ->
            val entity = InvoiceEntity.fromDomain(Invoice(id = "i-${st.name}", status = st))
            assertEquals(st.name, entity.status)
            assertEquals(st, entity.toDomainOrNull()?.status)
        }
    }

    @Test
    fun invoice_legacy_lowercase_status_still_reads() {
        val legacy = InvoiceEntity.fromDomain(Invoice(id = "i1", status = InvoiceStatus.PAID)).copy(status = "paid")
        assertEquals(InvoiceStatus.PAID, legacy.toDomainOrNull()?.status)
        val legacy2 = InvoiceEntity.fromDomain(Invoice(id = "i2", status = InvoiceStatus.PARTIALLY_PAID)).copy(status = "partially_paid")
        assertEquals(InvoiceStatus.PARTIALLY_PAID, legacy2.toDomainOrNull()?.status)
    }

    @Test
    fun invoice_with_unknown_status_is_rejected_not_defaulted() {
        val bad = InvoiceEntity.fromDomain(Invoice(id = "i3")).copy(status = "NOT_A_STATUS")
        assertEquals(null, bad.toDomainOrNull())
    }

    @Test
    fun local_user_enums_are_stored_as_names_and_round_trip() {
        val rec = LocalUserRecord(
            recordUuid = "u1", routerUsername = "alice", ownerId = "o1",
            status = UserRecordStatus.DISABLED, origin = UserOrigin.ADOPTED, syncStatus = UserSyncStatus.PENDING
        )
        val e = LocalUserEntity.fromDomain(rec)
        assertEquals("DISABLED", e.status)
        assertEquals("ADOPTED", e.origin)
        assertEquals("PENDING", e.syncStatus)
        assertEquals(rec, e.toDomainOrNull())
    }

    @Test
    fun offline_command_round_trips() {
        val cmd = OfflineCommand(type = OfflineCommandType.values().first(), payloadJson = "{}", idempotencyKey = "k1", attemptCount = 2, lastError = "x")
        assertEquals(cmd, OfflineCommandEntity.fromDomain(cmd).toDomainOrNull())
    }

    @Test
    fun offline_command_with_unknown_type_is_skipped() {
        val bad = OfflineCommandEntity.fromDomain(
            OfflineCommand(type = OfflineCommandType.values().first(), payloadJson = "{}", idempotencyKey = "k2")
        ).copy(type = "REMOVED_TYPE")
        assertEquals(null, bad.toDomainOrNull())
    }

    @Test
    fun run_mapping_time_is_sortable_iso() {
        assertEquals(null, RunMapping.formatTime(null))
        val a = RunMapping.formatTime(1_700_000_000_000L)
        val b = RunMapping.formatTime(1_700_000_000_001L)
        assertNotNull(a); assertNotNull(b)
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""").matches(a!!))
        assertEquals("2023-11-14T22:13:20.000Z", a)
        assertTrue(a < b!!)
    }
}

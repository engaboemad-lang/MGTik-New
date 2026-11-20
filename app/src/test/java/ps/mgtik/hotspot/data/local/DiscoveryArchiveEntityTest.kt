package ps.mgtik.hotspot.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ps.mgtik.hotspot.data.local.entity.ArchivedUserEntity
import ps.mgtik.hotspot.data.local.entity.DiscoveryEntity
import ps.mgtik.hotspot.domain.model.ArchiveType
import ps.mgtik.hotspot.domain.model.ArchivedUserSnapshot
import ps.mgtik.hotspot.domain.model.DiscoveryRecord
import ps.mgtik.hotspot.domain.model.DiscoveryStatus
import ps.mgtik.hotspot.domain.model.DiscoveryType

class DiscoveryArchiveEntityTest {

    @Test
    fun `discovery record round trips`() {
        val r = DiscoveryRecord("missing_u1", DiscoveryType.MISSING, "u1", "card1", DiscoveryStatus.OPEN, "a", "b", null)
        assertEquals(r, DiscoveryEntity.fromDomain(r).toDomainOrNull())
    }

    @Test
    fun `unknown discovery type or status is rejected, not defaulted`() {
        val ok = DiscoveryEntity.fromDomain(DiscoveryRecord("d", DiscoveryType.NULL, "k", "n"))
        assertNull(ok.copy(type = "weird").toDomainOrNull())
        assertNull(ok.copy(status = "weird").toDomainOrNull())
    }

    @Test
    fun `archived user keeps type and snapshot`() {
        val s = ArchivedUserSnapshot(
            "u1", "u1", "card1", mapOf("ownerId" to "o1", "routerState" to "absent"),
            "left", "admin-1", "2026-11-03T10:00:00.000Z", ArchiveType.MISSING
        )
        assertEquals(s, ArchivedUserEntity.fromDomain(s).toDomain())
    }

    @Test
    fun `unknown stored archive type falls back to disabled (the safe, verified state)`() {
        val e = ArchivedUserEntity.fromDomain(ArchivedUserSnapshot("u1", "u1", "c", emptyMap(), "r", null, "t", ArchiveType.MISSING))
        assertEquals(ArchiveType.DISABLED, e.copy(archiveType = "weird").toDomain().archiveType)
    }
}

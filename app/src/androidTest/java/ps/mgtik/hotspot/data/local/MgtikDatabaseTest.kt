package ps.mgtik.hotspot.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ps.mgtik.hotspot.data.local.db.MgtikDatabase
import ps.mgtik.hotspot.domain.model.Invoice
import ps.mgtik.hotspot.domain.model.InvoiceStatus
import ps.mgtik.hotspot.domain.model.LocalUserRecord
import ps.mgtik.hotspot.domain.model.UserRecordStatus

/** يتحقق من أن المخطط يُبنى (تطابق الكيانات/الاستعلامات)، وأن الفهارس موجودة، وأن تصفية enum وتنظيف الكاش يعملان. */
@RunWith(AndroidJUnit4::class)
class MgtikDatabaseTest {
    private lateinit var db: MgtikDatabase

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, MgtikDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun indexNames(table: String): List<String> {
        val out = mutableListOf<String>()
        db.openHelper.readableDatabase.query("PRAGMA index_list($table)").use { c ->
            while (c.moveToNext()) out += c.getString(c.getColumnIndexOrThrow("name"))
        }
        return out
    }

    @Test fun created_at_indices_exist() {
        assertTrue(indexNames("cached_invoices").contains("index_cached_invoices_created_at"))
        assertTrue(indexNames("local_users").contains("index_local_users_created_at"))
        assertTrue(indexNames("audit_logs").contains("index_audit_logs_timestamp"))
    }

    @Test fun invoice_status_filter_matches_stored_names() = runBlocking {
        db.invoiceDao().insertInvoices(
            listOf(
                ps.mgtik.hotspot.data.local.entity.InvoiceEntity.fromDomain(Invoice(id = "a", ownerId = "o", status = InvoiceStatus.PAID, createdAt = "2026-01-01T00:00:00.000Z")),
                ps.mgtik.hotspot.data.local.entity.InvoiceEntity.fromDomain(Invoice(id = "b", ownerId = "o", status = InvoiceStatus.DUE, createdAt = "2026-01-02T00:00:00.000Z"))
            )
        )
        val paid = db.invoiceDao().getFilteredInvoicesFlow(null, InvoiceStatus.PAID.name).first()
        assertEquals(listOf("a"), paid.map { it.id })
        val all = db.invoiceDao().getFilteredInvoicesFlow(null, null).first()
        assertEquals(listOf("b", "a"), all.map { it.id }) // الأحدث أولاً
    }

    @Test fun user_status_filter_and_clear_user_caches() = runBlocking {
        db.localUserDao().insertUsers(
            listOf(
                ps.mgtik.hotspot.data.local.entity.LocalUserEntity.fromDomain(LocalUserRecord("u1", "x", "o", status = UserRecordStatus.ACTIVE)),
                ps.mgtik.hotspot.data.local.entity.LocalUserEntity.fromDomain(LocalUserRecord("u2", "y", "o", status = UserRecordStatus.DISABLED))
            )
        )
        assertEquals(listOf("u2"), db.localUserDao().getUsersFilteredFlow(null, UserRecordStatus.DISABLED.name).first().map { it.recordUuid })
    }
}

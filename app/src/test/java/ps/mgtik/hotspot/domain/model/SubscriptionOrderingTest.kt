package ps.mgtik.hotspot.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionOrderingTest {

    private fun sub(id: String, createdAt: String) = Subscription(id = id, createdAt = createdAt)

    @Test fun newestCreatedFirst() {
        val list = listOf(
            sub("a", "2026-10-01T08:00:00.000Z"),
            sub("c", "2026-11-12T17:40:00.000Z"),
            sub("b", "2026-11-02T06:18:00.000Z")
        )
        assertEquals(listOf("c", "b", "a"), list.newestFirst().map { it.id })
    }

    @Test fun missingCreationDateGoesLast() {
        val list = listOf(sub("old", ""), sub("new", "2026-11-12T17:40:00.000Z"), sub("mid", "2026-11-01T00:00:00.000Z"))
        assertEquals(listOf("new", "mid", "old"), list.newestFirst().map { it.id })
    }

    @Test fun equalDatesAreOrderedStablyById() {
        val list = listOf(sub("b", "2026-11-12T17:40:00.000Z"), sub("a", "2026-11-12T17:40:00.000Z"))
        assertEquals(listOf("a", "b"), list.newestFirst().map { it.id })
        assertEquals(listOf("a", "b"), list.reversed().newestFirst().map { it.id })
    }

    @Test fun effectivePriceUsesFinalPriceThenPriceMinusDiscount() {
        assertEquals(900L, Subscription(priceMinor = 1_000, discountMinor = 100, finalPriceMinor = 900).effectivePriceMinor)
        assertEquals(900L, Subscription(priceMinor = 1_000, discountMinor = 100, finalPriceMinor = 0).effectivePriceMinor)
        assertEquals(0L, Subscription(priceMinor = 100, discountMinor = 500, finalPriceMinor = 0).effectivePriceMinor)
    }
}

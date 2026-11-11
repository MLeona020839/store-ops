package dev.storeops.events

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PromotionEventTest {
    private fun event(
        productIds: List<String> = listOf("product-42"),
        storeIds: List<String> = listOf("store-1001"),
        validFrom: LocalDate = LocalDate.of(2026, 11, 16),
        validUntil: LocalDate = LocalDate.of(2026, 11, 22),
    ) = PromotionEvent(
        eventId = UUID.randomUUID(),
        type = PromotionEventType.SCHEDULED,
        promotionId = UUID.randomUUID(),
        title = "Alle kipfilet",
        label = "25% korting",
        productIds = productIds,
        storeIds = storeIds,
        validFrom = validFrom,
        validUntil = validUntil,
        occurredAt = Instant.parse("2026-11-11T09:00:00Z"),
    )

    @Test
    fun `accepts a one-day promotion`() {
        event(validFrom = LocalDate.of(2026, 11, 16), validUntil = LocalDate.of(2026, 11, 16))
    }

    @Test
    fun `rejects a promotion that ends before it starts`() {
        assertFailsWith<IllegalArgumentException> {
            event(validFrom = LocalDate.of(2026, 11, 22), validUntil = LocalDate.of(2026, 11, 16))
        }
    }

    @Test
    fun `accepts a promotion covering several products`() {
        event(productIds = listOf("product-42", "product-43", "product-44"))
    }

    @Test
    fun `rejects a promotion without products`() {
        assertFailsWith<IllegalArgumentException> { event(productIds = emptyList()) }
    }

    @Test
    fun `rejects a promotion without stores`() {
        assertFailsWith<IllegalArgumentException> { event(storeIds = emptyList()) }
    }
}

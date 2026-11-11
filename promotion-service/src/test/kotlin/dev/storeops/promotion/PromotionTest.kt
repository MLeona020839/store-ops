package dev.storeops.promotion

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The aggregate's own rules. No database needed, so these always run. */
class PromotionTest {
    @Test
    fun `accepts every kind when it has the fields it needs`() {
        promotion(kind = PromotionKind.PERCENT_OFF, percent = BigDecimal("25"))
        promotion(kind = PromotionKind.PROMO_PRICE, percent = null, priceCents = 299)
        promotion(kind = PromotionKind.BUY_X_PAY_Y, percent = null, buy = 2, pay = 1)
        promotion(kind = PromotionKind.X_FOR_PRICE, percent = null, buy = 2, priceCents = 500)
        promotion(kind = PromotionKind.SECOND_HALF_PRICE, percent = null)
        promotion(kind = PromotionKind.OTHER, percent = null)
    }

    @Test
    fun `rejects a promotion that ends before it starts`() {
        assertFailsWith<IllegalArgumentException> {
            promotion(validFrom = LocalDate.of(2026, 11, 22), validUntil = LocalDate.of(2026, 11, 16))
        }
    }

    @Test
    fun `rejects a promotion without stores or products`() {
        assertFailsWith<IllegalArgumentException> { promotion(storeIds = emptySet()) }
        assertFailsWith<IllegalArgumentException> { promotion(productIds = emptySet()) }
    }

    @Test
    fun `rejects percent_off without a sensible percent`() {
        assertFailsWith<IllegalArgumentException> { promotion(percent = null) }
        assertFailsWith<IllegalArgumentException> { promotion(percent = BigDecimal.ZERO) }
        assertFailsWith<IllegalArgumentException> { promotion(percent = BigDecimal("100")) }
    }

    @Test
    fun `rejects buy_x_pay_y where you pay for every pack you get`() {
        assertFailsWith<IllegalArgumentException> {
            promotion(kind = PromotionKind.BUY_X_PAY_Y, percent = null, buy = 2, pay = 2)
        }
    }

    @Test
    fun `rejects a blank title`() {
        assertFailsWith<IllegalArgumentException> { promotion(title = " ") }
    }

    @Test
    fun `cancel keeps everything but the status`() {
        val scheduled = promotion()
        val cancelled = scheduled.cancel()

        assertEquals(PromotionStatus.CANCELLED, cancelled.status)
        assertEquals(scheduled.copy(status = PromotionStatus.CANCELLED), cancelled)
    }
}

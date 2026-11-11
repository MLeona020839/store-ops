package dev.storeops.promotion

import dev.storeops.events.PromotionEvent
import dev.storeops.events.PromotionEventType
import dev.storeops.events.Topics
import dev.storeops.promotion.outbox.Outbox
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PromotionServiceTest {
    private val repository = mock<PromotionRepository>()
    private val outbox = mock<Outbox>()

    // 23:30 UTC on Sunday 15 November is already Monday 16 November in Amsterdam.
    private val now = Instant.parse("2026-11-15T23:30:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("Europe/Amsterdam"))
    private val service = PromotionService(repository, outbox, clock)

    init {
        doAnswer { it.arguments[0] }.whenever(repository).save(any<Promotion>())
    }

    @Test
    fun `creating a promotion records a SCHEDULED event keyed by the promotion id`() {
        val created = service.create(promotion(storeIds = setOf("store-1002", "store-1001")))

        val event = recordedEvent(PromotionEventType.SCHEDULED, created.id)
        assertEquals(created.id, event.promotionId)
        assertEquals(listOf("store-1001", "store-1002"), event.storeIds)
        assertEquals(created.label, event.label)
        assertEquals(now, event.occurredAt)
    }

    @Test
    fun `cancelling records a CANCELLED event`() {
        val scheduled = promotion().copy(version = 0)
        whenever(repository.findById(scheduled.id)).thenReturn(Optional.of(scheduled))

        val result = service.cancel(scheduled.id)

        assertEquals(PromotionStatus.CANCELLED, result.status)
        verify(repository).save(scheduled.cancel())
        recordedEvent(PromotionEventType.CANCELLED, scheduled.id)
    }

    @Test
    fun `cancelling twice changes and publishes nothing the second time`() {
        val cancelled = promotion().copy(version = 1).cancel()
        whenever(repository.findById(cancelled.id)).thenReturn(Optional.of(cancelled))

        assertSame(cancelled, service.cancel(cancelled.id))
        verify(repository, never()).save(any<Promotion>())
        verify(outbox, never()).add(any(), any(), any(), any(), any())
    }

    @Test
    fun `lists from today in the stores' time zone when no date is given`() {
        service.listCurrentAndUpcoming()

        verify(repository).findAllByValidUntilGreaterThanEqualOrderByValidFrom(LocalDate.of(2026, 11, 16))
    }

    @Test
    fun `lists from the given date`() {
        service.listCurrentAndUpcoming(LocalDate.of(2026, 12, 1))

        verify(repository).findAllByValidUntilGreaterThanEqualOrderByValidFrom(LocalDate.of(2026, 12, 1))
    }

    @Test
    fun `get throws not found for an unknown id`() {
        val id = UUID.randomUUID()
        whenever(repository.findById(id)).thenReturn(Optional.empty())

        assertFailsWith<PromotionNotFoundException> { service.get(id) }
    }

    /** Verifies exactly one outbox write of [type] for [promotionId] and returns its event. */
    private fun recordedEvent(
        type: PromotionEventType,
        promotionId: UUID,
    ): PromotionEvent {
        val eventId = argumentCaptor<UUID>()
        val event = argumentCaptor<Any>()
        verify(outbox).add(eventId.capture(), eq(type.name), eq(Topics.PROMOTION_EVENTS), eq(promotionId.toString()), event.capture())
        val recorded = event.firstValue as PromotionEvent
        assertEquals(type, recorded.type)
        assertEquals(eventId.firstValue, recorded.eventId)
        return recorded
    }
}

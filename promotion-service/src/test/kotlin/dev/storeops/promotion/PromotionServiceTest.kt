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
import org.springframework.dao.OptimisticLockingFailureException
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

    // ---- updating. "Today" is Monday 16 November (see the clock above).

    /** Stored with version 0; by default starting next week, so every term may still change. */
    private fun stored(
        validFrom: LocalDate = LocalDate.of(2026, 11, 23),
        validUntil: LocalDate = LocalDate.of(2026, 11, 29),
        storeIds: Set<String> = setOf("store-1001"),
    ): Promotion {
        val promotion = promotion(validFrom = validFrom, validUntil = validUntil, storeIds = storeIds).copy(version = 0)
        whenever(repository.findById(promotion.id)).thenReturn(Optional.of(promotion))
        return promotion
    }

    @Test
    fun `a promotion that hasn't started can change anything, and records an UPDATED event`() {
        val current = stored()
        val changes = promotion(title = "Alle kipdijfilet", validFrom = LocalDate.of(2026, 11, 24), validUntil = LocalDate.of(2026, 11, 30))

        val updated = service.update(current.id, 0, changes)

        assertEquals("Alle kipdijfilet", updated.title)
        assertEquals(current.id, updated.id)
        val event = recordedEvent(PromotionEventType.UPDATED, current.id)
        assertEquals(LocalDate.of(2026, 11, 24), event.validFrom)
        assertEquals("Alle kipdijfilet", event.title)
    }

    @Test
    fun `an outdated version is refused and nothing is saved`() {
        val current = stored()

        assertFailsWith<PromotionVersionMismatchException> { service.update(current.id, 7, promotion(title = "Other")) }
        verify(repository, never()).save(any<Promotion>())
    }

    @Test
    fun `a change that lands between our read and our write is reported as a version mismatch`() {
        val current = stored()
        doAnswer { throw OptimisticLockingFailureException("stale") }.whenever(repository).save(any<Promotion>())

        assertFailsWith<PromotionVersionMismatchException> { service.update(current.id, 0, promotion(title = "Other")) }
        verify(outbox, never()).add(any(), any(), any(), any(), any())
    }

    @Test
    fun `cancelled and ended promotions can't change`() {
        val cancelled = stored().cancel()
        whenever(repository.findById(cancelled.id)).thenReturn(Optional.of(cancelled))
        val ended = stored(validFrom = LocalDate.of(2026, 11, 2), validUntil = LocalDate.of(2026, 11, 8))

        assertFailsWith<PromotionNotChangeableException> { service.update(cancelled.id, 0, promotion(title = "Other")) }
        assertFailsWith<PromotionNotChangeableException> {
            service.update(ended.id, 0, promotion(validFrom = ended.validFrom, validUntil = LocalDate.of(2026, 11, 30)))
        }
    }

    @Test
    fun `once started, only the end date and the stores can change`() {
        val running = stored(validFrom = LocalDate.of(2026, 11, 16), validUntil = LocalDate.of(2026, 11, 22))

        assertFailsWith<PromotionNotChangeableException> {
            service.update(running.id, 0, promotion(title = "Other", validFrom = running.validFrom, validUntil = running.validUntil))
        }
        val extended =
            service.update(
                running.id,
                0,
                promotion(
                    validFrom = running.validFrom,
                    validUntil = LocalDate.of(2026, 11, 29),
                    storeIds = setOf("store-1001", "store-2002"),
                ),
            )
        assertEquals(LocalDate.of(2026, 11, 29), extended.validUntil)
        assertEquals(setOf("store-1001", "store-2002"), extended.stores.map { it.storeId }.toSet())
    }

    @Test
    fun `a running promotion can't be made to end in the past`() {
        val running = stored(validFrom = LocalDate.of(2026, 11, 9), validUntil = LocalDate.of(2026, 11, 22))

        assertFailsWith<PromotionNotChangeableException> {
            service.update(running.id, 0, promotion(validFrom = running.validFrom, validUntil = LocalDate.of(2026, 11, 15)))
        }
    }

    @Test
    fun `a change that changes nothing keeps the version and publishes nothing`() {
        val current = stored()

        assertSame(current, service.update(current.id, 0, promotion(validFrom = current.validFrom, validUntil = current.validUntil)))
        verify(repository, never()).save(any<Promotion>())
        verify(outbox, never()).add(any(), any(), any(), any(), any())
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

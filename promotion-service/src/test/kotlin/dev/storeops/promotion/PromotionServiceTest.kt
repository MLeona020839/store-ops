package dev.storeops.promotion

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
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

    // 23:30 UTC on Sunday 15 November is already Monday 16 November in Amsterdam.
    private val clock = Clock.fixed(Instant.parse("2026-11-15T23:30:00Z"), ZoneId.of("Europe/Amsterdam"))
    private val service = PromotionService(repository, clock)

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

    @Test
    fun `cancel saves the cancelled promotion`() {
        val scheduled = promotion().copy(version = 0)
        whenever(repository.findById(scheduled.id)).thenReturn(Optional.of(scheduled))
        doAnswer { it.arguments[0] }.whenever(repository).save(any<Promotion>())

        val result = service.cancel(scheduled.id)

        assertEquals(PromotionStatus.CANCELLED, result.status)
        verify(repository).save(scheduled.cancel())
    }

    @Test
    fun `cancelling twice changes nothing the second time`() {
        val cancelled = promotion().copy(version = 1).cancel()
        whenever(repository.findById(cancelled.id)).thenReturn(Optional.of(cancelled))

        assertSame(cancelled, service.cancel(cancelled.id))
        verify(repository, never()).save(any<Promotion>())
    }
}

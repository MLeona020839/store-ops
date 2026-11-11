package dev.storeops.promotion

import dev.storeops.events.PromotionEventType
import dev.storeops.events.Topics
import dev.storeops.promotion.outbox.Outbox
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Use cases for promotions. Every change that other services care about writes its event to
 * the outbox in the same transaction as the change itself.
 */
@Service
class PromotionService(
    private val repository: PromotionRepository,
    private val outbox: Outbox,
    private val clock: Clock,
) {
    @Transactional
    fun create(promotion: Promotion): Promotion {
        val saved = repository.save(promotion)
        publish(saved, PromotionEventType.SCHEDULED)
        return saved
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): Promotion = repository.findById(id).orElseThrow { PromotionNotFoundException(id) }

    /** Running on [from] or starting later; [from] defaults to today in the stores' time zone. */
    @Transactional(readOnly = true)
    fun listCurrentAndUpcoming(from: LocalDate? = null): List<Promotion> =
        repository.findAllByValidUntilGreaterThanEqualOrderByValidFrom(from ?: LocalDate.now(clock))

    /** Idempotent: cancelling a cancelled promotion returns it unchanged and publishes nothing. */
    @Transactional
    fun cancel(id: UUID): Promotion {
        val promotion = get(id)
        if (promotion.status == PromotionStatus.CANCELLED) return promotion
        val saved = repository.save(promotion.cancel())
        publish(saved, PromotionEventType.CANCELLED)
        return saved
    }

    private fun publish(
        promotion: Promotion,
        type: PromotionEventType,
    ) {
        val event = promotion.toEvent(type, Instant.now(clock))
        // Keyed by promotion id: all events for one promotion land on the same partition, in order.
        outbox.add(event.eventId, type.name, Topics.PROMOTION_EVENTS, promotion.id.toString(), event)
    }
}

package dev.storeops.promotion

import dev.storeops.events.PromotionEventType
import dev.storeops.events.Topics
import dev.storeops.promotion.outbox.Outbox
import org.springframework.dao.OptimisticLockingFailureException
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

    /**
     * Replaces a promotion's terms with [changes], if [expectedVersion] is still current.
     *
     * - Cancelled or ended promotions can't change: they're history.
     * - Once a promotion has started its labels are on the shelves, so only the end date and the
     *   stores can change. Anything else would need every store to relabel mid-promotion; that's
     *   a new promotion (cancel this one, create another), not an edit.
     * - A change that changes nothing returns the promotion as it is: no new version, no event.
     */
    @Transactional
    fun update(
        id: UUID,
        expectedVersion: Int,
        changes: Promotion,
    ): Promotion {
        val current = get(id)
        if (current.version != expectedVersion) throw PromotionVersionMismatchException(id)
        val today = LocalDate.now(clock)
        if (current.status == PromotionStatus.CANCELLED) {
            throw PromotionNotChangeableException("Promotion $id was cancelled and can't be changed")
        }
        if (current.validUntil.isBefore(today)) {
            throw PromotionNotChangeableException("Promotion $id ended on ${current.validUntil} and can't be changed")
        }

        val updated = current.withTermsOf(changes)
        if (!current.validFrom.isAfter(today)) {
            val onlyEndDateAndStores = updated.copy(validUntil = current.validUntil, stores = current.stores) == current
            if (!onlyEndDateAndStores) {
                throw PromotionNotChangeableException(
                    "Promotion $id started on ${current.validFrom}: only its end date and stores can change. " +
                        "To change anything else, cancel it and create a new promotion.",
                )
            }
            if (updated.validUntil.isBefore(today)) {
                throw PromotionNotChangeableException("A running promotion can't be changed to have ended before today ($today)")
            }
        }
        if (updated == current) return current

        // The version check above and this save's own optimistic lock together cover a change
        // that lands between our read and our write.
        val saved =
            try {
                repository.save(updated)
            } catch (e: OptimisticLockingFailureException) {
                throw PromotionVersionMismatchException(id)
            }
        publish(saved, PromotionEventType.UPDATED)
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

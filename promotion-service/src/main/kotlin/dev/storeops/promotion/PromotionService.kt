package dev.storeops.promotion

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Use cases for promotions. Transactions start here, so a later step (publishing an event
 * through an outbox table) can join the same transaction as the change it announces.
 */
@Service
class PromotionService(
    private val repository: PromotionRepository,
    private val clock: Clock,
) {
    @Transactional
    fun create(promotion: Promotion): Promotion = repository.save(promotion)

    @Transactional(readOnly = true)
    fun get(id: UUID): Promotion = repository.findById(id).orElseThrow { PromotionNotFoundException(id) }

    /** Running on [from] or starting later; [from] defaults to today in the stores' time zone. */
    @Transactional(readOnly = true)
    fun listCurrentAndUpcoming(from: LocalDate? = null): List<Promotion> =
        repository.findAllByValidUntilGreaterThanEqualOrderByValidFrom(from ?: LocalDate.now(clock))

    /** Idempotent: cancelling a cancelled promotion returns it unchanged. */
    @Transactional
    fun cancel(id: UUID): Promotion {
        val promotion = get(id)
        if (promotion.status == PromotionStatus.CANCELLED) return promotion
        return repository.save(promotion.cancel())
    }
}

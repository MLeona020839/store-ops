package dev.storeops.promotion

import org.springframework.data.repository.ListCrudRepository
import java.time.LocalDate
import java.util.UUID

interface PromotionRepository : ListCrudRepository<Promotion, UUID> {
    /** Promotions still running on [date] or starting later, soonest first. Ended ones are left out. */
    fun findAllByValidUntilGreaterThanEqualOrderByValidFrom(date: LocalDate): List<Promotion>
}

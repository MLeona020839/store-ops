package dev.storeops.storetask

import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

interface StoreTaskRepository : ListCrudRepository<StoreTask, UUID> {
    fun findAllByPromotionId(promotionId: UUID): List<StoreTask>
}

package dev.storeops.storetask

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.ListCrudRepository
import java.time.LocalDate
import java.util.UUID

interface StoreTaskRepository : ListCrudRepository<StoreTask, UUID> {
    fun findAllByPromotionId(promotionId: UUID): List<StoreTask>

    /**
     * A store's open work due by [until], overdue tasks included, earliest first.
     * Served by the partial index store_task_open_by_store.
     */
    @Query(
        """
        SELECT * FROM store_task
        WHERE store_id = :storeId AND status = 'OPEN' AND due_date <= :until
        ORDER BY due_date, action, id
        """,
    )
    fun findOpenForStore(
        storeId: String,
        until: LocalDate,
    ): List<StoreTask>
}

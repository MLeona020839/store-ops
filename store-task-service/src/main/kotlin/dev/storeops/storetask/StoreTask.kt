package dev.storeops.storetask

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.InsertOnlyProperty
import org.springframework.data.relational.core.mapping.MappedCollection
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class TaskAction {
    /** Put the promotion's shelf labels up, on the promotion's first day. */
    PUT_UP_LABELS,

    /** Take them down again, the morning after its last day (or once it's cancelled). */
    TAKE_DOWN_LABELS,
}

enum class TaskStatus {
    OPEN,
    DONE,
    CANCELLED,
}

/** One piece of shelf-label work for one store. Aggregate root, together with its products. */
data class StoreTask(
    @Id val id: UUID = UUID.randomUUID(),
    val promotionId: UUID,
    val storeId: String,
    val action: TaskAction,
    val title: String,
    val label: String,
    val dueDate: LocalDate,
    val status: TaskStatus = TaskStatus.OPEN,
    val completedAt: Instant? = null,
    @MappedCollection(idColumn = "store_task_id") val products: Set<StoreTaskProduct>,
    /** Optimistic locking; null means not saved yet (ids are assigned here, see promotion-service). */
    @Version val version: Int? = null,
    @CreatedDate @InsertOnlyProperty val createdAt: Instant? = null,
    @LastModifiedDate val updatedAt: Instant? = null,
) {
    init {
        require(products.isNotEmpty()) { "A task must cover at least one product" }
        require((status == TaskStatus.DONE) == (completedAt != null)) { "completedAt is set exactly when the task is done" }
    }

    fun cancel(): StoreTask {
        check(status == TaskStatus.OPEN) { "Only an open task can be cancelled, this one is $status" }
        return copy(status = TaskStatus.CANCELLED)
    }

    /** Idempotent: completing a done task keeps the first completion time. A cancelled task can't be completed. */
    fun complete(at: Instant): StoreTask =
        when (status) {
            TaskStatus.OPEN -> copy(status = TaskStatus.DONE, completedAt = at)
            TaskStatus.DONE -> this
            TaskStatus.CANCELLED -> throw IllegalStateException("Task $id was cancelled and can't be completed")
        }

    /** A cancelled task wanted again, e.g. its store was removed from a promotion and then added back. */
    fun reopen(): StoreTask {
        check(status == TaskStatus.CANCELLED) { "Only a cancelled task can be reopened, this one is $status" }
        return copy(status = TaskStatus.OPEN)
    }

    /** Moves the due date forward to [date] if it's later; never pushes it back. */
    fun dueNoLaterThan(date: LocalDate): StoreTask = if (dueDate.isAfter(date)) copy(dueDate = date) else this
}

data class StoreTaskProduct(
    val productId: String,
)

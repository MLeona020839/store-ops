package dev.storeops.storetask

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A valid open task; tests override only what they're about. */
fun storeTask(
    storeId: String = "store-1001",
    action: TaskAction = TaskAction.PUT_UP_LABELS,
    dueDate: LocalDate = LocalDate.of(2026, 11, 16),
    status: TaskStatus = TaskStatus.OPEN,
    promotionId: UUID = UUID.randomUUID(),
    productIds: Set<String> = setOf("product-42"),
) = StoreTask(
    promotionId = promotionId,
    storeId = storeId,
    action = action,
    title = "Alle kipfilet",
    label = "25% korting",
    dueDate = dueDate,
    status = status,
    completedAt = if (status == TaskStatus.DONE) Instant.parse("2026-11-16T07:00:00Z") else null,
    products = productIds.map(::StoreTaskProduct).toSet(),
)

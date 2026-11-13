package dev.storeops.storetask

import dev.storeops.events.PromotionEvent
import dev.storeops.events.PromotionEventType
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoreTaskPlanningTest {
    private val promotionId = UUID.randomUUID()

    private fun event(type: PromotionEventType = PromotionEventType.SCHEDULED) =
        PromotionEvent(
            eventId = UUID.randomUUID(),
            type = type,
            promotionId = promotionId,
            title = "Alle kipfilet",
            label = "25% korting",
            productIds = listOf("product-42", "product-43"),
            storeIds = listOf("store-1001", "store-1002"),
            validFrom = LocalDate.of(2026, 11, 16),
            validUntil = LocalDate.of(2026, 11, 22),
            occurredAt = Instant.parse("2026-11-11T09:00:00Z"),
        )

    private fun task(
        action: TaskAction,
        status: TaskStatus = TaskStatus.OPEN,
        dueDate: LocalDate =
            if (action == TaskAction.PUT_UP_LABELS) LocalDate.of(2026, 11, 16) else LocalDate.of(2026, 11, 23),
    ) = StoreTask(
        promotionId = promotionId,
        storeId = "store-1001",
        action = action,
        title = "Alle kipfilet",
        label = "25% korting",
        dueDate = dueDate,
        status = status,
        completedAt = if (status == TaskStatus.DONE) Instant.parse("2026-11-16T07:00:00Z") else null,
        products = setOf(StoreTaskProduct("product-42")),
    )

    @Test
    fun `a scheduled promotion gives every store a put-up and a take-down task`() {
        val tasks = StoreTaskPlanning.tasksForScheduled(event())

        assertEquals(4, tasks.size)
        tasks.groupBy { it.storeId }.forEach { (_, storeTasks) ->
            val byAction = storeTasks.associateBy { it.action }
            assertEquals(LocalDate.of(2026, 11, 16), byAction.getValue(TaskAction.PUT_UP_LABELS).dueDate)
            // The morning after the last day, not the last day itself.
            assertEquals(LocalDate.of(2026, 11, 23), byAction.getValue(TaskAction.TAKE_DOWN_LABELS).dueDate)
        }
        assertTrue(tasks.all { it.status == TaskStatus.OPEN && it.products.size == 2 && it.label == "25% korting" })
    }

    @Test
    fun `cancelling before the labels went up cancels both tasks`() {
        val changes =
            StoreTaskPlanning.changesForCancelled(
                putUp = task(TaskAction.PUT_UP_LABELS),
                takeDown = task(TaskAction.TAKE_DOWN_LABELS),
                cancelledOn = LocalDate.of(2026, 11, 12),
            )

        assertEquals(listOf(TaskStatus.CANCELLED, TaskStatus.CANCELLED), changes.map { it.status })
    }

    @Test
    fun `cancelling after the labels went up brings the take-down forward to that day`() {
        val takeDown = task(TaskAction.TAKE_DOWN_LABELS)

        val changes =
            StoreTaskPlanning.changesForCancelled(
                putUp = task(TaskAction.PUT_UP_LABELS, TaskStatus.DONE),
                takeDown = takeDown,
                cancelledOn = LocalDate.of(2026, 11, 18),
            )

        assertEquals(listOf(takeDown.copy(dueDate = LocalDate.of(2026, 11, 18))), changes)
    }

    @Test
    fun `nothing changes when the labels are already down or the tasks already cancelled`() {
        val cancelledOn = LocalDate.of(2026, 11, 25)
        assertEquals(
            emptyList(),
            StoreTaskPlanning.changesForCancelled(
                putUp = task(TaskAction.PUT_UP_LABELS, TaskStatus.DONE),
                takeDown = task(TaskAction.TAKE_DOWN_LABELS, TaskStatus.DONE),
                cancelledOn = cancelledOn,
            ),
        )
        assertEquals(
            emptyList(),
            StoreTaskPlanning.changesForCancelled(
                putUp = task(TaskAction.PUT_UP_LABELS, TaskStatus.CANCELLED),
                takeDown = task(TaskAction.TAKE_DOWN_LABELS, TaskStatus.CANCELLED),
                cancelledOn = cancelledOn,
            ),
        )
        assertEquals(emptyList(), StoreTaskPlanning.changesForCancelled(putUp = null, takeDown = null, cancelledOn = cancelledOn))
    }

    @Test
    fun `a cancellation after the last day doesn't push the take-down back`() {
        val changes =
            StoreTaskPlanning.changesForCancelled(
                putUp = task(TaskAction.PUT_UP_LABELS, TaskStatus.DONE),
                takeDown = task(TaskAction.TAKE_DOWN_LABELS),
                cancelledOn = LocalDate.of(2026, 11, 30),
            )

        assertEquals(emptyList(), changes)
    }

    @Test
    fun `the cancellation day is the Dutch calendar day of the event`() {
        val lateSundayUtc = event(PromotionEventType.CANCELLED).copy(occurredAt = Instant.parse("2026-11-15T23:30:00Z"))

        assertEquals(LocalDate.of(2026, 11, 16), StoreTaskPlanning.cancellationDay(lateSundayUtc))
    }
}

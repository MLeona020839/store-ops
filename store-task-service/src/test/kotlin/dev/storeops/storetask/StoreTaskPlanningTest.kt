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

        assertEquals(LocalDate.of(2026, 11, 16), StoreTaskPlanning.dayOf(lateSundayUtc))
    }

    // ---- updated promotions. Existing tasks come from the original event (stores 1001 and 1002,
    // 16-22 November); the update arrives on Wednesday 18 November.

    private val updatedOn = Instant.parse("2026-11-18T09:00:00Z")

    private fun existing(vararg overrides: Pair<String, TaskStatus>) =
        StoreTaskPlanning.tasksForScheduled(event()).map { task ->
            val status = overrides.toMap()["${task.storeId}/${task.action}"] ?: TaskStatus.OPEN
            when (status) {
                TaskStatus.OPEN -> task
                TaskStatus.DONE -> task.complete(Instant.parse("2026-11-16T07:00:00Z"))
                TaskStatus.CANCELLED -> task.cancel()
            }
        }

    private fun updated(
        storeIds: List<String> = listOf("store-1001", "store-1002"),
        validFrom: LocalDate = LocalDate.of(2026, 11, 16),
        validUntil: LocalDate = LocalDate.of(2026, 11, 22),
        label: String = "25% korting",
    ) = event(PromotionEventType.UPDATED).copy(
        storeIds = storeIds,
        validFrom = validFrom,
        validUntil = validUntil,
        label = label,
        occurredAt = updatedOn,
    )

    private fun List<StoreTask>.of(
        storeId: String,
        action: TaskAction,
    ) = single { it.storeId == storeId && it.action == action }

    @Test
    fun `an update that changes nothing changes no tasks`() {
        assertEquals(emptyList(), StoreTaskPlanning.changesForUpdated(updated(), existing()))
    }

    @Test
    fun `open tasks follow new dates and a new label`() {
        val changes =
            StoreTaskPlanning.changesForUpdated(
                updated(validFrom = LocalDate.of(2026, 11, 17), validUntil = LocalDate.of(2026, 11, 29), label = "1+1 gratis"),
                existing(),
            )

        assertEquals(4, changes.size)
        assertEquals(LocalDate.of(2026, 11, 17), changes.of("store-1001", TaskAction.PUT_UP_LABELS).dueDate)
        assertEquals(LocalDate.of(2026, 11, 30), changes.of("store-1001", TaskAction.TAKE_DOWN_LABELS).dueDate)
        assertTrue(changes.all { it.label == "1+1 gratis" && it.status == TaskStatus.OPEN })
    }

    @Test
    fun `labels that are already up stay done, and the take-down follows a new end date`() {
        val changes =
            StoreTaskPlanning.changesForUpdated(
                updated(validUntil = LocalDate.of(2026, 11, 29)),
                existing("store-1001/PUT_UP_LABELS" to TaskStatus.DONE),
            )

        // Store 1001's put-up is done and unchanged; both stores' take-downs move to the 30th.
        assertTrue(changes.none { it.storeId == "store-1001" && it.action == TaskAction.PUT_UP_LABELS })
        assertEquals(LocalDate.of(2026, 11, 30), changes.of("store-1001", TaskAction.TAKE_DOWN_LABELS).dueDate)
        assertEquals(LocalDate.of(2026, 11, 30), changes.of("store-1002", TaskAction.TAKE_DOWN_LABELS).dueDate)
    }

    @Test
    fun `an added store gets both tasks`() {
        val changes = StoreTaskPlanning.changesForUpdated(updated(storeIds = listOf("store-1001", "store-1002", "store-3003")), existing())

        assertEquals(setOf(TaskAction.PUT_UP_LABELS, TaskAction.TAKE_DOWN_LABELS), changes.map { it.action }.toSet())
        assertTrue(changes.all { it.storeId == "store-3003" && it.status == TaskStatus.OPEN && it.version == null })
    }

    @Test
    fun `a removed store is handled like a cancellation for that store`() {
        // 1002 hadn't put its labels up: both tasks cancelled. 1001 had: take-down due on the day of the update.
        val changes =
            StoreTaskPlanning.changesForUpdated(
                updated(storeIds = listOf("store-3003")),
                existing("store-1001/PUT_UP_LABELS" to TaskStatus.DONE),
            )

        assertEquals(LocalDate.of(2026, 11, 18), changes.of("store-1001", TaskAction.TAKE_DOWN_LABELS).dueDate)
        assertEquals(TaskStatus.CANCELLED, changes.of("store-1002", TaskAction.PUT_UP_LABELS).status)
        assertEquals(TaskStatus.CANCELLED, changes.of("store-1002", TaskAction.TAKE_DOWN_LABELS).status)
    }

    @Test
    fun `a store removed and then added back gets its tasks reopened`() {
        val removedEarlier =
            existing("store-1002/PUT_UP_LABELS" to TaskStatus.CANCELLED, "store-1002/TAKE_DOWN_LABELS" to TaskStatus.CANCELLED)

        val changes = StoreTaskPlanning.changesForUpdated(updated(), removedEarlier)

        assertEquals(2, changes.size)
        assertTrue(changes.all { it.storeId == "store-1002" && it.status == TaskStatus.OPEN && it.version == removedEarlier[0].version })
    }
}

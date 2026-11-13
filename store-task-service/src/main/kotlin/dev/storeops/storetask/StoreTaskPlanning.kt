package dev.storeops.storetask

import dev.storeops.events.PromotionEvent
import java.time.LocalDate
import java.time.ZoneId

/**
 * The business rules for turning promotion events into tasks, kept free of Spring and the
 * database so they can be tested on their own.
 */
object StoreTaskPlanning {
    /** Stores work in Dutch calendar days; an event at 23:30 UTC on Sunday happened on Monday. */
    val STORE_ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")

    /** A scheduled promotion: every store puts the labels up on day one and takes them down after the last day. */
    fun tasksForScheduled(event: PromotionEvent): List<StoreTask> = event.storeIds.flatMap { tasksForStore(event, it) }

    private fun tasksForStore(
        event: PromotionEvent,
        storeId: String,
    ): List<StoreTask> =
        TaskAction.entries.map { action ->
            StoreTask(
                promotionId = event.promotionId,
                storeId = storeId,
                action = action,
                title = event.title,
                label = event.label,
                dueDate = dueDate(event, action),
                products = productsOf(event),
            )
        }

    private fun dueDate(
        event: PromotionEvent,
        action: TaskAction,
    ): LocalDate =
        when (action) {
            TaskAction.PUT_UP_LABELS -> event.validFrom
            TaskAction.TAKE_DOWN_LABELS -> event.validUntil.plusDays(1)
        }

    private fun productsOf(event: PromotionEvent) = event.productIds.map(::StoreTaskProduct).toSet()

    /**
     * A cancelled promotion, for one store. Returns only the tasks that change.
     *
     * - Labels not up yet (put-up task still open): nothing to do in the store, cancel both tasks.
     * - Labels already up (put-up task done): they must come down, so the take-down is due on the
     *   day of the cancellation instead of after the promotion's last day.
     * - Anything else (already cancelled, already taken down): nothing changes, which also makes
     *   handling the same cancellation twice harmless.
     *
     * The cancellation day comes from the event, not from the clock, so replaying an event
     * gives the same result as handling it the first time.
     */
    fun changesForCancelled(
        putUp: StoreTask?,
        takeDown: StoreTask?,
        cancelledOn: LocalDate,
    ): List<StoreTask> {
        val openTakeDown = takeDown?.takeIf { it.status == TaskStatus.OPEN }
        return when (putUp?.status) {
            TaskStatus.OPEN -> listOfNotNull(putUp.cancel(), openTakeDown?.cancel())
            TaskStatus.DONE -> listOfNotNull(openTakeDown?.dueNoLaterThan(cancelledOn)?.takeIf { it != openTakeDown })
            TaskStatus.CANCELLED, null -> emptyList()
        }
    }

    /**
     * A changed promotion. The event carries its complete new state; this compares it with the
     * tasks there are and returns only the tasks to create or change.
     *
     * - A store that was added gets both tasks.
     * - A store that was removed is handled as if the promotion were cancelled for that store.
     * - For the stores that stay, open tasks follow the new dates and label; done tasks are left
     *   alone (promotion-service doesn't allow changing the label once the promotion has started).
     * - A store that was removed and then added again gets its cancelled tasks reopened.
     */
    fun changesForUpdated(
        event: PromotionEvent,
        existing: List<StoreTask>,
    ): List<StoreTask> {
        val byStore = existing.groupBy { it.storeId }
        val removed =
            (byStore.keys - event.storeIds.toSet()).flatMap { storeId ->
                val tasks = byStore.getValue(storeId)
                changesForCancelled(tasks.find(TaskAction.PUT_UP_LABELS), tasks.find(TaskAction.TAKE_DOWN_LABELS), dayOf(event))
            }
        val kept =
            event.storeIds.flatMap { storeId ->
                val tasks = byStore[storeId].orEmpty()
                if (tasks.isEmpty()) {
                    tasksForStore(event, storeId)
                } else {
                    tasks.mapNotNull { task -> followed(task, event)?.takeIf { it != task } }
                }
            }
        return removed + kept
    }

    /** [task] brought in line with [event], or the task itself if there's nothing to follow. */
    private fun followed(
        task: StoreTask,
        event: PromotionEvent,
    ): StoreTask? {
        val reopenedOrOpen =
            when (task.status) {
                TaskStatus.OPEN -> task
                TaskStatus.CANCELLED -> task.reopen()
                TaskStatus.DONE -> return task
            }
        return reopenedOrOpen.copy(
            title = event.title,
            label = event.label,
            dueDate = dueDate(event, task.action),
            products = productsOf(event),
        )
    }

    private fun List<StoreTask>.find(action: TaskAction) = firstOrNull { it.action == action }

    /** The Dutch calendar day an event happened on: when the stores have to act on it. */
    fun dayOf(event: PromotionEvent): LocalDate = event.occurredAt.atZone(STORE_ZONE).toLocalDate()
}

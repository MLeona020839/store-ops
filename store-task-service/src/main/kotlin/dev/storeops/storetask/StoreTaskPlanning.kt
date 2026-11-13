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
    fun tasksForScheduled(event: PromotionEvent): List<StoreTask> {
        val products = event.productIds.map(::StoreTaskProduct).toSet()
        return event.storeIds.flatMap { storeId ->
            listOf(
                TaskAction.PUT_UP_LABELS to event.validFrom,
                TaskAction.TAKE_DOWN_LABELS to event.validUntil.plusDays(1),
            ).map { (action, dueDate) ->
                StoreTask(
                    promotionId = event.promotionId,
                    storeId = storeId,
                    action = action,
                    title = event.title,
                    label = event.label,
                    dueDate = dueDate,
                    products = products,
                )
            }
        }
    }

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

    fun cancellationDay(event: PromotionEvent): LocalDate = event.occurredAt.atZone(STORE_ZONE).toLocalDate()
}

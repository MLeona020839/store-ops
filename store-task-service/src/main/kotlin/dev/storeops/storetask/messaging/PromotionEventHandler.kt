package dev.storeops.storetask.messaging

import dev.storeops.events.PromotionEvent
import dev.storeops.events.PromotionEventType
import dev.storeops.storetask.StoreTaskPlanning
import dev.storeops.storetask.StoreTaskRepository
import dev.storeops.storetask.TaskAction
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Applies one promotion event to the store tasks, exactly once per event id. */
@Service
class PromotionEventHandler(
    private val tasks: StoreTaskRepository,
    private val processedEvents: ProcessedEvents,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * One transaction for the duplicate check and all task changes. The Kafka offset is only
     * committed after this returns, so a crash before the commit means redelivery, and the
     * redelivery either finds nothing committed (and does the work) or finds the event id
     * (and skips it). Either way the work happens once.
     */
    @Transactional
    fun handle(event: PromotionEvent) {
        if (!processedEvents.markProcessed(event.eventId)) {
            log.info("Skipping duplicate event {} ({} for promotion {})", event.eventId, event.type, event.promotionId)
            return
        }
        when (event.type) {
            PromotionEventType.SCHEDULED -> scheduled(event)
            PromotionEventType.CANCELLED -> cancelled(event)
        }
    }

    private fun scheduled(event: PromotionEvent) {
        // The unique constraint would reject duplicates anyway; skipping them keeps one bad
        // store from failing the whole event.
        val existing = tasks.findAllByPromotionId(event.promotionId).map { it.storeId to it.action }.toSet()
        val new = StoreTaskPlanning.tasksForScheduled(event).filterNot { (it.storeId to it.action) in existing }
        tasks.saveAll(new)
        log.info("Promotion {} scheduled: {} tasks for {} stores", event.promotionId, new.size, event.storeIds.size)
    }

    private fun cancelled(event: PromotionEvent) {
        val cancelledOn = StoreTaskPlanning.cancellationDay(event)
        val changes =
            tasks
                .findAllByPromotionId(event.promotionId)
                .groupBy { it.storeId }
                .values
                .flatMap { storeTasks ->
                    StoreTaskPlanning.changesForCancelled(
                        putUp = storeTasks.find { it.action == TaskAction.PUT_UP_LABELS },
                        takeDown = storeTasks.find { it.action == TaskAction.TAKE_DOWN_LABELS },
                        cancelledOn = cancelledOn,
                    )
                }
        if (changes.isEmpty()) {
            // E.g. the SCHEDULED event never arrived (it's on the dead-letter topic): nothing to undo.
            log.warn("Promotion {} cancelled, but no open tasks were found for it", event.promotionId)
        }
        tasks.saveAll(changes)
        log.info("Promotion {} cancelled on {}: {} tasks changed", event.promotionId, cancelledOn, changes.size)
    }
}

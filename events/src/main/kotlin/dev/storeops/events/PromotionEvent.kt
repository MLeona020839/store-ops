package dev.storeops.events

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Kafka topic names shared by producers and consumers. */
object Topics {
    const val PROMOTION_EVENTS = "promotion-events"
}

enum class PromotionEventType {
    /** A promotion was scheduled; stores need to put up new shelf labels on [PromotionEvent.validFrom]. */
    SCHEDULED,

    /** A promotion was cancelled before or during its run; stores need to restore regular labels. */
    CANCELLED,
}

/**
 * Starting point for the event contract between promotion-service and store-task-service.
 * Evolve it as the domain becomes clearer, and keep changes backwards compatible
 * (add fields, don't rename or remove them) once both services consume it.
 */
data class PromotionEvent(
    /** Unique per event; consumers use it to ignore duplicates (Kafka delivers at least once). */
    val eventId: UUID,
    val type: PromotionEventType,
    val promotionId: UUID,
    val productId: String,
    val storeIds: List<String>,
    val validFrom: LocalDate,
    val validUntil: LocalDate,
    val occurredAt: Instant,
) {
    init {
        require(storeIds.isNotEmpty()) { "A promotion must apply to at least one store" }
        require(!validUntil.isBefore(validFrom)) { "validUntil ($validUntil) is before validFrom ($validFrom)" }
    }
}

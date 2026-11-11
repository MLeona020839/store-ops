package dev.storeops.promotion

import dev.storeops.events.PromotionEvent
import dev.storeops.events.PromotionEventType
import java.time.Instant
import java.util.UUID

/** The event that tells other services about this promotion. Ids are sorted so payloads are stable. */
fun Promotion.toEvent(
    type: PromotionEventType,
    occurredAt: Instant,
    eventId: UUID = UUID.randomUUID(),
) = PromotionEvent(
    eventId = eventId,
    type = type,
    promotionId = id,
    title = title,
    label = label,
    productIds = products.map { it.productId }.sorted(),
    storeIds = stores.map { it.storeId }.sorted(),
    validFrom = validFrom,
    validUntil = validUntil,
    occurredAt = occurredAt,
)

package dev.storeops.promotion

import java.math.BigDecimal
import java.time.LocalDate

/** A valid promotion; tests override only what they're about. */
fun promotion(
    title: String = "Alle kipfilet",
    kind: PromotionKind = PromotionKind.PERCENT_OFF,
    percent: BigDecimal? = BigDecimal("25"),
    priceCents: Int? = null,
    buy: Int? = null,
    pay: Int? = null,
    validFrom: LocalDate = LocalDate.of(2026, 11, 16),
    validUntil: LocalDate = LocalDate.of(2026, 11, 22),
    storeIds: Set<String> = setOf("store-1001"),
    productIds: Set<String> = setOf("product-42"),
) = Promotion(
    title = title,
    label = "Test label",
    kind = kind,
    percent = percent,
    priceCents = priceCents,
    buy = buy,
    pay = pay,
    validFrom = validFrom,
    validUntil = validUntil,
    stores = storeIds.map(::PromotionStore).toSet(),
    products = productIds.map(::PromotionProduct).toSet(),
)

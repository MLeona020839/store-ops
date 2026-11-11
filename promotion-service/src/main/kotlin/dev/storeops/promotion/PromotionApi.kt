package dev.storeops.promotion

import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Request body for creating a promotion. The annotations bound the input (sizes, column
 * ranges); the business rules live in [Promotion] and are reported as 400 by [toPromotion].
 * Missing required fields or an unknown `kind` are rejected by Jackson before this is built.
 */
data class CreatePromotionRequest(
    @field:NotBlank @field:Size(max = 200) val title: String,
    @field:NotBlank @field:Size(max = 100) val label: String,
    val kind: PromotionKind,
    // numeric(5, 2): without this, PostgreSQL would silently round 12.345 to 12.35.
    @field:Digits(integer = 2, fraction = 2) val percent: BigDecimal? = null,
    @field:Positive @field:Max(10_000_000) val priceCents: Int? = null,
    @field:Positive @field:Max(99) val buy: Int? = null,
    @field:Positive @field:Max(99) val pay: Int? = null,
    val validFrom: LocalDate,
    val validUntil: LocalDate,
    @field:NotEmpty @field:Size(max = 2000) val storeIds: Set<String>,
    @field:NotEmpty @field:Size(max = 1000) val productIds: Set<String>,
) {
    fun toPromotion(): Promotion =
        try {
            Promotion(
                title = title.trim(),
                label = label.trim(),
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
        } catch (e: IllegalArgumentException) {
            // Only the domain's own rule checks are turned into 400s, not any IllegalArgumentException.
            throw InvalidPromotionException(e.message ?: "Invalid promotion")
        }
}

data class PromotionResponse(
    val id: UUID,
    val title: String,
    val label: String,
    val kind: PromotionKind,
    val percent: BigDecimal?,
    val priceCents: Int?,
    val buy: Int?,
    val pay: Int?,
    val validFrom: LocalDate,
    val validUntil: LocalDate,
    val status: PromotionStatus,
    val storeIds: List<String>,
    val productIds: List<String>,
    val version: Int?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

fun Promotion.toResponse() =
    PromotionResponse(
        id = id,
        title = title,
        label = label,
        kind = kind,
        percent = percent,
        priceCents = priceCents,
        buy = buy,
        pay = pay,
        validFrom = validFrom,
        validUntil = validUntil,
        status = status,
        // Sorted so responses are stable; sets have no order of their own.
        storeIds = stores.map { it.storeId }.sorted(),
        productIds = products.map { it.productId }.sorted(),
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

package dev.storeops.promotion

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.InsertOnlyProperty
import org.springframework.data.relational.core.mapping.MappedCollection
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** How the promotion works. Stored by name, matching the CHECK constraint in V1. */
enum class PromotionKind {
    PERCENT_OFF,
    PROMO_PRICE,
    BUY_X_PAY_Y,
    X_FOR_PRICE,
    SECOND_HALF_PRICE,
    OTHER,
}

enum class PromotionStatus {
    SCHEDULED,
    CANCELLED,
}

/**
 * Aggregate root: a promotion together with the stores and products it covers.
 * Spring Data JDBC always loads and saves the whole aggregate, so the stores and
 * products can't drift out of sync with the promotion.
 *
 * The rules in `init` mirror the database constraints. The database is the last line
 * of defence; checking here as well gives callers a clear error before any SQL runs.
 */
data class Promotion(
    @Id val id: UUID = UUID.randomUUID(),
    val title: String,
    val label: String,
    val kind: PromotionKind,
    val percent: BigDecimal? = null,
    val priceCents: Int? = null,
    val buy: Int? = null,
    val pay: Int? = null,
    val validFrom: LocalDate,
    val validUntil: LocalDate,
    val status: PromotionStatus = PromotionStatus.SCHEDULED,
    @MappedCollection(idColumn = "promotion_id") val stores: Set<PromotionStore>,
    @MappedCollection(idColumn = "promotion_id") val products: Set<PromotionProduct>,
    /**
     * Optimistic locking, and also how Spring Data tells a new aggregate from an existing one:
     * the id is assigned here rather than by the database, so it is never null, but a null
     * version means "not saved yet" and turns save() into an INSERT.
     */
    @Version val version: Int? = null,
    @CreatedDate @InsertOnlyProperty val createdAt: Instant? = null,
    @LastModifiedDate val updatedAt: Instant? = null,
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
        require(label.isNotBlank()) { "label must not be blank" }
        require(!validUntil.isBefore(validFrom)) { "validUntil ($validUntil) is before validFrom ($validFrom)" }
        require(stores.isNotEmpty()) { "A promotion must apply to at least one store" }
        require(products.isNotEmpty()) { "A promotion must cover at least one product" }

        // Exhaustive `when`: adding a PromotionKind won't compile until its rule is written here.
        when (kind) {
            PromotionKind.PERCENT_OFF ->
                require(percent != null && percent > BigDecimal.ZERO && percent < HUNDRED) {
                    "PERCENT_OFF needs a percent between 0 and 100, got $percent"
                }
            PromotionKind.PROMO_PRICE ->
                require(priceCents != null && priceCents > 0) { "PROMO_PRICE needs a positive priceCents" }
            PromotionKind.BUY_X_PAY_Y ->
                require(buy != null && pay != null && pay > 0 && buy > pay) {
                    "BUY_X_PAY_Y needs buy > pay > 0, got buy=$buy pay=$pay"
                }
            PromotionKind.X_FOR_PRICE ->
                require(buy != null && buy >= 1 && priceCents != null && priceCents > 0) {
                    "X_FOR_PRICE needs buy >= 1 and a positive priceCents"
                }
            PromotionKind.SECOND_HALF_PRICE, PromotionKind.OTHER -> Unit
        }
    }

    fun cancel(): Promotion = copy(status = PromotionStatus.CANCELLED)

    /**
     * This promotion with the terms (everything a client can set) of [other]; identity, status,
     * version and timestamps stay. The constructor checks the result's rules again.
     */
    fun withTermsOf(other: Promotion): Promotion =
        copy(
            title = other.title,
            label = other.label,
            kind = other.kind,
            percent = other.percent,
            priceCents = other.priceCents,
            buy = other.buy,
            pay = other.pay,
            validFrom = other.validFrom,
            validUntil = other.validUntil,
            stores = other.stores,
            products = other.products,
        )

    private companion object {
        val HUNDRED = BigDecimal(100)
    }
}

/** A store the promotion applies to. Store data itself lives in another system. */
data class PromotionStore(
    val storeId: String,
)

/** A product the promotion covers. Product data itself lives in another system. */
data class PromotionProduct(
    val productId: String,
)

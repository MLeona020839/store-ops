package dev.storeops.promotion

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.sql.Types
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Runs the Flyway migrations against real PostgreSQL and checks that the database itself
 * rejects invalid promotions, independent of any validation in the application code.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class PromotionSchemaTest(
    @Autowired private val jdbc: JdbcClient,
) {
    companion object {
        @JvmField
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }

    @Test
    fun `stores a promotion with its stores and products`() {
        val id = insertPromotion()
        insertStore(id, "store-1001")
        insertStore(id, "store-1002")
        insertProduct(id, "product-42")

        assertEquals(2, count("promotion_store", id))
        assertEquals(1, count("promotion_product", id))
        assertEquals(
            "SCHEDULED",
            jdbc
                .sql("SELECT status FROM promotion WHERE id = :id")
                .param("id", id)
                .query(String::class.java)
                .single(),
        )
    }

    @Test
    fun `accepts every kind when it has the fields it needs`() {
        insertPromotion(kind = "PERCENT_OFF", percent = BigDecimal("25"))
        insertPromotion(kind = "PROMO_PRICE", percent = null, priceCents = 299)
        insertPromotion(kind = "BUY_X_PAY_Y", percent = null, buy = 2, pay = 1)
        insertPromotion(kind = "X_FOR_PRICE", percent = null, buy = 2, priceCents = 500)
        insertPromotion(kind = "SECOND_HALF_PRICE", percent = null)
        insertPromotion(kind = "OTHER", percent = null)
    }

    @Test
    fun `rejects a promotion that ends before it starts`() {
        assertViolates("promotion_dates_ordered") {
            insertPromotion(validFrom = LocalDate.of(2026, 11, 22), validUntil = LocalDate.of(2026, 11, 16))
        }
    }

    @Test
    fun `rejects percent_off without a percent`() {
        assertViolates("promotion_kind_fields") { insertPromotion(kind = "PERCENT_OFF", percent = null) }
    }

    @Test
    fun `rejects buy_x_pay_y where you pay for every pack you get`() {
        assertViolates("promotion_kind_fields") {
            insertPromotion(kind = "BUY_X_PAY_Y", percent = null, buy = 2, pay = 2)
        }
    }

    @Test
    fun `rejects an unknown kind`() {
        assertViolates("promotion_kind_valid") { insertPromotion(kind = "FREE_LUNCH") }
    }

    @Test
    fun `rejects an unknown status`() {
        assertViolates("promotion_status_valid") { insertPromotion(status = "RUNNING") }
    }

    @Test
    fun `deleting a promotion removes its stores and products`() {
        val id = insertPromotion()
        insertStore(id, "store-1001")
        insertProduct(id, "product-42")

        jdbc.sql("DELETE FROM promotion WHERE id = :id").param("id", id).update()

        assertEquals(0, count("promotion_store", id))
        assertEquals(0, count("promotion_product", id))
    }

    private fun insertPromotion(
        kind: String = "PERCENT_OFF",
        percent: BigDecimal? = BigDecimal("25"),
        priceCents: Int? = null,
        buy: Int? = null,
        pay: Int? = null,
        validFrom: LocalDate = LocalDate.of(2026, 11, 16),
        validUntil: LocalDate = LocalDate.of(2026, 11, 22),
        status: String = "SCHEDULED",
    ): UUID {
        val id = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO promotion (id, title, label, kind, percent, price_cents, buy, pay, valid_from, valid_until, status)
                VALUES (:id, 'Alle kipfilet', 'Test label', :kind, :percent, :priceCents, :buy, :pay, :validFrom, :validUntil, :status)
                """,
            ).param("id", id)
            .param("kind", kind)
            .param("percent", percent, Types.NUMERIC)
            .param("priceCents", priceCents, Types.INTEGER)
            .param("buy", buy, Types.SMALLINT)
            .param("pay", pay, Types.SMALLINT)
            .param("validFrom", validFrom)
            .param("validUntil", validUntil)
            .param("status", status)
            .update()
        return id
    }

    private fun insertStore(
        promotionId: UUID,
        storeId: String,
    ) {
        jdbc
            .sql("INSERT INTO promotion_store (promotion_id, store_id) VALUES (:p, :s)")
            .param("p", promotionId)
            .param("s", storeId)
            .update()
    }

    private fun insertProduct(
        promotionId: UUID,
        productId: String,
    ) {
        jdbc
            .sql("INSERT INTO promotion_product (promotion_id, product_id) VALUES (:p, :s)")
            .param("p", promotionId)
            .param("s", productId)
            .update()
    }

    private fun count(
        table: String,
        promotionId: UUID,
    ): Int =
        jdbc
            .sql("SELECT count(*) FROM $table WHERE promotion_id = :id")
            .param("id", promotionId)
            .query(Int::class.javaObjectType)
            .single()

    /** Fails unless [block] is rejected by the named constraint (not just any constraint). */
    private fun assertViolates(
        constraint: String,
        block: () -> Unit,
    ) {
        val error = assertFailsWith<DataIntegrityViolationException> { block() }
        assertContains(error.message.orEmpty(), constraint)
    }
}

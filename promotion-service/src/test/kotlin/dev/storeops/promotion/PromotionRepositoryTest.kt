package dev.storeops.promotion

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.dao.OptimisticLockingFailureException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** Mapping between the Promotion aggregate and the V1 tables, against real PostgreSQL. */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcConfiguration::class)
@Testcontainers(disabledWithoutDocker = true)
class PromotionRepositoryTest(
    @Autowired private val repository: PromotionRepository,
) {
    companion object {
        @JvmField
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }

    @Test
    fun `saves a new promotion and loads it back with its stores and products`() {
        val saved = repository.save(promotion(storeIds = setOf("store-1001", "store-1002"), productIds = setOf("product-42")))

        val loaded = assertNotNull(repository.findById(saved.id).orElse(null))
        assertEquals(setOf("store-1001", "store-1002"), loaded.stores.map { it.storeId }.toSet())
        assertEquals(setOf("product-42"), loaded.products.map { it.productId }.toSet())
        assertEquals(0, loaded.version)
        assertNotNull(loaded.createdAt)
        assertNotNull(loaded.updatedAt)
    }

    @Test
    fun `round-trips every field`() {
        val saved = repository.save(promotion(kind = PromotionKind.X_FOR_PRICE, percent = null, buy = 2, priceCents = 500))

        val loaded = repository.findById(saved.id).orElseThrow()
        // Audit timestamps come back with the database's precision, so compare everything else.
        assertEquals(saved.copy(createdAt = null, updatedAt = null), loaded.copy(createdAt = null, updatedAt = null))
    }

    @Test
    fun `an update replaces the stores and bumps the version`() {
        val saved = repository.save(promotion(storeIds = setOf("store-1001")))

        val updated = repository.save(saved.copy(stores = setOf(PromotionStore("store-2001")), title = "Alle kipdijfilet"))

        val loaded = repository.findById(saved.id).orElseThrow()
        assertEquals(setOf("store-2001"), loaded.stores.map { it.storeId }.toSet())
        assertEquals("Alle kipdijfilet", loaded.title)
        assertEquals(1, updated.version)
        assertEquals(saved.createdAt, updated.createdAt)
    }

    @Test
    fun `a stale update is rejected instead of overwriting a newer one`() {
        val saved = repository.save(promotion())
        val firstEditor = repository.findById(saved.id).orElseThrow()
        val secondEditor = repository.findById(saved.id).orElseThrow()

        repository.save(firstEditor.copy(title = "First edit"))

        assertFailsWith<OptimisticLockingFailureException> {
            repository.save(secondEditor.copy(title = "Second edit"))
        }
    }

    @Test
    fun `cancelling is persisted`() {
        val saved = repository.save(promotion())

        repository.save(saved.cancel())

        assertEquals(PromotionStatus.CANCELLED, repository.findById(saved.id).orElseThrow().status)
    }

    @Test
    fun `lists running and upcoming promotions, soonest first`() {
        val today = LocalDate.of(2026, 11, 18)
        repository.save(promotion(title = "Ended", validFrom = today.minusDays(10), validUntil = today.minusDays(1)))
        val upcoming = repository.save(promotion(title = "Upcoming", validFrom = today.plusDays(5), validUntil = today.plusDays(11)))
        val endsToday = repository.save(promotion(title = "Ends today", validFrom = today.minusDays(6), validUntil = today))
        val running = repository.save(promotion(title = "Running", validFrom = today.minusDays(2), validUntil = today.plusDays(4)))

        val titles = repository.findAllByValidUntilGreaterThanEqualOrderByValidFrom(today).map { it.title }

        assertEquals(listOf(endsToday.title, running.title, upcoming.title), titles)
    }
}

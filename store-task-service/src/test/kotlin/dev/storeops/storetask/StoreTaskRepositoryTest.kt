package dev.storeops.storetask

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Mapping and queries of StoreTask against the V1 tables, on real PostgreSQL. */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcConfiguration::class)
@Testcontainers(disabledWithoutDocker = true)
class StoreTaskRepositoryTest(
    @Autowired private val tasks: StoreTaskRepository,
) {
    companion object {
        @JvmField
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }

    private val monday = LocalDate.of(2026, 11, 16)

    @Test
    fun `open tasks for a store, overdue included, earliest first, with their products`() {
        val store = "store-${UUID.randomUUID()}"
        val overdue = tasks.save(storeTask(storeId = store, dueDate = monday.minusDays(2), productIds = setOf("p-1", "p-2")))
        val takeDownToday = tasks.save(storeTask(storeId = store, dueDate = monday, action = TaskAction.TAKE_DOWN_LABELS))
        val putUpToday = tasks.save(storeTask(storeId = store, dueDate = monday, action = TaskAction.PUT_UP_LABELS))
        tasks.save(storeTask(storeId = store, dueDate = monday.plusDays(1))) // not due yet
        tasks.save(storeTask(storeId = store, dueDate = monday, status = TaskStatus.DONE))
        tasks.save(storeTask(storeId = store, dueDate = monday, status = TaskStatus.CANCELLED))
        tasks.save(storeTask(storeId = "store-elsewhere", dueDate = monday))

        val open = tasks.findOpenForStore(store, monday)

        assertEquals(listOf(overdue.id, putUpToday.id, takeDownToday.id), open.map { it.id })
        assertEquals(
            setOf("p-1", "p-2"),
            open
                .first()
                .products
                .map { it.productId }
                .toSet(),
        )
    }

    @Test
    fun `completing is persisted with its time`() {
        val saved = tasks.save(storeTask())
        val at = Instant.parse("2026-11-16T08:15:00Z")

        tasks.save(saved.complete(at))

        val loaded = tasks.findById(saved.id).orElseThrow()
        assertEquals(TaskStatus.DONE, loaded.status)
        assertEquals(at, loaded.completedAt)
        assertEquals(1, loaded.version)
    }

    @Test
    fun `one task per promotion, store and action`() {
        val promotionId = UUID.randomUUID()
        tasks.save(storeTask(promotionId = promotionId))

        assertFailsWith<DataIntegrityViolationException> { tasks.save(storeTask(promotionId = promotionId)) }
    }
}

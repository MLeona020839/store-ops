package dev.storeops.storetask.messaging

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

@JdbcTest(properties = ["messaging.processed-events.retention=30d", "messaging.processed-events.cleanup-batch-size=2"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ProcessedEventCleanup::class, ProcessedEventCleanupTest.FixedClock::class)
@Testcontainers(disabledWithoutDocker = true)
class ProcessedEventCleanupTest(
    @Autowired private val cleanup: ProcessedEventCleanup,
    @Autowired private val jdbc: JdbcClient,
) {
    companion object {
        @JvmField
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

        val NOW: Instant = Instant.parse("2026-11-13T12:00:00Z")
        val CUTOFF: Instant = NOW.minus(Duration.ofDays(30))
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FixedClock {
        @Bean
        fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    @Test
    fun `forgets only event ids processed before the retention period`() {
        val old = insert(CUTOFF.minusSeconds(1))
        val atCutoff = insert(CUTOFF)
        val recent = insert(NOW.minus(Duration.ofDays(1)))

        assertEquals(1, cleanup.cleanUp())

        assertEquals(setOf(atCutoff, recent), remaining(old, atCutoff, recent))
    }

    @Test
    fun `works through a backlog larger than one batch`() {
        val old = List(5) { insert(CUTOFF.minus(Duration.ofDays(it + 1L))) }

        assertEquals(5, cleanup.cleanUp()) // batches of 2, 2 and 1

        assertEquals(emptySet(), remaining(*old.toTypedArray()))
    }

    private fun insert(processedAt: Instant): UUID {
        val eventId = UUID.randomUUID()
        jdbc
            .sql("INSERT INTO processed_event (event_id, processed_at) VALUES (:id, :at)")
            .param("id", eventId)
            .param("at", processedAt.atOffset(ZoneOffset.UTC))
            .update()
        return eventId
    }

    private fun remaining(vararg eventIds: UUID): Set<UUID> =
        jdbc
            .sql("SELECT event_id FROM processed_event WHERE event_id IN (:ids)")
            .param("ids", eventIds.toList())
            .query(UUID::class.java)
            .list()
            .filterNotNull()
            .toSet()
}

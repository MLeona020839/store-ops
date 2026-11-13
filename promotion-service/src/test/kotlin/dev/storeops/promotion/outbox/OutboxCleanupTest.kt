package dev.storeops.promotion.outbox

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
import java.sql.Types
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

@JdbcTest(properties = ["outbox.cleanup.retention=7d", "outbox.cleanup.batch-size=2"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OutboxCleanup::class, OutboxCleanupTest.FixedClock::class)
@Testcontainers(disabledWithoutDocker = true)
class OutboxCleanupTest(
    @Autowired private val cleanup: OutboxCleanup,
    @Autowired private val jdbc: JdbcClient,
) {
    companion object {
        @JvmField
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

        val NOW: Instant = Instant.parse("2026-11-13T12:00:00Z")
        val CUTOFF: Instant = NOW.minus(Duration.ofDays(7))
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FixedClock {
        @Bean
        fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    @Test
    fun `deletes only rows published before the retention period`() {
        val oldPublished = insert(published = CUTOFF.minusSeconds(1))
        val atCutoff = insert(published = CUTOFF)
        val recentPublished = insert(published = NOW.minus(Duration.ofDays(1)))
        val oldUnpublished = insert(published = null, created = NOW.minus(Duration.ofDays(30)))

        assertEquals(1, cleanup.cleanUp())

        assertEquals(setOf(atCutoff, recentPublished, oldUnpublished), remaining(oldPublished, atCutoff, recentPublished, oldUnpublished))
    }

    @Test
    fun `works through a backlog larger than one batch`() {
        val old = List(5) { insert(published = CUTOFF.minus(Duration.ofDays(it + 1L))) }

        assertEquals(5, cleanup.cleanUp()) // batches of 2, 2 and 1

        assertEquals(emptySet(), remaining(*old.toTypedArray()))
    }

    private fun insert(
        published: Instant?,
        created: Instant = published ?: NOW,
    ): UUID {
        val eventId = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO outbox (event_id, event_type, topic, message_key, payload, created_at, published_at)
                VALUES (:eventId, 'SCHEDULED', 'promotion-events', 'key', '{}'::jsonb, :created, :published)
                """,
            ).param("eventId", eventId)
            .param("created", created.atOffset(ZoneOffset.UTC))
            .param("published", published?.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
            .update()
        return eventId
    }

    private fun remaining(vararg eventIds: UUID): Set<UUID> =
        jdbc
            .sql("SELECT event_id FROM outbox WHERE event_id IN (:ids)")
            .param("ids", eventIds.toList())
            .query(UUID::class.java)
            .list()
            .filterNotNull()
            .toSet()
}

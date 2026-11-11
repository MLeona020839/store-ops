package dev.storeops.promotion.outbox

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(Outbox::class)
@ImportAutoConfiguration(JacksonAutoConfiguration::class)
@Testcontainers(disabledWithoutDocker = true)
class OutboxTest(
    @Autowired private val outbox: Outbox,
    @Autowired private val jdbc: JdbcClient,
) {
    companion object {
        @JvmField
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }

    private data class SampleEvent(
        val name: String,
        val count: Int,
    )

    @Test
    fun `stores the event as JSON, unpublished`() {
        val eventId = UUID.randomUUID()

        outbox.add(eventId, "SAMPLE", "sample-topic", "key-1", SampleEvent("kipfilet", 2))

        val row =
            jdbc
                .sql(
                    "SELECT event_type, topic, message_key, payload->>'name' AS name, (payload->>'count')::int AS count, " +
                        "published_at FROM outbox WHERE event_id = :id",
                ).param("id", eventId)
                .query { rs, _ ->
                    listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getObject(6))
                }.single()
        assertEquals(listOf("SAMPLE", "sample-topic", "key-1", "kipfilet", 2), row.take(5))
        assertNull(row[5])
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `refuses to write outside a transaction`() {
        assertFailsWith<IllegalTransactionStateException> {
            outbox.add(UUID.randomUUID(), "SAMPLE", "sample-topic", "key-1", SampleEvent("kipfilet", 2))
        }
    }
}

package dev.storeops.promotion

import dev.storeops.events.PromotionEvent
import dev.storeops.events.PromotionEventType
import dev.storeops.events.Topics
import dev.storeops.promotion.outbox.OutboxRelay
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.ConsumerFactory
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.Properties
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * End to end against real PostgreSQL and Kafka: a change through the service ends up as a
 * message on the topic, sent by the scheduled outbox relay, and the outbox row is marked.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Testcontainers(disabledWithoutDocker = true)
class PromotionEventPublishingTest(
    @Autowired private val service: PromotionService,
    @Autowired private val consumerFactory: ConsumerFactory<String, String>,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val jdbc: JdbcClient,
) {
    @Test
    fun `scheduling and cancelling a promotion publish both events, in order`() {
        val created = service.create(promotion(storeIds = setOf("store-1001", "store-1002")))
        service.cancel(created.id)

        val records = receive(key = created.id.toString(), count = 2)

        val (scheduled, cancelled) = records.map { jsonMapper.readValue(it.value(), PromotionEvent::class.java) }
        assertEquals(PromotionEventType.SCHEDULED, scheduled.type)
        assertEquals(PromotionEventType.CANCELLED, cancelled.type)
        assertEquals(created.toEvent(PromotionEventType.SCHEDULED, scheduled.occurredAt, scheduled.eventId), scheduled)
        assertEquals(
            listOf("SCHEDULED", "CANCELLED"),
            records.map { String(it.headers().lastHeader(OutboxRelay.EVENT_TYPE_HEADER).value()) },
        )
        assertEquals(1, records.map { it.partition() }.distinct().size, "one promotion's events share a partition")

        eventually("outbox rows marked published") {
            jdbc
                .sql("SELECT count(*) FROM outbox WHERE event_id IN (:ids) AND published_at IS NOT NULL")
                .param("ids", listOf(scheduled.eventId, cancelled.eventId))
                .query(Int::class.javaObjectType)
                .single() == 2
        }
    }

    /** Reads the topic from the beginning until [count] records with [key] have arrived. */
    private fun receive(
        key: String,
        count: Int,
        timeout: Duration = Duration.ofSeconds(30),
    ): List<ConsumerRecord<String, String>> {
        val properties = Properties().apply { put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest") }
        consumerFactory.createConsumer("test-${UUID.randomUUID()}", null, null, properties).use { consumer ->
            consumer.subscribe(listOf(Topics.PROMOTION_EVENTS))
            val received = mutableListOf<ConsumerRecord<String, String>>()
            val deadline = System.nanoTime() + timeout.toNanos()
            while (received.size < count && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).filter { it.key() == key }.forEach(received::add)
            }
            assertEquals(count, received.size, "records for key $key")
            return received
        }
    }

    private fun eventually(
        what: String,
        timeout: Duration = Duration.ofSeconds(10),
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("timed out waiting for: $what")
    }
}

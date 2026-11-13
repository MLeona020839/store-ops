package dev.storeops.storetask.messaging

import dev.storeops.events.PromotionEvent
import dev.storeops.events.PromotionEventType
import dev.storeops.events.Topics
import dev.storeops.storetask.StoreTaskRepository
import dev.storeops.storetask.TaskAction
import dev.storeops.storetask.TaskStatus
import dev.storeops.storetask.TestcontainersConfiguration
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Properties
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The consumer end to end, against real Kafka and PostgreSQL: messages are sent to the
 * topic the way promotion-service's outbox relay sends them, and the test watches the tasks.
 */
@SpringBootTest(properties = ["messaging.retry.interval=100ms"])
@Import(TestcontainersConfiguration::class, PromotionEventConsumptionTest.SinglePartitionTopic::class)
@Testcontainers(disabledWithoutDocker = true)
class PromotionEventConsumptionTest(
    @Autowired private val kafka: KafkaTemplate<String, String>,
    @Autowired private val consumerFactory: ConsumerFactory<String, String>,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val tasks: StoreTaskRepository,
    @Autowired private val jdbc: JdbcClient,
) {
    /**
     * One partition, so messages are handled in the order they're sent and a "marker" event
     * proves that everything sent before it has been handled.
     */
    @TestConfiguration(proxyBeanMethods = false)
    class SinglePartitionTopic {
        @Bean
        fun promotionEventsTopic() = TopicBuilder.name(Topics.PROMOTION_EVENTS).partitions(1).build()
    }

    @Test
    fun `a scheduled promotion becomes a put-up and a take-down task per store`() {
        val event = scheduled(storeIds = listOf("store-1001", "store-1002"))

        send(event)

        val created = eventually("tasks created") { tasks.findAllByPromotionId(event.promotionId).takeIf { it.size == 4 } }
        assertEquals(setOf("store-1001", "store-1002"), created.map { it.storeId }.toSet())
        assertEquals(
            setOf(TaskAction.PUT_UP_LABELS to event.validFrom, TaskAction.TAKE_DOWN_LABELS to event.validUntil.plusDays(1)),
            created.map { it.action to it.dueDate }.toSet(),
        )
        assertTrue(created.all { it.status == TaskStatus.OPEN && it.products.map { p -> p.productId } == event.productIds })
    }

    @Test
    fun `an event delivered twice is applied once`() {
        val event = scheduled()

        send(event)
        send(event)
        awaitHandledInOrder()

        assertEquals(2, tasks.findAllByPromotionId(event.promotionId).size)
        assertEquals(1, processedCount(event.eventId))
    }

    @Test
    fun `cancelling before the labels went up cancels the store's tasks`() {
        val event = scheduled()
        send(event)
        send(event.copy(eventId = UUID.randomUUID(), type = PromotionEventType.CANCELLED, occurredAt = Instant.now()))

        eventually("tasks cancelled") {
            tasks.findAllByPromotionId(event.promotionId).takeIf { all -> all.size == 2 && all.all { it.status == TaskStatus.CANCELLED } }
        }
    }

    @Test
    fun `a message that can't be read goes to the dead-letter topic and doesn't block the next one`() {
        val key = UUID.randomUUID().toString()
        kafka.send(Topics.PROMOTION_EVENTS, key, "{ not json").get()

        val next = scheduled()
        send(next)

        eventually("next event handled") { tasks.findAllByPromotionId(next.promotionId).takeIf { it.isNotEmpty() } }
        val deadLetter = eventually("message dead-lettered") { readFromStart(MessagingConfiguration.PROMOTION_EVENTS_DLT, key) }
        assertEquals("{ not json", deadLetter)
    }

    @Test
    fun `unknown fields are tolerated and unknown event types are skipped`() {
        // A field added to the contract later must not break this consumer.
        val withNewField = scheduled()
        val json = jsonMapper.writeValueAsString(withNewField).removeSuffix("}") + ""","loyaltyOnly":true}"""
        sendRaw(withNewField.promotionId.toString(), json, PromotionEventType.SCHEDULED.name)

        // A new kind of event is ignored rather than dead-lettered.
        val unknownType = scheduled()
        sendRaw(unknownType.promotionId.toString(), jsonMapper.writeValueAsString(unknownType), "REPRICED")
        awaitHandledInOrder()

        assertEquals(2, tasks.findAllByPromotionId(withNewField.promotionId).size)
        assertEquals(0, tasks.findAllByPromotionId(unknownType.promotionId).size)
    }

    private fun scheduled(storeIds: List<String> = listOf("store-1001")) =
        PromotionEvent(
            eventId = UUID.randomUUID(),
            type = PromotionEventType.SCHEDULED,
            promotionId = UUID.randomUUID(),
            title = "Alle kipfilet",
            label = "25% korting",
            productIds = listOf("product-42", "product-43"),
            storeIds = storeIds,
            validFrom = LocalDate.of(2026, 11, 16),
            validUntil = LocalDate.of(2026, 11, 22),
            occurredAt = Instant.parse("2026-11-11T09:00:00Z"),
        )

    private fun send(event: PromotionEvent) = sendRaw(event.promotionId.toString(), jsonMapper.writeValueAsString(event), event.type.name)

    private fun sendRaw(
        key: String,
        json: String,
        eventType: String,
    ) {
        val header = RecordHeader(PromotionEventListener.EVENT_TYPE_HEADER, eventType.toByteArray())
        kafka.send(ProducerRecord(Topics.PROMOTION_EVENTS, null, key, json, listOf(header))).get()
    }

    /** Sends a marker event and waits for it: with one partition, everything sent earlier is handled too. */
    private fun awaitHandledInOrder() {
        val marker = scheduled()
        send(marker)
        eventually("marker handled") { tasks.findAllByPromotionId(marker.promotionId).takeIf { it.isNotEmpty() } }
    }

    private fun processedCount(eventId: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM processed_event WHERE event_id = :id")
            .param("id", eventId)
            .query(Int::class.javaObjectType)
            .single()

    private fun readFromStart(
        topic: String,
        key: String,
    ): String? {
        val properties = Properties().apply { put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest") }
        consumerFactory.createConsumer("test-${UUID.randomUUID()}", null, null, properties).use { consumer ->
            consumer.subscribe(listOf(topic))
            repeat(10) {
                consumer.poll(Duration.ofMillis(500)).firstOrNull { it.key() == key }?.let { return it.value() }
            }
        }
        return null
    }

    private fun <T : Any> eventually(
        what: String,
        timeout: Duration = Duration.ofSeconds(30),
        probe: () -> T?,
    ): T {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            probe()?.let { return it }
            Thread.sleep(200)
        }
        fail("timed out waiting for: $what")
    }
}

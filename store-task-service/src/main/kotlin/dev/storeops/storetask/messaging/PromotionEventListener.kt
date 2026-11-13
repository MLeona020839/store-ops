package dev.storeops.storetask.messaging

import dev.storeops.events.PromotionEvent
import dev.storeops.events.PromotionEventType
import dev.storeops.events.Topics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

@Component
class PromotionEventListener(
    private val handler: PromotionEventHandler,
    private val jsonMapper: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val knownTypes = PromotionEventType.entries.map { it.name }.toSet()

    /**
     * Values arrive as plain strings and are parsed here, so a message that can't be parsed
     * fails in this method and goes to the dead-letter topic (see MessagingConfiguration)
     * rather than failing inside the Kafka client.
     */
    @KafkaListener(topics = [Topics.PROMOTION_EVENTS])
    fun onMessage(record: ConsumerRecord<String, String>) {
        // Forward compatibility: if promotion-service starts publishing a new kind of event,
        // skip it instead of dead-lettering every one until this service is updated.
        val type =
            record
                .headers()
                .lastHeader(EVENT_TYPE_HEADER)
                ?.value()
                ?.let(::String)
        if (type != null && type !in knownTypes) {
            log.info("Ignoring unknown event type {} at {}-{}@{}", type, record.topic(), record.partition(), record.offset())
            return
        }
        handler.handle(jsonMapper.readValue(record.value(), PromotionEvent::class.java))
    }

    companion object {
        /** Set by promotion-service's outbox relay. */
        const val EVENT_TYPE_HEADER = "event-type"
    }
}

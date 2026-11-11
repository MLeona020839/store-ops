package dev.storeops.promotion.outbox

import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Sends outbox rows to Kafka and marks them published once Kafka has acknowledged them.
 *
 * Delivery is at least once: if the service stops after Kafka acknowledged a message but
 * before the row was marked, the message is sent again on restart. Consumers deduplicate on
 * the event id. The alternative, marking first and sending after, could lose events instead.
 */
@Component
class OutboxRelay(
    private val jdbc: JdbcClient,
    private val kafka: KafkaTemplate<String, String>,
    private val transactions: TransactionTemplate,
    @Value("\${outbox.relay.batch-size:100}") private val batchSize: Int,
    @Value("\${outbox.relay.send-timeout:10s}") private val sendTimeout: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${outbox.relay.interval-ms:500}")
    fun relayPending() {
        try {
            while (relayBatch() == batchSize) {
                // A full batch means there may be more waiting; keep going instead of sleeping.
            }
        } catch (e: Exception) {
            // Nothing was marked published for the failed batch, so it's retried on the next run.
            log.warn("Outbox relay failed, will retry: {}", e.message)
        }
    }

    /** Sends one batch and returns how many rows it published. */
    fun relayBatch(): Int =
        transactions.execute {
            // SKIP LOCKED: if a second instance of this service runs the relay at the same time,
            // it skips rows this one is sending instead of waiting for them or sending them twice.
            val rows =
                jdbc
                    .sql(
                        """
                        SELECT position, event_type, topic, message_key, payload::text AS payload
                        FROM outbox
                        WHERE published_at IS NULL
                        ORDER BY position
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """,
                    ).param("limit", batchSize)
                    .query { rs, _ ->
                        PendingMessage(
                            position = rs.getLong("position"),
                            eventType = rs.getString("event_type"),
                            topic = rs.getString("topic"),
                            key = rs.getString("message_key"),
                            payload = rs.getString("payload"),
                        )
                    }.list()
            if (rows.isEmpty()) return@execute 0

            // Send the whole batch, then wait for every acknowledgement before marking anything.
            rows
                .map { kafka.send(it.toRecord()) }
                .forEach { it.get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS) }

            jdbc
                .sql("UPDATE outbox SET published_at = now() WHERE position IN (:positions)")
                .param("positions", rows.map { it.position })
                .update()
            rows.size
        }

    private data class PendingMessage(
        val position: Long,
        val eventType: String,
        val topic: String,
        val key: String,
        val payload: String,
    ) {
        fun toRecord() = ProducerRecord(topic, null, key, payload, listOf(RecordHeader(EVENT_TYPE_HEADER, eventType.toByteArray())))
    }

    companion object {
        /** Lets consumers route or skip a message without parsing its body. */
        const val EVENT_TYPE_HEADER = "event-type"
    }
}

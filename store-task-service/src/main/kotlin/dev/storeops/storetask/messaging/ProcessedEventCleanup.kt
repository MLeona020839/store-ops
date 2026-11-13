package dev.storeops.storetask.messaging

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Forgets event ids older than [retention], so processed_event doesn't grow forever.
 *
 * The retention has to be longer than any window in which Kafka could deliver the same event
 * again. Forget an id too early and a late duplicate is handled as if it were new. The
 * longest such window is the topic's own retention (7 days by default): once a message is
 * gone from Kafka it can't be redelivered, not even by resetting the consumer's offsets.
 * 30 days leaves a wide margin. If the topic's retention is ever raised, raise this too.
 *
 * A duplicate that slipped through anyway would still do little harm: a second SCHEDULED is
 * stopped by the unique constraint on store_task, and a second CANCELLED changes nothing.
 */
@Component
class ProcessedEventCleanup(
    private val jdbc: JdbcClient,
    private val clock: Clock,
    @Value("\${messaging.processed-events.retention:30d}") private val retention: Duration,
    @Value("\${messaging.processed-events.cleanup-batch-size:1000}") private val batchSize: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${messaging.processed-events.cleanup-interval-ms:3600000}",
        initialDelayString = "\${messaging.processed-events.cleanup-initial-delay-ms:60000}",
    )
    fun scheduledCleanUp() {
        try {
            cleanUp()
        } catch (e: Exception) {
            log.warn("Processed-event cleanup failed, will retry: {}", e.message)
        }
    }

    /** Deletes in batches of short transactions, like OutboxCleanup in promotion-service. Returns the rows deleted. */
    fun cleanUp(): Int {
        val cutoff = Instant.now(clock).minus(retention).atOffset(ZoneOffset.UTC)
        var total = 0
        do {
            val deleted =
                jdbc
                    .sql(
                        """
                        DELETE FROM processed_event WHERE event_id IN (
                            SELECT event_id FROM processed_event
                            WHERE processed_at < :cutoff
                            LIMIT :limit
                        )
                        """,
                    ).param("cutoff", cutoff)
                    .param("limit", batchSize)
                    .update()
            total += deleted
        } while (deleted == batchSize)
        if (total > 0) log.info("Forgot {} event ids processed before {}", total, cutoff)
        return total
    }
}

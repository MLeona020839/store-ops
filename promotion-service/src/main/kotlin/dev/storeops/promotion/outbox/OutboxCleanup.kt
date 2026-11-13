package dev.storeops.promotion.outbox

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
 * Deletes outbox rows that were published longer than [retention] ago. Without it the table
 * only grows: every promotion change adds a row and nothing ever removes it.
 *
 * Published rows are kept for a while rather than deleted straight away, so recent messages
 * can still be inspected (what was sent, when) while investigating a problem.
 * Unpublished rows are never touched, however old: they still have to be sent.
 */
@Component
class OutboxCleanup(
    private val jdbc: JdbcClient,
    private val clock: Clock,
    @Value("\${outbox.cleanup.retention:7d}") private val retention: Duration,
    @Value("\${outbox.cleanup.batch-size:1000}") private val batchSize: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${outbox.cleanup.interval-ms:3600000}",
        initialDelayString = "\${outbox.cleanup.initial-delay-ms:60000}",
    )
    fun scheduledCleanUp() {
        try {
            cleanUp()
        } catch (e: Exception) {
            // Nothing depends on this having run; the next run picks up where this one stopped.
            log.warn("Outbox cleanup failed, will retry: {}", e.message)
        }
    }

    /**
     * Deletes in batches, each its own statement and so its own short transaction, so a large
     * backlog never holds locks on many rows at once. Running on several instances at the
     * same time is harmless: a row can only be deleted once. Returns the number of rows deleted.
     */
    fun cleanUp(): Int {
        val cutoff = Instant.now(clock).minus(retention).atOffset(ZoneOffset.UTC)
        var total = 0
        do {
            // No index on published_at: once cleanup runs regularly the table holds only about
            // [retention] worth of rows, so scanning it is cheap.
            val deleted =
                jdbc
                    .sql(
                        """
                        DELETE FROM outbox WHERE position IN (
                            SELECT position FROM outbox
                            WHERE published_at < :cutoff
                            ORDER BY position
                            LIMIT :limit
                        )
                        """,
                    ).param("cutoff", cutoff)
                    .param("limit", batchSize)
                    .update()
            total += deleted
        } while (deleted == batchSize)
        if (total > 0) log.info("Deleted {} outbox rows published before {}", total, cutoff)
        return total
    }
}

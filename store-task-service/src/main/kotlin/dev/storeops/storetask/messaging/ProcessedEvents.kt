package dev.storeops.storetask.messaging

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Remembers which events were handled, so a redelivered event is recognised and skipped. */
@Component
class ProcessedEvents(
    private val jdbc: JdbcClient,
) {
    /**
     * Returns true the first time an event id is seen, false for a duplicate.
     *
     * Must share the transaction of the event's effects (hence MANDATORY): if handling fails
     * and rolls back, the id is rolled back too and the retry isn't mistaken for a duplicate.
     * Two consumers racing on the same id is safe as well: the second INSERT waits for the
     * first transaction, then does nothing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun markProcessed(eventId: UUID): Boolean =
        jdbc
            .sql("INSERT INTO processed_event (event_id) VALUES (:id) ON CONFLICT (event_id) DO NOTHING")
            .param("id", eventId)
            .update() == 1
}

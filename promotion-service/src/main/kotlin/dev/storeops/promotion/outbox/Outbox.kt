package dev.storeops.promotion.outbox

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Records events to be published. See V2__create_outbox.sql for why this is a table. */
@Component
class Outbox(
    private val jdbc: JdbcClient,
    private val jsonMapper: JsonMapper,
) {
    /**
     * MANDATORY: throws if there's no surrounding transaction. An outbox write on its own
     * transaction would defeat the purpose, so make that mistake impossible rather than silent.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun add(
        eventId: UUID,
        eventType: String,
        topic: String,
        key: String,
        event: Any,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO outbox (event_id, event_type, topic, message_key, payload)
                VALUES (:eventId, :eventType, :topic, :key, CAST(:payload AS jsonb))
                """,
            ).param("eventId", eventId)
            .param("eventType", eventType)
            .param("topic", topic)
            .param("key", key)
            .param("payload", jsonMapper.writeValueAsString(event))
            .update()
    }
}

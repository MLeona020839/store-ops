-- Transactional outbox: events are written here in the same transaction as the change they
-- announce, then OutboxRelay sends them to Kafka. Either both the change and its event are
-- committed or neither is; there's no window where one exists without the other.

CREATE TABLE outbox (
    -- Insertion order. A sequence rather than created_at, which is the same for every row
    -- written in one transaction (now() is the transaction's start time).
    position      bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id      uuid         NOT NULL UNIQUE,  -- the eventId consumers deduplicate on
    event_type    text         NOT NULL,
    topic         text         NOT NULL,
    message_key   text         NOT NULL,         -- Kafka key: the promotion id, so one promotion's events stay in order
    payload       jsonb        NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    published_at  timestamptz                    -- NULL until Kafka has acknowledged the message
);

-- The relay only ever looks for unpublished rows, oldest first; this index stays small
-- because published rows drop out of it.
CREATE INDEX outbox_unpublished ON outbox (position) WHERE published_at IS NULL;

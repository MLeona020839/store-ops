-- Shelf-label work for store employees, derived from promotion events.
-- Table and column names follow Spring Data JDBC's defaults, like in promotion-service.

CREATE TABLE store_task (
    id            uuid         PRIMARY KEY,
    promotion_id  uuid         NOT NULL,  -- promotion-service's id; no foreign key, it's another service's data
    store_id      text         NOT NULL,
    action        text         NOT NULL,
    title         text         NOT NULL,  -- copied from the event so a task reads on its own
    label         text         NOT NULL,
    due_date      date         NOT NULL,
    status        text         NOT NULL DEFAULT 'OPEN',
    completed_at  timestamptz,
    version       integer      NOT NULL DEFAULT 0,  -- optimistic locking (@Version)
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT store_task_action_valid CHECK (action IN ('PUT_UP_LABELS', 'TAKE_DOWN_LABELS')),
    CONSTRAINT store_task_status_valid CHECK (status IN ('OPEN', 'DONE', 'CANCELLED')),
    -- A task is done exactly when it has a completion time.
    CONSTRAINT store_task_completed_when_done CHECK ((status = 'DONE') = (completed_at IS NOT NULL)),
    -- One task of each kind per promotion and store, however often an event is delivered.
    CONSTRAINT store_task_one_per_action UNIQUE (promotion_id, store_id, action)
);

-- "What does my store need to do?": open tasks for one store by due date.
CREATE INDEX store_task_open_by_store ON store_task (store_id, due_date) WHERE status = 'OPEN';

CREATE TABLE store_task_product (
    store_task_id  uuid  NOT NULL REFERENCES store_task (id) ON DELETE CASCADE,
    product_id     text  NOT NULL,
    PRIMARY KEY (store_task_id, product_id)
);

-- Idempotent consumer: an event's id is recorded in the same transaction as its effects,
-- so a redelivered event finds its id here and is skipped.
CREATE TABLE processed_event (
    event_id      uuid         PRIMARY KEY,
    processed_at  timestamptz  NOT NULL DEFAULT now()
);

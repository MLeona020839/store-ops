# store-ops

[![CI](https://github.com/MLeona020839/store-ops/actions/workflows/ci.yml/badge.svg)](https://github.com/MLeona020839/store-ops/actions/workflows/ci.yml)

Event-driven Kotlin microservices that turn weekly supermarket promotions into shelf-label tasks for store employees.

## The problem

Every week, supermarket store employees replace shelf labels for new promotions. It's easy to miss a label or put it up late, and then the price on the shelf doesn't match the price at the checkout, which leads to customer complaints.

store-ops gives each store a daily list of the labels to change: which labels go up when a promotion starts, and which come down when it ends.

## Architecture

```mermaid
flowchart LR
    subgraph promotion-service
        P[REST API<br/>manage promotions] --> PDB[(promotions DB)]
    end
    subgraph store-task-service
        C[Kafka consumer] --> TDB[(store_tasks DB)]
        T[REST API<br/>tasks per store] --> TDB
    end
    P -- PromotionEvent --> K{{Kafka<br/>promotion-events}}
    K --> C
    E([Store employee]) --> T
```

| Module | Responsibility | Port |
|---|---|---|
| `events` | Shared event contracts (`PromotionEvent`), no framework dependencies | – |
| `promotion-service` | Stores promotions and publishes an event when one is scheduled or cancelled | 8081 |
| `store-task-service` | Consumes promotion events and turns them into label-change tasks per store | 8082 |

Each service owns its own database; they communicate only through Kafka.

## Tech stack

Kotlin 2.3 · Spring Boot 4 · Spring Security (OAuth2 resource server) · Spring Data JDBC · Flyway · PostgreSQL 18 · Apache Kafka 4 (KRaft) ·
JUnit 5 · Testcontainers · ktlint · Docker · GitHub Actions

## Running locally

Requirements: JDK 21 and Docker.

```bash
./scripts/dev-jwt.sh keys                     # once: a local key pair for signing development tokens
docker compose up -d                          # PostgreSQL + Kafka
./gradlew :promotion-service:bootRun --args='--spring.profiles.active=dev'    # http://localhost:8081
./gradlew :store-task-service:bootRun --args='--spring.profiles.active=dev'   # http://localhost:8082
```

The `dev` profile makes the services trust tokens signed with that local key. The private key stays
in `.dev-keys/` (git-ignored); the services only ever see the public key.

Health checks: `curl localhost:8081/actuator/health`

Or run the whole system in containers, then check the full flow:

```bash
./scripts/dev-jwt.sh keys                     # once, as above
docker compose --profile apps up -d --build   # builds both images from the shared Dockerfile
./scripts/smoke-test.sh                       # create -> tasks appear; edit (add a store) -> its tasks appear; cancel -> cleared
```

CI runs the same smoke test on every push, after the tests pass.

Alternatively, run `TestPromotionServiceApplication` / `TestStoreTaskServiceApplication`
from your IDE: they start the service against throwaway Testcontainers, no compose needed.
They trust a throwaway signing key, though, so for calling the API use the `dev` profile above.

## API

Both services are OAuth2 resource servers: every request needs a bearer token (JWT). In a real
environment an identity provider issues them; locally, `scripts/dev-jwt.sh` does. Health and info
endpoints stay open for the platform's probes. A missing or invalid token is a `401`, a valid one
without the right access a `403`.

`promotion-service`: reading needs the scope `promotions:read`, creating and cancelling `promotions:write`.

| Method | Path | |
|---|---|---|
| `POST` | `/promotions` | Create a promotion; `201` with a `Location` header |
| `GET` | `/promotions/{id}` | One promotion |
| `GET` | `/promotions?from=2026-11-16` | Running on that day or starting later, soonest first; `from` defaults to today (Europe/Amsterdam) |
| `PUT` | `/promotions/{id}` | Change a promotion; needs `If-Match` with the `ETag` from the last read (see below) |
| `POST` | `/promotions/{id}/cancel` | Cancel; idempotent |

```bash
TOKEN=$(./scripts/dev-jwt.sh token '"scope":"promotions:read promotions:write"')
curl -i localhost:8081/promotions -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{
  "title": "Alle kipfilet", "label": "25% korting", "kind": "PERCENT_OFF", "percent": 25,
  "validFrom": "2026-11-16", "validUntil": "2026-11-22",
  "storeIds": ["store-1001", "store-1002"], "productIds": ["product-42"]
}'
```

Responses for a single promotion carry its version as an `ETag`. A `PUT` must send it back in
`If-Match`: without it the answer is `428`, and if someone changed the promotion since your read, `412`,
so a change based on an old read can't silently overwrite a newer one. Once a promotion has started,
only its end date and stores can change (`409` otherwise): the labels are already on the shelves, so
anything else means cancelling it and creating a new one.

Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details (`application/problem+json`):
`400` for invalid input or a broken business rule (with the reason in `detail`), `404` for an unknown id,
`409` when the promotion was changed concurrently.

`store-task-service`: an employee's token carries their store as a `store_id` claim, and every path must be for that store
(otherwise `403`). Within their store, a task id from another store is a `404`.

| Method | Path | |
|---|---|---|
| `GET` | `/stores/{storeId}/tasks?until=2026-11-16` | Open tasks due by that day, overdue included, earliest first; `until` defaults to today |
| `GET` | `/stores/{storeId}/tasks/{taskId}` | One task |
| `POST` | `/stores/{storeId}/tasks/{taskId}/complete` | Mark done; idempotent. `409` if the task was cancelled |

```bash
TOKEN=$(./scripts/dev-jwt.sh token '"store_id":"store-1001"')
curl -s localhost:8082/stores/store-1001/tasks -H "Authorization: Bearer $TOKEN"
```

## Testing

```bash
./gradlew build        # ktlint + unit tests + integration tests
./gradlew ktlintFormat # auto-fix formatting
```

Integration tests use Testcontainers with real Kafka and PostgreSQL. They are skipped
automatically when Docker isn't available and always run in CI.

## Design decisions

### Transactional outbox (promotion-service)

promotion-service never sends to Kafka directly. When a promotion is created or cancelled, its event is written to an `outbox` table **in the same database transaction** as the change. `OutboxRelay` then sends pending rows to Kafka and marks them published once Kafka has acknowledged them.

- **Instead of:** saving the promotion, then sending the event. If the service crashes between the two steps, the promotion exists but the event is lost. Sending first has the opposite problem: stores get tasks for a promotion that was never saved.
- **Trade-off:** delivery is *at least once*. If the relay crashes after Kafka acknowledged a message but before marking the row, the message is sent again on restart. The consumer has to handle duplicates (next decision). Losing an event can't be fixed afterwards; a duplicate can.
- **Details:** rows are ordered by a sequence, not by `created_at` (which is the same for everything in one transaction). `FOR UPDATE SKIP LOCKED` lets several instances run the relay without sending the same rows twice. Messages are keyed by promotion id, so one promotion's events stay in order on one partition.

### Idempotent consumer (store-task-service)

Every event carries an `eventId`. store-task-service records it in a `processed_event` table in the same transaction as the task changes; a redelivered event finds its id and is skipped. A unique constraint on (promotion, store, action) is a second line of defence.

- **Instead of:** Kafka's exactly-once transactions. They cover reading from and writing to Kafka, not the writes to PostgreSQL that matter here.
- **Related:** due dates come from the event's `occurredAt`, never from the clock, so handling an event again gives the same result as the first time.

### Dead-letter topic

Transient failures (database briefly unavailable, a concurrent update) are retried three times, in place, so the partition keeps its order. After that, and immediately for messages that can't be parsed, the message goes to `promotion-events-dlt` with the error in its headers, and the consumer moves on.

- **Instead of:** retrying forever, where one bad message blocks every promotion behind it on its partition; or logging and skipping, where it's silently lost.
- **Compatibility:** unknown fields in an event are ignored, and unknown event types are skipped rather than dead-lettered, so promotion-service can add to the contract without breaking this consumer first. All of this is covered by integration tests against real Kafka.

### Changing a promotion: rules on one side, reconciliation on the other

An update publishes an `UPDATED` event that carries the promotion's **complete new state**, not what
changed. store-task-service compares it with the tasks it has: added stores get tasks, removed stores
are handled like a cancellation for that store, open tasks follow the new dates and label, and done
tasks are left alone.

- **Instead of:** an event per kind of change (`STORES_ADDED`, `DATES_CHANGED`, ...). That needs a new
  event type and consumer code for every new kind of edit, and a consumer that missed one event is out
  of step for good. With full state, each event is enough on its own.
- **Rules where the knowledge is:** promotion-service refuses changes stores can't follow, such as a new
  label on a promotion that's already on the shelves. So the consumer never has to invent a "relabel"
  task for a case that can't happen.
- **Evolving the contract:** `UPDATED` is a new event type. Consumers skip types they don't know (by the
  `event-type` header), so the safe order is to deploy store-task-service first, then promotion-service.
  Deployed the other way round, updates would be skipped, not crash anything.

### A database per service

Each service owns its own database. They share nothing but the event contract in the `events` module, which has no framework dependencies.

- **Instead of:** one shared database, which makes joins easy but couples each service to the other's schema: neither can change its tables or be deployed on its own.
- **Consequence:** store-task-service copies what it needs (title, label, products) from the events into its tasks, and ids owned by other systems are stored without foreign keys.

### Optimistic locking

Both aggregates carry a `@Version`. When two writes race, for example an employee completing a task while a cancellation event for the same promotion is being handled, the second write fails instead of silently overwriting the first.

- In the API that becomes a `409 Conflict`; the client reloads and sees the current state.
- In the consumer it's a transient failure: the retry reads the task again and applies the cancellation to what's there now. If the labels were already put up, the take-down is brought forward to the cancellation day.
- **Instead of:** pessimistic locks (`SELECT ... FOR UPDATE`) held for the whole request. Conflicts here are rare, so paying for a lock on every write isn't worth it.

### Access control in the filter chain

Store employees may only see and complete their own store's tasks. That check sits in the security
filter chain, matched against `/stores/{storeId}/**`, rather than in each controller method, so a new
endpoint under that path can't forget it. Anything not explicitly allowed is denied, so a new endpoint
elsewhere starts closed. The services only verify tokens; which issuer or key to trust is configuration,
so the same code works with a local development key and with a real identity provider.

- **Not done yet:** head-office users who may see every store, and checking the token's issuer and
  audience, which comes with configuring a real identity provider.

### Rules in the database as well as in code

The domain classes check their rules (dates in order, the fields each promotion kind needs), and the same rules exist as `CHECK` constraints in the Flyway migrations. Code gives clear error messages; the database is the last line of defence for anything that bypasses the code. One detail the tests pin down: a `CHECK` whose result is `NULL` *passes*, so the constraints test `IS NOT NULL` explicitly.

## How I used AI tools

I built this project with [Claude Code](https://claude.com/claude-code), Anthropic's coding agent, and I want to be upfront about it: **Claude Code wrote most of the code, tests and configuration.** I chose the role I'm applying for and the domain (it builds on my earlier project, grocery-agent, which compares supermarket promotions), set the order of the work, and approved every change before it was committed.

### What it got wrong, and how that was caught

- **A Kafka setting that would never have worked.** The first `docker-compose.yml` gave Kafka a plain-text `CLUSTER_ID`; Kafka requires a base64-encoded UUID. It was caught before running, on a second read of the config.
- **A failure that only showed up in CI.** `docker compose` builds both images in parallel, and both Gradle builds shared one cache, so one timed out waiting for the other's lock. The CI smoke test failed, the logs showed why, and the fix was one line (`sharing=locked`).
- **A naming mismatch between SQL and Kotlin.** The first migration stored promotion kinds in lower case, but Kotlin enums are stored by their upper-case names. It was caught while writing the entity, before anything was deployed, so the migration could still be changed.
- **A confident statement that wasn't true.** In one summary it said a Docker change was still pending when there wasn't one. It corrected itself in the next message, but it's a reminder to check claims against the code, not to take them as given.

### How I kept it honest

- **Nothing counts as done until CI says so.** My machine has no Docker, so the integration tests (Testcontainers with real PostgreSQL and Kafka) and the smoke test only run in CI. Each step was pushed and only treated as finished once those tests had actually run and passed.
- **Versions from the source, not from memory.** Library, plugin and image versions were looked up from start.spring.io, Maven Central, the Gradle plugin portal and Docker Hub rather than guessed.
- **Tests at every level:** unit tests for the rules, slice tests for HTTP and SQL, integration tests against real infrastructure, and a full-flow smoke test against the containerised system.
- **[CLAUDE.md](CLAUDE.md)** records the project's conventions (commands, module boundaries, migration rules), so each session starts from the same rules.

## Roadmap

- [x] Promotion API: create, get, list, cancel, with validation (`promotion-service`)
- [x] Update a promotion (`PUT` with optimistic locking through `If-Match`; `UPDATED` events reconciled into store tasks)
- [x] Flyway migration for `promotion-service`
- [x] Flyway migration for `store-task-service`
- [x] Publish `PromotionEvent` on schedule / cancel
- [x] Consume events and create tasks per store (`store-task-service`)
- [x] Task API: list open tasks for a store, mark a task done
- [x] Authentication: JWT resource servers; promotion scopes; employees only see and complete their own store's tasks
- [x] Idempotent consumer + dead-letter topic
- [x] Transactional outbox in `promotion-service`
- [x] Clean up old rows: published outbox messages after 7 days, processed event ids after 30 days
- [x] Full-flow smoke test over HTTP against the containerised system (CI)
- [x] Dockerfile for both services (shared, layered, non-root)
- [ ] (Optional) Deploy to Azure Container Apps

# store-ops

Two Kotlin/Spring Boot microservices connected through Kafka. See README.md for the architecture.

## Commands

- `./gradlew build` – ktlint, unit and integration tests; must pass before any commit
- `./gradlew ktlintFormat` – fix formatting
- `./gradlew :<module>:test --tests '<Class>'` – run one test class
- `docker compose up -d` – local PostgreSQL and Kafka
- `docker compose --profile apps up -d --build` then `./scripts/smoke-test.sh` – whole system in containers, full-flow check
- Testcontainers tests skip when Docker isn't available (the owner's machine has none); CI runs them, so a change isn't verified until CI has passed

## Conventions

- Modules: `events` (shared contracts, no Spring), `promotion-service`, `store-task-service`
- Services never share a database or call each other over HTTP; they communicate only through events in `events`
- Event contracts stay backwards compatible: add fields, never rename or remove them
- Persistence: Spring Data JDBC with immutable data classes; schema changes go through Flyway migrations only. Never edit a migration that's already in main; add a new one
- Configuration in `application.properties`; secrets come from environment variables, never committed
- Every new behaviour gets a test. Kafka or database behaviour gets a Testcontainers test, not a mock
- Pin Docker image versions; keep docker-compose.yml and TestcontainersConfiguration on the same versions

## Working with me

- This is a portfolio project for job applications, built mostly by Claude Code (README, "How I used AI tools"). The owner has to be able to explain every decision in an interview: explain trade-offs in plain terms, and say honestly what is and isn't verified
- Prefer small, focused changes with a clear commit message
- Commit author email is the GitHub noreply address (set in this repo's git config); keep it that way

# store-ops

Two Kotlin/Spring Boot microservices connected through Kafka. See README.md for the architecture.

## Commands

- `./gradlew build` – ktlint, unit and integration tests; must pass before any commit
- `./gradlew ktlintFormat` – fix formatting
- `./gradlew :<module>:test --tests '<Class>'` – run one test class
- `docker compose up -d` – local PostgreSQL and Kafka

## Conventions

- Modules: `events` (shared contracts, no Spring), `promotion-service`, `store-task-service`
- Services never share a database or call each other over HTTP; they communicate only through events in `events`
- Event contracts stay backwards compatible: add fields, never rename or remove them
- Persistence: Spring Data JDBC with immutable data classes; schema changes go through Flyway migrations only. Nothing is deployed yet, so V1 could still be edited; from the first deployment on, never edit an applied migration, add a new one
- Configuration in `application.properties`; secrets come from environment variables, never committed
- Every new behaviour gets a test. Kafka or database behaviour gets a Testcontainers test, not a mock
- Pin Docker image versions; keep docker-compose.yml and TestcontainersConfiguration on the same versions

## Working with me

- This is a portfolio project: the owner writes the domain logic. Explain trade-offs and review code; don't write whole features unless asked
- Prefer small, focused changes with a clear commit message

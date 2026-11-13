# syntax=docker/dockerfile:1
#
# One Dockerfile for both services; pick one with --build-arg:
#   docker build --build-arg SERVICE=promotion-service  -t store-ops/promotion-service .
#   docker build --build-arg SERVICE=store-task-service -t store-ops/store-task-service .
# The build context is the repository root, because both services depend on the events module.

ARG SERVICE

# ---- build: compile the service into an executable jar -----------------------------------
FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
ARG SERVICE
WORKDIR /workspace

# Build scripts first: they change less often than the sources, so this layer stays cached.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY events/build.gradle.kts events/
COPY promotion-service/build.gradle.kts promotion-service/
COPY store-task-service/build.gradle.kts store-task-service/
COPY events/src events/src
COPY ${SERVICE}/src ${SERVICE}/src

# The cache mount keeps Gradle's downloads between builds without putting them in the image.
# Tests run in CI before images are built, so they're skipped here.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :${SERVICE}:bootJar -x test \
 && cp "$(find ${SERVICE}/build/libs -name '*.jar' ! -name '*-plain.jar')" application.jar

# Split the jar into layers, from least to most often changed: dependencies, Spring Boot's
# loader, snapshot dependencies, the application. A code change only rebuilds the last one.
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- runtime: just a JRE and the layers ----------------------------------------------------
FROM eclipse-temurin:21.0.13_9-jre-noble
WORKDIR /app

# Run as an unprivileged user, not root.
RUN groupadd --system app && useradd --system --gid app --no-create-home app

COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER app

# Size the heap from the container's memory limit, and restart (via the orchestrator)
# rather than limp on after running out of memory.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# No HEALTHCHECK: the orchestrator probes /actuator/health/liveness and /readiness instead
# (enabled with management.endpoint.health.probes.enabled).
ENTRYPOINT ["java", "-jar", "application.jar"]

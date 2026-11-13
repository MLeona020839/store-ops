package dev.storeops.storetask.messaging

import dev.storeops.events.Topics
import org.apache.kafka.common.TopicPartition
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.util.backoff.FixedBackOff
import tools.jackson.core.JacksonException
import java.time.Duration

/** @EnableScheduling runs ProcessedEventCleanup. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class MessagingConfiguration {
    /**
     * What happens when handling a message throws. Spring Boot plugs this bean into the
     * listener container.
     *
     * - Transient failures (database briefly unavailable, a lock timeout) are retried a few
     *   times, in place, so the partition's order is kept.
     * - If it still fails, or it can never succeed (the JSON doesn't parse or breaks the
     *   event's rules), the message goes to the dead-letter topic with the error in its
     *   headers, and the partition moves on. One bad message mustn't block every promotion
     *   behind it. Dead-lettered messages can be inspected and replayed once fixed.
     */
    @Bean
    fun kafkaErrorHandler(
        template: KafkaTemplate<Any, Any>,
        @Value("\${messaging.retry.interval:1s}") retryInterval: Duration,
        @Value("\${messaging.retry.attempts:3}") retryAttempts: Long,
    ): DefaultErrorHandler =
        DefaultErrorHandler(
            // Named explicitly instead of relying on the library's default suffix; same partition number as the original.
            DeadLetterPublishingRecoverer(template) { record, _ -> TopicPartition(PROMOTION_EVENTS_DLT, record.partition()) },
            FixedBackOff(retryInterval.toMillis(), retryAttempts),
        ).apply {
            // Retrying a message that can't be parsed only delays the dead-lettering.
            addNotRetryableExceptions(JacksonException::class.java)
        }

    /** At least as many partitions as promotion-events, since dead letters keep their partition number. */
    @Bean
    fun promotionEventsDeadLetterTopic() =
        TopicBuilder
            .name(PROMOTION_EVENTS_DLT)
            .partitions(3)
            .replicas(1)
            .build()

    companion object {
        const val PROMOTION_EVENTS_DLT = "${Topics.PROMOTION_EVENTS}-dlt"
    }
}

package dev.storeops.promotion.outbox

import dev.storeops.events.Topics
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class MessagingConfiguration {
    /**
     * Created on startup if missing. Three partitions let store-task-service run up to three
     * consumers in parallel; messages with the same key (promotion id) always share a partition,
     * so each promotion's events still arrive in order. In a real cluster the replication factor
     * would be at least 3, set by whoever runs Kafka rather than by the service.
     */
    @Bean
    fun promotionEventsTopic() =
        TopicBuilder
            .name(Topics.PROMOTION_EVENTS)
            .partitions(3)
            .replicas(1)
            .build()
}

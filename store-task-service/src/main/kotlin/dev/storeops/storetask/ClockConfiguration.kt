package dev.storeops.storetask

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class ClockConfiguration {
    /** "Today" for a store is the Dutch calendar day; injected so tests can fix it. */
    @Bean
    fun clock(): Clock = Clock.system(StoreTaskPlanning.STORE_ZONE)
}

package dev.storeops.promotion

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

@Configuration(proxyBeanMethods = false)
class ClockConfiguration {
    /**
     * Promotion dates are calendar days in the stores' time zone, not UTC: at 00:30 on Monday
     * in Zaandam it's still Sunday in UTC. Injecting the clock also lets tests fix "today".
     */
    @Bean
    fun clock(): Clock = Clock.system(ZoneId.of("Europe/Amsterdam"))
}

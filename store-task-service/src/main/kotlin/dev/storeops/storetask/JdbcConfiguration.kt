package dev.storeops.storetask

import org.springframework.context.annotation.Configuration
import org.springframework.data.jdbc.repository.config.EnableJdbcAuditing

/** Fills @CreatedDate and @LastModifiedDate on save; kept out of the application class (see promotion-service). */
@Configuration(proxyBeanMethods = false)
@EnableJdbcAuditing
class JdbcConfiguration

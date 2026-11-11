package dev.storeops.promotion

import org.springframework.context.annotation.Configuration
import org.springframework.data.jdbc.repository.config.EnableJdbcAuditing

/**
 * Fills @CreatedDate and @LastModifiedDate on save. Kept out of the application class so that
 * test slices without JDBC (e.g. @WebMvcTest) don't try to set up auditing.
 */
@Configuration(proxyBeanMethods = false)
@EnableJdbcAuditing
class JdbcConfiguration

package dev.storeops.promotion

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

/**
 * An OAuth2 resource server: every request carries a JWT issued by the organisation's
 * identity provider, and this service only verifies it. Which key or issuer is trusted is
 * configuration (see application-dev.properties and docker-compose.yml), not code.
 *
 * Scopes come from the token's `scope` claim, which Spring Security turns into
 * SCOPE_... authorities.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                // Liveness and readiness probes come from the platform, without a token.
                authorize("/actuator/health/**", permitAll)
                authorize("/actuator/info", permitAll)
                authorize(HttpMethod.GET, "/promotions/**", hasAuthority("SCOPE_promotions:read"))
                authorize(HttpMethod.POST, "/promotions/**", hasAuthority("SCOPE_promotions:write"))
                authorize(HttpMethod.PUT, "/promotions/**", hasAuthority("SCOPE_promotions:write"))
                // Anything not listed above is refused, so a new endpoint is closed until someone opens it.
                authorize(anyRequest, denyAll)
            }
            oauth2ResourceServer { jwt { } }
            // Bearer tokens aren't sent by the browser on its own the way cookies are, so there's
            // no cross-site request to forge: CSRF protection and sessions aren't needed.
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
        }
        return http.build()
    }
}

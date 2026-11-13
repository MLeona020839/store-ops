package dev.storeops.storetask

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.authorization.AuthorizationResult
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import java.util.function.Supplier

/**
 * An OAuth2 resource server, like promotion-service. Store employees' tokens carry the store
 * they work in as a `store_id` claim, and every task path is checked against it here, in the
 * filter chain, so a new endpoint under /stores/{storeId} can't forget the check.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize("/actuator/health/**", permitAll)
                authorize("/actuator/info", permitAll)
                authorize("/stores/{storeId}/**", OwnStoreOnly)
                authorize(anyRequest, denyAll)
            }
            oauth2ResourceServer { jwt { } }
            // Bearer tokens, no cookies: nothing for CSRF to exploit, no session to keep.
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
        }
        return http.build()
    }

    /**
     * Grants access when the token's `store_id` equals the {storeId} in the path. A valid token
     * for another store gets 403. A store that's allowed but a task id from another store is
     * still a 404, from StoreTaskService.
     */
    object OwnStoreOnly : AuthorizationManager<RequestAuthorizationContext> {
        const val STORE_CLAIM = "store_id"

        override fun authorize(
            authentication: Supplier<out Authentication>,
            context: RequestAuthorizationContext,
        ): AuthorizationResult {
            val token = authentication.get() as? JwtAuthenticationToken ?: return AuthorizationDecision(false)
            val tokenStore = token.token.getClaimAsString(STORE_CLAIM)
            return AuthorizationDecision(tokenStore != null && tokenStore == context.variables["storeId"])
        }
    }
}

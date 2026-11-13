package dev.storeops.promotion

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

/**
 * Real Kafka and PostgreSQL for integration tests, image versions as in docker-compose.yml,
 * plus a JWT decoder so the security configuration can start without real key configuration.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    @ServiceConnection
    fun kafkaContainer(): KafkaContainer = KafkaContainer(DockerImageName.parse("apache/kafka-native:4.3.2"))

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

    /** Trusts a throwaway key made for this test run; nothing outside the tests can sign for it. */
    @Bean
    fun jwtDecoder(): JwtDecoder {
        val key =
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
                .public as RSAPublicKey
        return NimbusJwtDecoder.withPublicKey(key).build()
    }
}

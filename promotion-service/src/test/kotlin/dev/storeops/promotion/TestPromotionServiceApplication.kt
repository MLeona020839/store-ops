package dev.storeops.promotion

import org.springframework.boot.fromApplication
import org.springframework.boot.with

/** Runs the service locally against throwaway Testcontainers instead of docker compose. */
fun main(args: Array<String>) {
    fromApplication<PromotionServiceApplication>().with(TestcontainersConfiguration::class).run(*args)
}

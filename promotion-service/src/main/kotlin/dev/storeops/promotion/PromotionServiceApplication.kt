package dev.storeops.promotion

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class PromotionServiceApplication

fun main(args: Array<String>) {
    runApplication<PromotionServiceApplication>(*args)
}

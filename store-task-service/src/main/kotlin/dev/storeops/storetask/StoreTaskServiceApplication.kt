package dev.storeops.storetask

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class StoreTaskServiceApplication

fun main(args: Array<String>) {
    runApplication<StoreTaskServiceApplication>(*args)
}

package dev.storeops.storetask

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Every path is scoped to one store. There's no authentication yet; once there is, this is
 * where an employee's token would be checked against {storeId}.
 */
@RestController
@RequestMapping("/stores/{storeId}/tasks")
class StoreTaskController(
    private val service: StoreTaskService,
) {
    /** `GET /stores/store-1001/tasks?until=2026-11-16`: open work due by that day, overdue included. */
    @GetMapping
    fun openTasks(
        @PathVariable @NotBlank @Size(max = 64) storeId: String,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) until: LocalDate?,
    ): List<StoreTaskResponse> = service.openTasks(storeId, until).map { it.toResponse() }

    @GetMapping("/{taskId}")
    fun get(
        @PathVariable @NotBlank @Size(max = 64) storeId: String,
        @PathVariable taskId: UUID,
    ): StoreTaskResponse = service.get(storeId, taskId).toResponse()

    /** An action rather than a PATCH of `status`: completing is the only change an employee makes. */
    @PostMapping("/{taskId}/complete")
    fun complete(
        @PathVariable @NotBlank @Size(max = 64) storeId: String,
        @PathVariable taskId: UUID,
    ): StoreTaskResponse = service.complete(storeId, taskId).toResponse()
}

data class StoreTaskResponse(
    val id: UUID,
    val promotionId: UUID,
    val storeId: String,
    val action: TaskAction,
    val title: String,
    val label: String,
    val productIds: List<String>,
    val dueDate: LocalDate,
    val status: TaskStatus,
    val completedAt: Instant?,
)

fun StoreTask.toResponse() =
    StoreTaskResponse(
        id = id,
        promotionId = promotionId,
        storeId = storeId,
        action = action,
        title = title,
        label = label,
        productIds = products.map { it.productId }.sorted(),
        dueDate = dueDate,
        status = status,
        completedAt = completedAt,
    )

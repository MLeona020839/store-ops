package dev.storeops.promotion

import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/promotions")
class PromotionController(
    private val service: PromotionService,
) {
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreatePromotionRequest,
    ): ResponseEntity<PromotionResponse> {
        val created = service.create(request.toPromotion())
        val location =
            ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id)
                .toUri()
        return ResponseEntity.created(location).body(created.toResponse())
    }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): PromotionResponse = service.get(id).toResponse()

    /** `GET /promotions?from=2026-11-16`: running on that day or later. Defaults to today. */
    @GetMapping
    fun listCurrentAndUpcoming(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
    ): List<PromotionResponse> = service.listCurrentAndUpcoming(from).map { it.toResponse() }

    /**
     * Cancelling is its own action rather than a PATCH of `status`: it's a business event that
     * store-task-service will hear about, and the only allowed status change.
     */
    @PostMapping("/{id}/cancel")
    fun cancel(
        @PathVariable id: UUID,
    ): PromotionResponse = service.cancel(id).toResponse()
}

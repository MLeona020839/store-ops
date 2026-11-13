package dev.storeops.promotion

import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.time.LocalDate
import java.util.UUID

/**
 * Responses about a single promotion carry its version as an ETag. A client that wants to
 * change the promotion sends that ETag back in If-Match, so a change based on an old read is
 * refused (412) instead of silently overwriting someone else's (optimistic locking over HTTP).
 */
@RestController
@RequestMapping("/promotions")
class PromotionController(
    private val service: PromotionService,
) {
    @PostMapping
    fun create(
        @Valid @RequestBody request: PromotionRequest,
    ): ResponseEntity<PromotionResponse> {
        val created = service.create(request.toPromotion())
        val location =
            ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id)
                .toUri()
        return ResponseEntity.created(location).withETagOf(created)
    }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<PromotionResponse> = ResponseEntity.ok().withETagOf(service.get(id))

    /** `GET /promotions?from=2026-11-16`: running on that day or later. Defaults to today. */
    @GetMapping
    fun listCurrentAndUpcoming(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
    ): List<PromotionResponse> = service.listCurrentAndUpcoming(from).map { it.toResponse() }

    /**
     * Replaces the promotion's terms. Requires `If-Match` with the ETag from the last read:
     * missing or not a version is 428, an outdated version 412.
     */
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: PromotionRequest,
    ): ResponseEntity<PromotionResponse> {
        val expectedVersion = ifMatch?.let(::versionFromETag) ?: throw IfMatchRequiredException()
        return ResponseEntity.ok().withETagOf(service.update(id, expectedVersion, request.toPromotion()))
    }

    /**
     * Cancelling is its own action rather than a PATCH of `status`: it's a business event that
     * store-task-service will hear about, and the only allowed status change.
     */
    @PostMapping("/{id}/cancel")
    fun cancel(
        @PathVariable id: UUID,
    ): ResponseEntity<PromotionResponse> = ResponseEntity.ok().withETagOf(service.cancel(id))

    private fun ResponseEntity.BodyBuilder.withETagOf(promotion: Promotion): ResponseEntity<PromotionResponse> =
        eTag(promotion.version.toString()).body(promotion.toResponse())

    /** `"3"` -> 3. A weak (`W/"3"`) or wildcard (`*`) tag can't guard a change, so it counts as missing. */
    private fun versionFromETag(header: String): Int? =
        header
            .trim()
            .removeSurrounding("\"")
            .takeIf { header.trim().startsWith('"') }
            ?.toIntOrNull()
}

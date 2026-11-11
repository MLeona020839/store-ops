package dev.storeops.promotion

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The HTTP layer only: request parsing, validation, status codes and error bodies.
 * The service is mocked, so these run without a database.
 */
@WebMvcTest(PromotionController::class)
class PromotionControllerTest(
    @Autowired private val mvc: MockMvc,
) {
    @MockitoBean
    private lateinit var service: PromotionService

    private val validBody =
        """
        {
          "title": "Alle kipfilet",
          "label": "25% korting",
          "kind": "PERCENT_OFF",
          "percent": 25,
          "validFrom": "2026-11-16",
          "validUntil": "2026-11-22",
          "storeIds": ["store-1002", "store-1001"],
          "productIds": ["product-42"]
        }
        """

    private fun bodyWith(vararg overrides: Pair<String, String>): String =
        overrides.fold(validBody) { body, (field, json) ->
            body.replace(Regex("\"$field\": [^\\n]*?(,?)\\n")) { "\"$field\": $json${it.groupValues[1]}\n" }
        }

    @Test
    fun `creates a promotion and points to it`() {
        whenever(service.create(any())).thenAnswer { (it.arguments[0] as Promotion).copy(version = 0) }

        val result =
            mvc
                .post("/promotions") {
                    contentType = MediaType.APPLICATION_JSON
                    content = validBody
                }.andExpect {
                    status { isCreated() }
                    header { string("Location", org.hamcrest.Matchers.matchesRegex("http://localhost/promotions/[0-9a-f-]{36}")) }
                    jsonPath("$.title") { value("Alle kipfilet") }
                    jsonPath("$.status") { value("SCHEDULED") }
                    jsonPath("$.validFrom") { value("2026-11-16") }
                    jsonPath("$.storeIds[0]") { value("store-1001") } // sorted
                    jsonPath("$.storeIds[1]") { value("store-1002") }
                }.andReturn()

        val saved = argumentCaptor<Promotion>().apply { verify(service).create(capture()) }.firstValue
        assertEquals(setOf("store-1001", "store-1002"), saved.stores.map { it.storeId }.toSet())
        assertEquals("http://localhost/promotions/${saved.id}", result.response.getHeader("Location"))
    }

    @Test
    fun `reports a broken business rule as 400 with the reason`() {
        mvc
            .post("/promotions") {
                contentType = MediaType.APPLICATION_JSON
                content = bodyWith("validUntil" to "\"2026-11-10\"")
            }.andExpect {
                status { isBadRequest() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.detail") { value(org.hamcrest.Matchers.containsString("validUntil")) }
            }
        verify(service, never()).create(any())
    }

    @Test
    fun `rejects input outside the allowed bounds`() {
        listOf(
            "title" to "\"   \"",
            "title" to "\"${"x".repeat(201)}\"",
            "percent" to "12.345",
            "storeIds" to "[]",
        ).forEach { override ->
            mvc
                .post("/promotions") {
                    contentType = MediaType.APPLICATION_JSON
                    content = bodyWith(override)
                }.andExpect {
                    status { isBadRequest() }
                    // Bean Validation's message, so the JSON itself parsed: the bound is what failed.
                    jsonPath("$.detail") { value("Invalid request content.") }
                }
        }
        verify(service, never()).create(any())
    }

    @Test
    fun `rejects an unknown kind and a missing field`() {
        listOf(
            bodyWith("kind" to "\"FREE_LUNCH\""),
            validBody.replace(Regex("\"title\": [^\\n]*\\n"), ""),
        ).forEach { body ->
            mvc
                .post("/promotions") {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.detail") { value("Failed to read request") }
                }
        }
        verify(service, never()).create(any())
    }

    @Test
    fun `returns 404 as a problem detail for an unknown promotion`() {
        val id = UUID.randomUUID()
        whenever(service.get(id)).thenThrow(PromotionNotFoundException(id))

        mvc.get("/promotions/$id").andExpect {
            status { isNotFound() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.detail") { value("Promotion $id not found") }
        }
    }

    @Test
    fun `rejects an id that isn't a UUID`() {
        mvc.get("/promotions/not-a-uuid").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `lists from the given date, or from today when none is given`() {
        whenever(service.listCurrentAndUpcoming(LocalDate.of(2026, 11, 16))).thenReturn(listOf(promotion()))
        whenever(service.listCurrentAndUpcoming(null)).thenReturn(emptyList())

        mvc.get("/promotions?from=2026-11-16").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
        }
        mvc.get("/promotions").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(0) }
        }
    }

    @Test
    fun `cancels a promotion`() {
        val cancelled = promotion().cancel()
        whenever(service.cancel(cancelled.id)).thenReturn(cancelled)

        mvc.post("/promotions/${cancelled.id}/cancel").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("CANCELLED") }
        }
    }

    @Test
    fun `reports a concurrent change as 409`() {
        val id = UUID.randomUUID()
        whenever(service.cancel(id)).thenThrow(OptimisticLockingFailureException("stale version"))

        mvc.post("/promotions/$id/cancel").andExpect {
            status { isConflict() }
            jsonPath("$.status") { value(409) }
        }
    }
}

package dev.storeops.storetask

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.LocalDate
import java.util.UUID

/** The HTTP layer only; the service is mocked, so these run without a database. */
@WebMvcTest(StoreTaskController::class)
@Import(SecurityConfiguration::class)
class StoreTaskControllerTest(
    @Autowired private val mvc: MockMvc,
) {
    @MockitoBean
    private lateinit var service: StoreTaskService

    // Required by the resource server configuration; the jwt() post-processor bypasses it.
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    /** A store employee's token, as the identity provider would issue it. */
    private fun employeeOf(storeId: String) = jwt().jwt { it.claim(SecurityConfiguration.OwnStoreOnly.STORE_CLAIM, storeId) }

    @Test
    fun `lists a store's open tasks`() {
        val task = storeTask(productIds = setOf("product-43", "product-42"))
        whenever(service.openTasks("store-1001", LocalDate.of(2026, 11, 16))).thenReturn(listOf(task))

        mvc.get("/stores/store-1001/tasks?until=2026-11-16") { with(employeeOf("store-1001")) }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].id") { value(task.id.toString()) }
            jsonPath("$[0].action") { value("PUT_UP_LABELS") }
            jsonPath("$[0].dueDate") { value("2026-11-16") }
            jsonPath("$[0].productIds[0]") { value("product-42") } // sorted
        }
    }

    @Test
    fun `lists up to today when no date is given`() {
        whenever(service.openTasks("store-1001", null)).thenReturn(emptyList())

        mvc.get("/stores/store-1001/tasks") { with(employeeOf("store-1001")) }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(0) }
        }
    }

    @Test
    fun `completes a task`() {
        val task = storeTask()
        whenever(service.complete(task.storeId, task.id)).thenReturn(task.complete(java.time.Instant.parse("2026-11-16T08:15:00Z")))

        mvc.post("/stores/${task.storeId}/tasks/${task.id}/complete") { with(employeeOf(task.storeId)) }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("DONE") }
            jsonPath("$.completedAt") { value("2026-11-16T08:15:00Z") }
        }
    }

    @Test
    fun `an unknown or another store's task is 404`() {
        val id = UUID.randomUUID()
        whenever(service.get("store-1001", id)).thenThrow(TaskNotFoundException("store-1001", id))

        mvc.get("/stores/store-1001/tasks/$id") { with(employeeOf("store-1001")) }.andExpect {
            status { isNotFound() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
        }
    }

    @Test
    fun `completing a cancelled task is 409`() {
        val id = UUID.randomUUID()
        whenever(service.complete("store-1001", id)).thenThrow(TaskCancelledException(id))

        mvc.post("/stores/store-1001/tasks/$id/complete") { with(employeeOf("store-1001")) }.andExpect {
            status { isConflict() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
        }
    }

    @Test
    fun `a concurrent change is 409`() {
        val id = UUID.randomUUID()
        whenever(service.complete("store-1001", id)).thenThrow(OptimisticLockingFailureException("stale version"))

        mvc.post("/stores/store-1001/tasks/$id/complete") { with(employeeOf("store-1001")) }.andExpect {
            status { isConflict() }
            jsonPath("$.status") { value(409) }
        }
    }

    @Test
    fun `rejects a malformed date, task id or store id`() {
        mvc.get("/stores/store-1001/tasks?until=16-11-2026") { with(employeeOf("store-1001")) }.andExpect { status { isBadRequest() } }
        mvc.get("/stores/store-1001/tasks/not-a-uuid") { with(employeeOf("store-1001")) }.andExpect { status { isBadRequest() } }
        val tooLong = "x".repeat(65)
        mvc.get("/stores/$tooLong/tasks") { with(employeeOf(tooLong)) }.andExpect { status { isBadRequest() } }
        verify(service, never()).openTasks(any(), any())
    }

    @Test
    fun `requires a token`() {
        mvc.get("/stores/store-1001/tasks").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `an employee can't see or complete another store's tasks`() {
        mvc.get("/stores/store-1001/tasks") { with(employeeOf("store-2002")) }.andExpect { status { isForbidden() } }
        mvc
            .post("/stores/store-1001/tasks/${UUID.randomUUID()}/complete") { with(employeeOf("store-2002")) }
            .andExpect { status { isForbidden() } }
        verify(service, never()).openTasks(any(), any())
        verify(service, never()).complete(any(), any())
    }

    @Test
    fun `a token without a store is refused`() {
        mvc.get("/stores/store-1001/tasks") { with(jwt()) }.andExpect { status { isForbidden() } }
    }
}

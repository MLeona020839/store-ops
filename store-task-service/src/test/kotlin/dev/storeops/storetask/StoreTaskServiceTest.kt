package dev.storeops.storetask

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class StoreTaskServiceTest {
    private val tasks = mock<StoreTaskRepository>()

    // 23:30 UTC on Sunday 15 November is already Monday 16 November in the stores.
    private val now = Instant.parse("2026-11-15T23:30:00Z")
    private val service = StoreTaskService(tasks, Clock.fixed(now, StoreTaskPlanning.STORE_ZONE))

    init {
        doAnswer { it.arguments[0] }.whenever(tasks).save(any<StoreTask>())
    }

    private fun stored(task: StoreTask): StoreTask {
        whenever(tasks.findById(task.id)).thenReturn(Optional.of(task))
        return task
    }

    @Test
    fun `lists work due by today in the stores' time zone when no date is given`() {
        service.openTasks("store-1001")

        verify(tasks).findOpenForStore("store-1001", LocalDate.of(2026, 11, 16))
    }

    @Test
    fun `lists work due by the given date`() {
        service.openTasks("store-1001", LocalDate.of(2026, 11, 30))

        verify(tasks).findOpenForStore("store-1001", LocalDate.of(2026, 11, 30))
    }

    @Test
    fun `another store's task is not found`() {
        val task = stored(storeTask(storeId = "store-1001"))

        assertFailsWith<TaskNotFoundException> { service.get("store-2002", task.id) }
        assertFailsWith<TaskNotFoundException> { service.complete("store-2002", task.id) }
        verify(tasks, never()).save(any<StoreTask>())
    }

    @Test
    fun `an unknown task is not found`() {
        val id = UUID.randomUUID()
        whenever(tasks.findById(id)).thenReturn(Optional.empty())

        assertFailsWith<TaskNotFoundException> { service.get("store-1001", id) }
    }

    @Test
    fun `completing saves the task as done now`() {
        val task = stored(storeTask())

        val done = service.complete(task.storeId, task.id)

        assertEquals(TaskStatus.DONE, done.status)
        assertEquals(now, done.completedAt)
        verify(tasks).save(done)
    }

    @Test
    fun `completing a done task changes nothing`() {
        val task = stored(storeTask(status = TaskStatus.DONE))

        assertSame(task, service.complete(task.storeId, task.id))
        verify(tasks, never()).save(any<StoreTask>())
    }

    @Test
    fun `completing a cancelled task is a conflict`() {
        val task = stored(storeTask(status = TaskStatus.CANCELLED))

        assertFailsWith<TaskCancelledException> { service.complete(task.storeId, task.id) }
        verify(tasks, never()).save(any<StoreTask>())
    }
}

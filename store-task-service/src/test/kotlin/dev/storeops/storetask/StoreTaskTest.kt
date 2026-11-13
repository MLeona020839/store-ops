package dev.storeops.storetask

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class StoreTaskTest {
    private val now = Instant.parse("2026-11-16T08:15:00Z")

    @Test
    fun `completing an open task records when`() {
        val done = storeTask().complete(now)

        assertEquals(TaskStatus.DONE, done.status)
        assertEquals(now, done.completedAt)
    }

    @Test
    fun `completing twice keeps the first completion time`() {
        val done = storeTask().complete(now)

        assertSame(done, done.complete(now.plusSeconds(60)))
    }

    @Test
    fun `a cancelled task can't be completed`() {
        assertFailsWith<IllegalStateException> { storeTask(status = TaskStatus.CANCELLED).complete(now) }
    }

    @Test
    fun `done and completedAt go together`() {
        assertFailsWith<IllegalArgumentException> { storeTask().copy(completedAt = now) }
        assertFailsWith<IllegalArgumentException> { storeTask().copy(status = TaskStatus.DONE) }
    }
}

package dev.storeops.storetask

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** What store employees do with their tasks. */
@Service
class StoreTaskService(
    private val tasks: StoreTaskRepository,
    private val clock: Clock,
) {
    /** Open tasks due by [until] (default: today), overdue ones included. */
    @Transactional(readOnly = true)
    fun openTasks(
        storeId: String,
        until: LocalDate? = null,
    ): List<StoreTask> = tasks.findOpenForStore(storeId, until ?: LocalDate.now(clock))

    /** A task of this store; another store's task is reported as not found. */
    @Transactional(readOnly = true)
    fun get(
        storeId: String,
        taskId: UUID,
    ): StoreTask =
        tasks
            .findById(taskId)
            .filter { it.storeId == storeId }
            .orElseThrow { TaskNotFoundException(storeId, taskId) }

    /** Idempotent: completing a done task returns it unchanged. */
    @Transactional
    fun complete(
        storeId: String,
        taskId: UUID,
    ): StoreTask {
        val task = get(storeId, taskId)
        return when (task.status) {
            TaskStatus.DONE -> task
            TaskStatus.CANCELLED -> throw TaskCancelledException(taskId)
            TaskStatus.OPEN -> tasks.save(task.complete(Instant.now(clock)))
        }
    }
}

package dev.storeops.storetask

import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

// RFC 9457 problem details, as in promotion-service (spring.mvc.problemdetails.enabled).

/**
 * Also used when the task exists but belongs to another store: saying "not yours" would
 * confirm that the id exists, so both cases look the same to the caller.
 */
class TaskNotFoundException(
    storeId: String,
    taskId: UUID,
) : ErrorResponseException(
        HttpStatus.NOT_FOUND,
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Task $taskId not found for store $storeId"),
        null,
    )

class TaskCancelledException(
    taskId: UUID,
) : ErrorResponseException(
        HttpStatus.CONFLICT,
        ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "Task $taskId was cancelled, most likely because its promotion was; there's nothing to do",
        ),
        null,
    )

@RestControllerAdvice
class ApiExceptionHandler {
    /**
     * The task changed between our read and our write, e.g. a cancellation event was being
     * handled at the same moment. The client reloads and sees the current state.
     */
    @ExceptionHandler(OptimisticLockingFailureException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun concurrentChange(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The task was changed at the same time; reload it and try again")
}

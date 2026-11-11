package dev.storeops.promotion

import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

// Errors are returned as RFC 9457 problem details (application/problem+json).
// ErrorResponseException subclasses are rendered by Spring Boot's problem-details handler,
// enabled with spring.mvc.problemdetails.enabled.

class PromotionNotFoundException(
    id: UUID,
) : ErrorResponseException(
        HttpStatus.NOT_FOUND,
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Promotion $id not found"),
        null,
    )

class InvalidPromotionException(
    detail: String,
) : ErrorResponseException(
        HttpStatus.BAD_REQUEST,
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail),
        null,
    )

@RestControllerAdvice
class ApiExceptionHandler {
    /** Someone else changed the promotion between our read and our write. */
    @ExceptionHandler(OptimisticLockingFailureException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun concurrentChange(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The promotion was changed at the same time; reload it and try again")
}

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

/** The promotion's state doesn't allow this change (cancelled, ended, or already running). */
class PromotionNotChangeableException(
    detail: String,
) : ErrorResponseException(
        HttpStatus.CONFLICT,
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail),
        null,
    )

/** If-Match named a version that isn't the current one: someone else changed the promotion first. */
class PromotionVersionMismatchException(
    id: UUID,
) : ErrorResponseException(
        HttpStatus.PRECONDITION_FAILED,
        ProblemDetail.forStatusAndDetail(
            HttpStatus.PRECONDITION_FAILED,
            "Promotion $id was changed since you read it; GET it again and reapply your change",
        ),
        null,
    )

/** A change without If-Match could silently overwrite someone else's, so it isn't accepted. */
class IfMatchRequiredException :
    ErrorResponseException(
        HttpStatus.PRECONDITION_REQUIRED,
        ProblemDetail.forStatusAndDetail(
            HttpStatus.PRECONDITION_REQUIRED,
            "Send the promotion's ETag from your last GET in an If-Match header",
        ),
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

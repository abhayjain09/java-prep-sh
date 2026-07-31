package com.interviewprep.orders.springapp.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Central error-handling point for the whole REST API. WHY ONE CLASS FOR
 * EVERYTHING instead of try/catch in every controller method: this is the
 * exact pattern java-basics' exception Javadocs kept previewing ("the
 * natural handling point is one global exception handler translating it
 * into a 409 Conflict response") — every controller method stays focused on
 * its own request/response shape and simply lets domain exceptions
 * propagate; this class is the ONE place that decides how each exception
 * type becomes an HTTP status + body, so that decision is made consistently
 * everywhere instead of being re-litigated (and inevitably done slightly
 * differently) in every controller.
 *
 * {@code @RestControllerAdvice} = {@code @ControllerAdvice} +
 * {@code @ResponseBody} — it's a Spring AOP-backed interceptor applied
 * across every {@code @RestController} in the application (or a subset,
 * if scoped with {@code basePackages}/{@code assignableTypes}), and each
 * {@code @ExceptionHandler} method here is tried, most-specific exception
 * type first, whenever a matching exception escapes a controller method.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 409 CONFLICT for "not enough stock": the request was well-formed and
     * the resources involved exist, but fulfilling it conflicts with the
     * CURRENT STATE of the server's data (stock level) — the textbook
     * definition of when 409 (rather than 400, which means the REQUEST
     * itself was malformed) is the right status code.
     */
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ApiError> handleInsufficientStock(InsufficientStockException ex,
                                                              HttpServletRequest request) {
        log.warn("Insufficient stock: {}", ex.getMessage()); // WARN, not ERROR —
        // this is an expected business condition a client can act on
        // (show "only N left in stock"), not a bug. Reserve ERROR for
        // conditions that indicate something is actually broken — flooding
        // logs with ERROR for routine business rejections trains
        // on-call engineers to ignore alerts, which is how real incidents
        // get missed.
        ApiError body = ApiError.of(
                HttpStatus.CONFLICT.value(), HttpStatus.CONFLICT.getReasonPhrase(),
                ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /** 409 CONFLICT for an illegal order-status transition — see
     * {@code InvalidOrderStateException}'s Javadoc for why this gets its
     * own handler instead of being caught as a generic 500. */
    @ExceptionHandler(InvalidOrderStateException.class)
    public ResponseEntity<ApiError> handleInvalidOrderState(InvalidOrderStateException ex,
                                                              HttpServletRequest request) {
        log.warn("Invalid order state transition: {}", ex.getMessage());
        ApiError body = ApiError.of(
                HttpStatus.CONFLICT.value(), HttpStatus.CONFLICT.getReasonPhrase(),
                ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * 409 CONFLICT for a failed optimistic lock (see {@code Product.version}
     * / {@code Order.version} Javadocs). {@code ObjectOptimisticLockingFailureException}
     * is Spring's translated form of JPA's {@code OptimisticLockException} —
     * Spring wraps low-level persistence-provider exceptions into its own
     * {@code DataAccessException} hierarchy precisely so application code
     * (including this handler) never needs to depend on Hibernate-specific
     * exception types, only Spring's.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException ex,
                                                           HttpServletRequest request) {
        log.warn("Optimistic locking conflict: {}", ex.getMessage());
        ApiError body = ApiError.of(
                HttpStatus.CONFLICT.value(), HttpStatus.CONFLICT.getReasonPhrase(),
                "The resource was modified concurrently by another request. Please retry.",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /** 404 NOT FOUND — the requested resource doesn't exist. */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        ApiError body = ApiError.of(
                HttpStatus.NOT_FOUND.value(), HttpStatus.NOT_FOUND.getReasonPhrase(),
                ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * 400 BAD REQUEST with STRUCTURED FIELD ERRORS for Bean Validation
     * failures (any {@code @Valid} request body that fails its
     * {@code @NotBlank}/{@code @Positive}/etc. constraints). Spring throws
     * {@code MethodArgumentNotValidException} carrying a {@code BindingResult}
     * with one {@code FieldError} per violated constraint; this handler
     * flattens that into the same {@code ApiError} shape every other error
     * uses, but with {@code fieldErrors} populated — e.g.
     * {@code [{"field": "email", "message": "email must be a well-formed email address"}]}
     * — which is dramatically more useful to a frontend form than a single
     * generic "validation failed" string, because it can highlight the
     * exact offending field.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex,
                                                       HttpServletRequest request) {
        List<ApiError.FieldValidationError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldValidationError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        ApiError body = ApiError.validation(
                HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Validation failed for one or more fields", request.getRequestURI(), fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * 500 INTERNAL SERVER ERROR — the catch-all for anything not handled
     * above (a genuine bug, an unexpected null, a downstream failure).
     *
     * WHY THE RESPONSE BODY IS DELIBERATELY GENERIC (no {@code ex.getMessage()},
     * no stack trace, no exception class name): leaking a stack trace or an
     * internal exception message to an API response is a well-known
     * security anti-pattern (OWASP calls this out explicitly) because it
     * can reveal internal implementation details an attacker can use to
     * plan further attacks — package/class names (technology
     * fingerprinting: "this is a Spring Boot 3.3 app using Hibernate"),
     * SQL fragments (hints toward SQL injection surface), file paths,
     * or even fragments of internal business logic. The FULL exception
     * (message + stack trace) is logged at ERROR server-side, where an
     * engineer with legitimate access can see it — that's the correct
     * audience for that detail, not an arbitrary API caller. This exact
     * trade-off (rich detail in logs, minimal detail in the response) is a
     * near-guaranteed senior-interview and security-review question.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception processing request {} {}", request.getMethod(), request.getRequestURI(), ex);
        ApiError body = ApiError.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(), HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "An unexpected error occurred. Please contact support if this persists.",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}

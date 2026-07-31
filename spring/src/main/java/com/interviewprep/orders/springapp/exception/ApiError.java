package com.interviewprep.orders.springapp.exception;

import java.time.Instant;
import java.util.List;

/**
 * The single, structured error response shape returned by every branch of
 * {@code GlobalExceptionHandler} — clients can always parse
 * {@code {timestamp, status, error, message, path, fieldErrors}} regardless
 * of which failure occurred, rather than each endpoint inventing its own ad
 * hoc error JSON shape.
 *
 * {@code fieldErrors} is null/omitted for non-validation failures and
 * populated only for {@code MethodArgumentNotValidException} (see the
 * handler) — kept as a plain list rather than a map so multiple violations
 * on the same field (rare but possible) aren't silently collapsed to one.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldValidationError> fieldErrors
) {
    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(Instant.now(), status, error, message, path, null);
    }

    public static ApiError validation(int status, String error, String message, String path,
                                       List<FieldValidationError> fieldErrors) {
        return new ApiError(Instant.now(), status, error, message, path, fieldErrors);
    }

    public record FieldValidationError(String field, String message) {
    }
}

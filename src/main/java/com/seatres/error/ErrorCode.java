package com.seatres.error;

import java.util.Locale;
import org.springframework.http.HttpStatus;

/** Stable machine-readable error codes. Clients branch on these, never on {@code detail}. */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed",
            "Request validation failed."),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request",
            "Request body could not be parsed."),
    IDEMPOTENCY_KEY_MISSING(HttpStatus.BAD_REQUEST, "Idempotency key missing",
            "The Idempotency-Key header is required."),
    IDEMPOTENCY_KEY_INVALID(HttpStatus.BAD_REQUEST, "Idempotency key invalid",
            "The Idempotency-Key header is not in the accepted format."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Unauthenticated",
            "A valid bearer token is required."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden",
            "This operation is not permitted for the caller."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found",
            "No such resource."),
    SHOW_NOT_FOUND(HttpStatus.NOT_FOUND, "Show not found",
            "No such show."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Reservation not found",
            "No such reservation."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed",
            "This method is not supported for this resource."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "Idempotency key reused",
            "This Idempotency-Key was already used with a different request."),
    SEAT_UNAVAILABLE(HttpStatus.CONFLICT, "Seat unavailable",
            "One or more requested seats are not available. No seats were reserved."),
    USER_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "Per-user seat limit exceeded",
            "This request would exceed the per-user seat limit for this show. No seats were reserved."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type",
            "The request content type is not supported."),
    UNKNOWN_SEAT(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown seat",
            "One or more requested seats do not exist in this show. No seats were reserved."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
            "The request could not be processed."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable",
            "A dependency is unavailable. Retry with the same Idempotency-Key.", true),
    OUTCOME_UNKNOWN(HttpStatus.SERVICE_UNAVAILABLE, "Outcome unknown",
            "The database connection failed while committing. "
                    + "Retry with the same Idempotency-Key to obtain the outcome.", true);

    private static final String TYPE_PREFIX = "urn:seatres:problem:";

    private final HttpStatus status;
    private final String title;
    private final String detail;
    private final boolean retryable;
    private final String type;

    ErrorCode(HttpStatus status, String title, String detail) {
        this(status, title, detail, false);
    }

    ErrorCode(HttpStatus status, String title, String detail, boolean retryable) {
        this.status = status;
        this.title = title;
        this.detail = detail;
        this.retryable = retryable;
        this.type = TYPE_PREFIX + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String detail() {
        return detail;
    }

    public boolean retryable() {
        return retryable;
    }

    public String type() {
        return type;
    }
}

package com.example.notificationdispatcher.exception;

import lombok.Getter;

/**
 * Custom runtime exception representing an outbound HTTP dispatch failure.
 * Carries metadata defining whether the failure is transient (retryable) and the HTTP status code.
 */
@Getter
public class DispatchException extends RuntimeException {

    /**
     * Flag indicating if the exception is transient and can be retried.
     */
    private final boolean retryable;

    /**
     * The HTTP status code returned by the vendor API (or 0 if connection/timeout failure).
     */
    private final int statusCode;

    /**
     * Constructs a new DispatchException.
     *
     * @param message the detailed error message
     * @param retryable true if the exception represents a transient failure that can be retried
     * @param statusCode the HTTP status code (or 0)
     */
    public DispatchException(String message, boolean retryable, int statusCode) {
        super(message);
        this.retryable = retryable;
        this.statusCode = statusCode;
    }

    /**
     * Constructs a new DispatchException with a cause.
     *
     * @param message the detailed error message
     * @param retryable true if the exception represents a transient failure that can be retried
     * @param statusCode the HTTP status code (or 0)
     * @param cause the underlying exception cause
     */
    public DispatchException(String message, boolean retryable, int statusCode, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.statusCode = statusCode;
    }
}

package com.example.notificationdispatcher.exception;

import lombok.Getter;

@Getter
public class DispatchException extends RuntimeException {

    private final boolean retryable;
    private final int statusCode;

    public DispatchException(String message, boolean retryable, int statusCode) {
        super(message);
        this.retryable = retryable;
        this.statusCode = statusCode;
    }

    public DispatchException(String message, boolean retryable, int statusCode, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.statusCode = statusCode;
    }
}

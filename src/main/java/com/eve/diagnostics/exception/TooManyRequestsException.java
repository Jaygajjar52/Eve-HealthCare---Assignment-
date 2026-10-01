package com.eve.diagnostics.exception;

/** The caller has made too many requests in a short period. Answered with 429. */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
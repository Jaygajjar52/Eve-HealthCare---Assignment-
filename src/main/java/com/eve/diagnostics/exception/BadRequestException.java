package com.eve.diagnostics.exception;

/** The request itself is wrong, even though it parsed. Answered with 400. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
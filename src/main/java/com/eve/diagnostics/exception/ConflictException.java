package com.eve.diagnostics.exception;

/** The request clashes with the current state of a resource. Answered with 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
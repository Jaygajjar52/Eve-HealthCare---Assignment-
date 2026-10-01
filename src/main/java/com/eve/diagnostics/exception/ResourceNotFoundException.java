package com.eve.diagnostics.exception;

/** The resource asked for does not exist. Answered with 404. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
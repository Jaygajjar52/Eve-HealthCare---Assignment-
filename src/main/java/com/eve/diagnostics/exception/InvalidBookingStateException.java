package com.eve.diagnostics.exception;

/**
 * The booking is not in a state that allows the requested move, for example
 * cancelling a booking that is already cancelled. Answered with 409.
 *
 * <p>Extends {@link ConflictException} because that is exactly what it is - the
 * request is valid, it just clashes with the current state - so it is mapped to
 * 409 without needing a second handler.
 */
public class InvalidBookingStateException extends ConflictException {

    public InvalidBookingStateException(String message) {
        super(message);
    }
}
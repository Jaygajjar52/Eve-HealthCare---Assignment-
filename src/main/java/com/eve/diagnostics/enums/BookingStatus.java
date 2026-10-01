package com.eve.diagnostics.enums;

/**
 * Lifecycle of a booking. The allowed moves between these values live in one
 * place only: Booking.canTransitionTo(...).
 *
 * <p>PENDING    -> CONFIRMED | FAILED | CANCELLED
 * FAILED     -> PENDING (user retries the payment)
 * CONFIRMED  -> CANCELLED
 * CANCELLED  -> (terminal)
 */
public enum BookingStatus {
    PENDING,
    CONFIRMED,
    FAILED,
    CANCELLED
}
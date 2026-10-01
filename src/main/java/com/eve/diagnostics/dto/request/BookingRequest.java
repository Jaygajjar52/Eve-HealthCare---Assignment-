package com.eve.diagnostics.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * There is no "amount" field on purpose. The price is read from centre_tests by
 * the service, so a client cannot influence how much a booking costs, not even
 * by sending an extra property.
 */
public record BookingRequest(

        @NotNull(message = "centreId is required")
        UUID centreId,

        @NotNull(message = "testId is required")
        UUID testId,

        @NotNull(message = "appointmentTime is required")
        @Future(message = "appointmentTime must be in the future")
        Instant appointmentTime) {
}
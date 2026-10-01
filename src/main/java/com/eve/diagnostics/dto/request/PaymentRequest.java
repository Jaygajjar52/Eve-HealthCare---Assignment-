package com.eve.diagnostics.dto.request;

import com.eve.diagnostics.enums.PaymentStatus;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PaymentRequest(

        @NotNull(message = "bookingId is required")
        UUID bookingId,

        /**
         * Forces the outcome of the simulated gateway instead of rolling the
         * dice. Accepted only when the application is not running the "prod"
         * profile; in prod the field is rejected with 400 rather than quietly
         * ignored, so nobody believes they forced a result that was not forced.
         * An unrecognised value fails as 400 during deserialisation.
         */
        PaymentStatus simulateStatus) {
}
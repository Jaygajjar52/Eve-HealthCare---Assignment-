package com.eve.diagnostics.dto.request;

import com.eve.diagnostics.enums.PaymentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * What the payment provider sends us.
 *
 * <p>eventId is the idempotency key. paymentId and bookingId are plain
 * identifiers and not foreign keys on webhook_events, because an event can
 * legitimately arrive for something we have never seen and still has to be
 * recorded.
 */
public record WebhookRequest(

        @NotBlank(message = "eventId is required")
        @Size(max = 120, message = "eventId must be at most 120 characters")
        String eventId,

        @NotNull(message = "paymentId is required")
        UUID paymentId,

        @NotNull(message = "bookingId is required")
        UUID bookingId,

        /** An unrecognised value is rejected with 400 while deserialising. */
        @NotNull(message = "status is required")
        PaymentStatus status,

        @NotNull(message = "timestamp is required")
        Instant timestamp) {
}
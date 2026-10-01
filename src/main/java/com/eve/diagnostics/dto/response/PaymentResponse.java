package com.eve.diagnostics.dto.response;

import com.eve.diagnostics.entity.Payment;
import com.eve.diagnostics.enums.BookingStatus;
import com.eve.diagnostics.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Includes the booking's status as well as the payment's, because "did my money
 * come through" is a question about the booking and the client should not have
 * to make a second call to find out.
 */
public record PaymentResponse(
        UUID id,
        PaymentStatus status,
        UUID bookingId,
        BookingStatus bookingStatus,
        BigDecimal amount,
        String providerReference,
        String failureReason,
        Instant createdAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getStatus(),
                payment.getBooking().getId(),
                payment.getBooking().getStatus(),
                payment.getAmount(),
                payment.getProviderReference(),
                payment.getFailureReason(),
                payment.getCreatedAt());
    }
}
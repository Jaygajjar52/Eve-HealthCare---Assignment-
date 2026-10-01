package com.eve.diagnostics.dto.response;

import com.eve.diagnostics.entity.Booking;
import com.eve.diagnostics.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BookingResponse(
        UUID id,
        UUID centreId,
        String centreName,
        UUID testId,
        String testName,
        Instant appointmentTime,
        BigDecimal amount,
        BookingStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static BookingResponse from(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getCentre().getId(),
                booking.getCentre().getName(),
                booking.getTest().getId(),
                booking.getTest().getName(),
                booking.getAppointmentTime(),
                booking.getAmount(),
                booking.getStatus(),
                booking.getCreatedAt(),
                booking.getUpdatedAt());
    }
}
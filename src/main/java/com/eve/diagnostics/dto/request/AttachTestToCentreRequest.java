package com.eve.diagnostics.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record AttachTestToCentreRequest(

        @NotNull(message = "testId is required")
        UUID testId,

        @NotNull(message = "price is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "price must be greater than 0")
        // Matches NUMERIC(10, 2) in the schema: 8 digits before the point, 2 after.
        @Digits(integer = 8, fraction = 2, message = "price must have at most 2 decimal places")
        BigDecimal price) {
}
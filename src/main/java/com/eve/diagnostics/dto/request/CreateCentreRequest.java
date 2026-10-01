package com.eve.diagnostics.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCentreRequest(

        @NotBlank(message = "name is required")
        @Size(max = 160, message = "name must be at most 160 characters")
        String name,

        @NotBlank(message = "city is required")
        @Size(max = 80, message = "city must be at most 80 characters")
        String city,

        @Size(max = 255, message = "address must be at most 255 characters")
        String address) {
}
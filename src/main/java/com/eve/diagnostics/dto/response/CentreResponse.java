package com.eve.diagnostics.dto.response;

import com.eve.diagnostics.entity.DiagnosticCentre;

import java.time.Instant;
import java.util.UUID;

/**
 * One centre in a list. Deliberately has no tests field: listing many centres
 * and then loading their tests would be one extra query per centre.
 * GET /centres/{id} uses CentreDetailsResponse instead.
 */
public record CentreResponse(
        UUID id,
        String name,
        String city,
        String address,
        Instant createdAt) {

    public static CentreResponse from(DiagnosticCentre centre) {
        return new CentreResponse(
                centre.getId(),
                centre.getName(),
                centre.getCity(),
                centre.getAddress(),
                centre.getCreatedAt());
    }
}
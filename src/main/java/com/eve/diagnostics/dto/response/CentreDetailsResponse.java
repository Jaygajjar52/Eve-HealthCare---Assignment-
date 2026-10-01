package com.eve.diagnostics.dto.response;

import com.eve.diagnostics.entity.DiagnosticCentre;
import com.eve.diagnostics.entity.CentreTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A single centre together with the tests it offers, as returned by GET /centres/{id}. */
public record CentreDetailsResponse(
        UUID id,
        String name,
        String city,
        String address,
        Instant createdAt,
        List<CentreTestResponse> tests) {

    public static CentreDetailsResponse from(DiagnosticCentre centre, List<CentreTest> centreTests) {
        return new CentreDetailsResponse(
                centre.getId(),
                centre.getName(),
                centre.getCity(),
                centre.getAddress(),
                centre.getCreatedAt(),
                centreTests.stream().map(CentreTestResponse::from).toList());
    }
}
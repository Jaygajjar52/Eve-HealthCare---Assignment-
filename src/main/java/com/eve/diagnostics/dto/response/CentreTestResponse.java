package com.eve.diagnostics.dto.response;

import com.eve.diagnostics.entity.CentreTest;

import java.math.BigDecimal;
import java.util.UUID;

/** A test as offered by one centre, with that centre's price. */
public record CentreTestResponse(
        UUID id,
        UUID testId,
        String testName,
        String description,
        BigDecimal price) {

    public static CentreTestResponse from(CentreTest centreTest) {
        return new CentreTestResponse(
                centreTest.getId(),
                centreTest.getTest().getId(),
                centreTest.getTest().getName(),
                centreTest.getTest().getDescription(),
                centreTest.getPrice());
    }
}
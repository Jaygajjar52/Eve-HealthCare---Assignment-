package com.eve.diagnostics.dto.response;

import com.eve.diagnostics.entity.DiagnosticTest;

import java.util.UUID;

/** An entry in the global test catalogue. */
public record TestResponse(
        UUID id,
        String name,
        String description) {

    public static TestResponse from(DiagnosticTest test) {
        return new TestResponse(test.getId(), test.getName(), test.getDescription());
    }
}
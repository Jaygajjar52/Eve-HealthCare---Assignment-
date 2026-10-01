package com.eve.diagnostics.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

/**
 * The one JSON shape every error in this API uses, no matter where it comes
 * from: a thrown exception, bean validation, or Spring Security rejecting a
 * request. Keeping a single shape means a client only has to learn one format.
 *
 * <p>fieldErrors is null for errors that are not about a single field, and
 * application.yml sets non_null inclusion so it is left out rather than sent
 * as null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldError> fieldErrors) {

    public static ApiError of(HttpStatus status, String message, String path) {
        return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, path, null);
    }

    public static ApiError of(HttpStatus status, String message, String path, List<FieldError> fieldErrors) {
        return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, path, fieldErrors);
    }

    /** One rejected field from @Valid. */
    public record FieldError(String field, String message) {
    }
}
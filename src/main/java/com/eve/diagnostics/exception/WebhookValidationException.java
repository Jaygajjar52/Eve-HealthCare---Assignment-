package com.eve.diagnostics.exception;

import com.eve.diagnostics.dto.response.ApiError;

import java.util.List;

/**
 * Carries the per-field problems found while validating a body we parsed
 * ourselves.
 *
 * <p>The webhook body is read as a String so the signature can be checked over
 * the exact bytes the provider sent, which means Spring's {@code @Valid} cannot
 * do the validation for us. Rather than give up the fieldErrors that {@code @Valid}
 * would have produced, we run the same {@link jakarta.validation.Validator} by
 * hand and carry the result here.
 */
public class WebhookValidationException extends RuntimeException {

    private final transient List<ApiError.FieldError> fieldErrors;

    public WebhookValidationException(List<ApiError.FieldError> fieldErrors, String body) {
        super("Validation failed.");
        this.fieldErrors = fieldErrors;
    }

    public List<ApiError.FieldError> getFieldErrors() {
        return fieldErrors;
    }
}
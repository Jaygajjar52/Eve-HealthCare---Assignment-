package com.eve.diagnostics.controller;

import com.eve.diagnostics.dto.request.PaymentRequest;
import com.eve.diagnostics.dto.request.WebhookRequest;
import com.eve.diagnostics.dto.response.ApiError;
import com.eve.diagnostics.dto.response.PaymentResponse;
import com.eve.diagnostics.dto.response.WebhookResponse;
import com.eve.diagnostics.exception.BadRequestException;
import com.eve.diagnostics.exception.WebhookValidationException;
import com.eve.diagnostics.security.AppUserDetails;
import com.eve.diagnostics.service.PaymentService;
import com.eve.diagnostics.service.WebhookService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Simulated payments against a mock gateway, and the provider webhook")
public class PaymentController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String SIGNATURE_HEADER = "X-Signature";

    private final PaymentService paymentService;
    private final WebhookService webhookService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    /**
     * Provider webhook. Public route, but every call has to carry a valid
     * X-Signature or it is rejected with 401 before anything is read or written.
     *
     * <p>The body arrives as a String on purpose. The signature is computed over
     * the bytes the provider sent, so we need those exact bytes; parsing straight
     * into WebhookRequest and re-serialising to check the signature could produce
     * a different result and reject a perfectly valid call.
     */
    @PostMapping({"/webhook", "/webhook/"})
    @Operation(summary = "Payment webhook (provider)",
            description = "Public endpoint guarded by X-Signature = hex(HMAC-SHA256(webhook secret, raw body)). "
                    + "Safe to retry: the eventId is recorded first under a UNIQUE constraint, so a repeat "
                    + "returns ALREADY_PROCESSED and changes nothing.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Applied, or deliberately ignored (always 2xx so the "
                    + "provider stops retrying), or already processed",
                    content = @Content(schema = @Schema(implementation = WebhookResponse.class))),
            @ApiResponse(responseCode = "400", description = "Body is not valid JSON, or a field is missing/unknown",
                    content = @Content),
            @ApiResponse(responseCode = "401", description = "Missing or invalid X-Signature", content = @Content),
            @ApiResponse(responseCode = "404", description = "Unknown paymentId", content = @Content)
    })
    public ResponseEntity<WebhookResponse> receiveWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {

        // 1. Authenticate before touching the body at all.
        webhookService.verifySignature(rawBody, signature);

        // 2. Parse and validate. An unknown "status" value fails here as 400.
        WebhookRequest request = parseRequest(rawBody);
        validate(request, rawBody);

        // 3. Record and apply, exactly once.
        WebhookService.WebhookResult result = webhookService.process(request, rawBody);

        return ResponseEntity.status(statusFor(result.outcome()))
                .body(WebhookResponse.of(result.result()));
    }

    private WebhookRequest parseRequest(String rawBody) {
        try {
            return objectMapper.readValue(rawBody, WebhookRequest.class);
        } catch (JsonProcessingException ex) {
            throw new BadRequestException("Request body is not valid JSON.");
        }
    }

    private void validate(WebhookRequest request, String rawBody) {
        Set<ConstraintViolation<WebhookRequest>> violations = validator.validate(request);
        if (violations.isEmpty()) {
            return;
        }
        List<ApiError.FieldError> fieldErrors = violations.stream()
                .map(violation -> new ApiError.FieldError(
                        violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();
        throw new WebhookValidationException(fieldErrors, rawBody);
    }

    private HttpStatus statusFor(WebhookService.Outcome outcome) {
        return switch (outcome) {
            case PROCESSED, ALREADY_PROCESSED -> HttpStatus.OK;
            case PAYMENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case BOOKING_MISMATCH -> HttpStatus.BAD_REQUEST;
        };
    }

    @PostMapping
    @Operation(summary = "Pay for a booking",
            description = "Runs the mock gateway and moves the booking to CONFIRMED or FAILED. "
                    + "By default the outcome is random with probability "
                    + "eve.payment.success-rate; pass simulateStatus to force it (not allowed on the prod "
                    + "profile). Retry after a timeout with the same Idempotency-Key header and you get the "
                    + "original payment back rather than a second charge - still 201, since the payment for "
                    + "that key does exist and telling replay apart from first creation would need a "
                    + "response type for no real benefit.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Payment processed",
                    content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid body, or simulateStatus on the prod profile",
                    content = @Content),
            @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content),
            @ApiResponse(responseCode = "404", description = "No such booking for this user", content = @Content),
            @ApiResponse(responseCode = "409",
                    description = "Booking is already paid or cancelled, or a concurrent payment won the race. "
                            + "Either way, retry.", content = @Content)
    })
    public ResponseEntity<PaymentResponse> createPayment(
            @AuthenticationPrincipal AppUserDetails user,
            @Valid @RequestBody PaymentRequest request,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentService.createPayment(user.getUserId(), request, idempotencyKey));
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get one of my payments")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found",
                    content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
            @ApiResponse(responseCode = "404",
                    description = "No such payment for this user. Also returned for another user's payment.",
                    content = @Content)
    })
    public PaymentResponse getPayment(@AuthenticationPrincipal AppUserDetails user,
                                      @PathVariable UUID paymentId) {
        return paymentService.getPayment(user.getUserId(), paymentId);
    }
}
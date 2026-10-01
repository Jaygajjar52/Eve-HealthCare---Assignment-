package com.eve.diagnostics.controller;

import com.eve.diagnostics.dto.request.PaymentRequest;
import com.eve.diagnostics.dto.response.PaymentResponse;
import com.eve.diagnostics.security.AppUserDetails;
import com.eve.diagnostics.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

import java.util.UUID;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Payments", description = "Simulated payments against a mock gateway")
public class PaymentController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final PaymentService paymentService;

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
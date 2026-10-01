package com.eve.diagnostics.controller;

import com.eve.diagnostics.dto.request.BookingRequest;
import com.eve.diagnostics.dto.response.BookingResponse;
import com.eve.diagnostics.dto.response.PageResponse;
import com.eve.diagnostics.enums.BookingStatus;
import com.eve.diagnostics.security.AppUserDetails;
import com.eve.diagnostics.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/bookings")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Bookings", description = "Book a diagnostic test at a centre")
public class BookingController {

    private final BookingService bookingService;

    @PostMapping
    @Operation(summary = "Book a test",
            description = "The price comes from the centre's catalogue, not from the request, and the "
                    + "booking always belongs to the signed-in user. appointmentTime must be an ISO-8601 "
                    + "instant in UTC, for example 2026-11-01T09:30:00Z.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Booking created",
                    content = @Content(schema = @Schema(implementation = BookingResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed, or the time is in the past",
                    content = @Content),
            @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content),
            @ApiResponse(responseCode = "404", description = "The centre does not offer that test", content = @Content)
    })
    public ResponseEntity<BookingResponse> createBooking(
            @AuthenticationPrincipal AppUserDetails user,
            @Valid @RequestBody BookingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bookingService.createBooking(user.getUserId(), request));
    }

    @GetMapping
    @Operation(summary = "List my bookings",
            description = "Only ever returns the signed-in user's bookings, optionally filtered by status.")
    public PageResponse<BookingResponse> listBookings(
            @AuthenticationPrincipal AppUserDetails user,
            @RequestParam(required = false) BookingStatus status,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return bookingService.listBookings(user.getUserId(), status, pageable);
    }

    @GetMapping("/{bookingId}")
    @Operation(summary = "Get one of my bookings")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found",
                    content = @Content(schema = @Schema(implementation = BookingResponse.class))),
            @ApiResponse(responseCode = "404",
                    description = "No such booking for this user. Also returned for another user's booking, "
                            + "so ids cannot be probed.", content = @Content)
    })
    public BookingResponse getBooking(@AuthenticationPrincipal AppUserDetails user,
                                      @PathVariable UUID bookingId) {
        return bookingService.getBooking(user.getUserId(), bookingId);
    }

    @PatchMapping("/{bookingId}/cancel")
    @Operation(summary = "Cancel a booking",
            description = "Allowed from PENDING or CONFIRMED. Already cancelled, or FAILED, returns 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cancelled",
                    content = @Content(schema = @Schema(implementation = BookingResponse.class))),
            @ApiResponse(responseCode = "404", description = "Not your booking, or no such booking", content = @Content),
            @ApiResponse(responseCode = "409", description = "The booking's current status does not allow cancelling",
                    content = @Content)
    })
    public BookingResponse cancelBooking(@AuthenticationPrincipal AppUserDetails user,
                                         @PathVariable UUID bookingId) {
        return bookingService.cancelBooking(user.getUserId(), bookingId);
    }
}
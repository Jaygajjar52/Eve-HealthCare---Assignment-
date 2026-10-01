package com.eve.diagnostics.controller;

import com.eve.diagnostics.dto.request.AttachTestToCentreRequest;
import com.eve.diagnostics.dto.request.CreateCentreRequest;
import com.eve.diagnostics.dto.response.CentreDetailsResponse;
import com.eve.diagnostics.dto.response.CentreResponse;
import com.eve.diagnostics.dto.response.CentreTestResponse;
import com.eve.diagnostics.dto.response.PageResponse;
import com.eve.diagnostics.service.CentreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/centres")
@RequiredArgsConstructor
@Tag(name = "Diagnostic centres", description = "Browse centres and the tests they offer")
public class CentreController {

    private final CentreService centreService;

    @GetMapping
    @Operation(summary = "List centres",
            description = "Public. Optionally filter by city, ignoring case. Pages are zero-based.")
    public PageResponse<CentreResponse> listCentres(
            @RequestParam(required = false) String city,
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return centreService.listCentres(city, pageable);
    }

    @GetMapping("/{centreId}")
    @Operation(summary = "Get one centre with its tests and prices")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found",
                    content = @Content(schema = @Schema(implementation = CentreDetailsResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such centre", content = @Content)
    })
    public CentreDetailsResponse getCentre(@PathVariable UUID centreId) {
        return centreService.getCentre(centreId);
    }

    @GetMapping("/{centreId}/tests")
    @Operation(summary = "List the tests a centre offers, with that centre's price")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found", content = @Content),
            @ApiResponse(responseCode = "404", description = "No such centre", content = @Content)
    })
    public PageResponse<CentreTestResponse> listTestsForCentre(
            @PathVariable UUID centreId,
            @PageableDefault(size = 50, sort = "id") Pageable pageable) {
        return centreService.listTestsForCentre(centreId, pageable);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirements
    @Operation(summary = "Create a centre (ADMIN only)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created",
                    content = @Content(schema = @Schema(implementation = CentreResponse.class))),
            @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content),
            @ApiResponse(responseCode = "403", description = "Signed in but not an ADMIN", content = @Content)
    })
    public ResponseEntity<CentreResponse> createCentre(@Valid @RequestBody CreateCentreRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(centreService.createCentre(request));
    }

    @PostMapping("/{centreId}/tests")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirements
    @Operation(summary = "Offer an existing test at a centre (ADMIN only)",
            description = "Fails with 409 if the centre already offers that test, "
                    + "400 if the price is not greater than zero, 404 if either id is unknown.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Attached",
                    content = @Content(schema = @Schema(implementation = CentreTestResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid price", content = @Content),
            @ApiResponse(responseCode = "404", description = "Unknown centre or test", content = @Content),
            @ApiResponse(responseCode = "409", description = "Centre already offers this test", content = @Content)
    })
    public ResponseEntity<CentreTestResponse> attachTest(
            @PathVariable UUID centreId,
            @Valid @RequestBody AttachTestToCentreRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(centreService.attachTestToCentre(centreId, request));
    }
}
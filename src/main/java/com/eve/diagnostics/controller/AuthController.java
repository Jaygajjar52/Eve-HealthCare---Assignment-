package com.eve.diagnostics.controller;

import com.eve.diagnostics.dto.request.LoginRequest;
import com.eve.diagnostics.dto.request.SignupRequest;
import com.eve.diagnostics.dto.response.ApiError;
import com.eve.diagnostics.dto.response.AuthResponse;
import com.eve.diagnostics.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Signup and login")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    @Operation(summary = "Register a new user",
            description = "Creates an account and returns a token so the client can continue without logging in.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created",
                    content = @Content(schema = @Schema(implementation = AuthResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content),
            @ApiResponse(responseCode = "409", description = "Email already registered", content = @Content)
    })
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.signup(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Log in",
            description = "Returns a bearer token. An unknown email and a wrong password both return 401 "
                    + "with the same message, so registered addresses cannot be discovered. "
                    + "Limited to 5 attempts a minute per IP address; further attempts return 429.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Logged in",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = AuthResponse.class),
                            examples = @ExampleObject(name = "success", value = """
                                    {
                                      "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhc2hhQGV2ZS5jb20i...",
                                      "tokenType": "Bearer",
                                      "expiresIn": 3600
                                    }"""))),
            @ApiResponse(responseCode = "401", description = "Invalid email or password",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "timestamp": "2026-10-01T10:15:30Z",
                                      "status": 401,
                                      "error": "Unauthorized",
                                      "message": "Invalid email or password.",
                                      "path": "/auth/login"
                                    }"""))),
            @ApiResponse(responseCode = "429", description = "Too many attempts, see Retry-After",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        // The remote address is only used to rate limit repeated failures. Behind
        // a reverse proxy this would be the proxy's address, which would make the
        // limit apply to everyone at once - see the note in LoginRateLimiter.
        return authService.login(request, httpRequest.getRemoteAddr());
    }
}
package com.eve.diagnostics.dto.response;

/**
 * What the client needs to make the next call: the token, the scheme to put in
 * front of it, and how long it stays valid.
 */
public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresIn) {

    public static AuthResponse bearer(String accessToken, long expiresInSeconds) {
        return new AuthResponse(accessToken, "Bearer", expiresInSeconds);
    }
}
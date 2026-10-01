package com.eve.diagnostics.security;

import com.eve.diagnostics.config.AppProperties;
import com.eve.diagnostics.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Creates and reads the HS256 JSON Web Tokens used for authentication.
 *
 * <p>The token carries the email as the subject (so Spring Security can treat
 * it as a username) plus the user id in a "userId" claim, which saves the API
 * from having to look the user up again just to know who is calling.
 *
 * <p>Tokens are signed but never encrypted: anyone can read the claims, so
 * nothing secret goes in here.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final String CLAIM_USER_ID = "userId";

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(AppProperties properties) {
        // hmacShaKeyFor refuses anything shorter than 256 bits, which turns a
        // misconfigured secret into a startup failure instead of a weak token.
        this.signingKey = Keys.hmacShaKeyFor(
                properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
        this.expirationMs = properties.getJwt().getExpirationMs();
    }

    public String generateToken(User user) {
        return generateToken(user.getEmail(), user.getId());
    }

    /** Overload for login, where we already hold an AppUserDetails rather than an entity. */
    public String generateToken(String email, UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(email)
                .claim(CLAIM_USER_ID, userId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(signingKey)
                .compact();
    }

    /** Token lifetime in seconds, reported to the client as "expiresIn". */
    public long getExpiresInSeconds() {
        return expirationMs / 1000;
    }

    /**
     * Reads the claims and checks the signature and expiry.
     *
     * @return the claims, or null if the token is malformed, tampered with, or
     *         expired. Returning null rather than throwing keeps the filter simple;
     *         an unusable token just means "not authenticated", which Spring
     *         Security then turns into a 401.
     */
    public Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            // The token itself is never logged, only the reason it was rejected.
            log.debug("Rejected JWT: {}", ex.getMessage());
            return null;
        }
    }

    public UUID extractUserId(Claims claims) {
        String rawUserId = claims.get(CLAIM_USER_ID, String.class);
        return rawUserId == null ? null : UUID.fromString(rawUserId);
    }

    public String extractEmail(Claims claims) {
        return claims.getSubject();
    }
}
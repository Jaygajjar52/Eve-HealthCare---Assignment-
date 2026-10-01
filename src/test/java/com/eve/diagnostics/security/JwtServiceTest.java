package com.eve.diagnostics.security;

import com.eve.diagnostics.config.AppProperties;
import com.eve.diagnostics.entity.User;
import com.eve.diagnostics.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "unit-test-jwt-secret-that-is-long-enough";
    private static final UUID USER_ID = UUID.randomUUID();

    private JwtService jwtService;
    private User user;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getJwt().setSecret(SECRET);
        properties.getJwt().setExpirationMs(3_600_000L);
        jwtService = new JwtService(properties);

        user = User.builder()
                .id(USER_ID)
                .name("Asha")
                .email("asha@eve.com")
                .passwordHash("$2a$10$8zvwlK2o7VehrBK5/m5kaeEWTmISj/xHIUn2BiCw1q3Tw6GTtzSFe")
                .role(Role.USER)
                .build();
    }

    @Test
    @DisplayName("generates a token whose claims carry the email and user id")
    void generatesTokenWithExpectedClaims() {
        String token = jwtService.generateToken(user);

        Claims claims = jwtService.parseClaims(token);
        assertThat(claims).isNotNull();
        assertThat(jwtService.extractEmail(claims)).isEqualTo("asha@eve.com");
        assertThat(jwtService.extractUserId(claims)).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("reports the lifetime in seconds")
    void reportsExpiryInSeconds() {
        assertThat(jwtService.getExpiresInSeconds()).isEqualTo(3600);
    }

    @Test
    @DisplayName("accepts the same user id passed directly, as login does")
    void generatesTokenFromEmailAndId() {
        String token = jwtService.generateToken("asha@eve.com", USER_ID);

        Claims claims = jwtService.parseClaims(token);
        assertThat(jwtService.extractEmail(claims)).isEqualTo("asha@eve.com");
        assertThat(jwtService.extractUserId(claims)).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("rejects an expired token")
    void rejectsExpiredToken() {
        // Signed with the real key, but already expired by an hour.
        String expired = Jwts.builder()
                .subject("asha@eve.com")
                .claim("userId", USER_ID.toString())
                .issuedAt(Date.from(Instant.now().minusSeconds(7_200)))
                .expiration(Date.from(Instant.now().minusSeconds(3_600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(jwtService.parseClaims(expired)).isNull();
    }

    @Test
    @DisplayName("rejects a token signed with a different secret")
    void rejectsForeignSignature() {
        String foreign = Jwts.builder()
                .subject("asha@eve.com")
                .claim("userId", USER_ID.toString())
                .expiration(Date.from(Instant.now().plusSeconds(3_600)))
                .signWith(Keys.hmacShaKeyFor("a-completely-different-secret-value".getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(jwtService.parseClaims(foreign)).isNull();
    }

    @Test
    @DisplayName("returns null rather than throwing for junk, so the filter stays simple")
    void returnsNullForGarbage() {
        assertThat(jwtService.parseClaims("not-a-jwt")).isNull();
        assertThat(jwtService.parseClaims("")).isNull();
        assertThat(jwtService.parseClaims("a.b.c")).isNull();
    }

    @Test
    @DisplayName("rejects a token whose payload was edited, as tampering would be")
    void rejectsTamperedPayload() {
        String token = jwtService.generateToken(user);
        String[] parts = token.split("\\.");
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"attacker@evil.com\",\"userId\":\"" + UUID.randomUUID() + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThat(jwtService.parseClaims(tampered)).isNull();
    }

    @Test
    @DisplayName("refuses to start with a secret too short to be safe for HS256")
    void refusesShortSecret() {
        AppProperties weak = new AppProperties();
        weak.getJwt().setSecret("too-short");

        // Thrown by jjwt's Keys.hmacShaKeyFor, which enforces the RFC 7518 rule
        // that an HMAC key must be at least 256 bits. Failing at startup is the
        // whole point: a weak secret must never quietly produce weak tokens.
        assertThatThrownBy(() -> new JwtService(weak))
                .isInstanceOf(io.jsonwebtoken.security.WeakKeyException.class);
    }
}
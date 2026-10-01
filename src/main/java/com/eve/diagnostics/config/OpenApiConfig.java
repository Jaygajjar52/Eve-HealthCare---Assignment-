package com.eve.diagnostics.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI / OpenAPI document.
 *
 * <p>Declaring the bearer scheme here is what puts the "Authorize" button on the
 * Swagger UI page. Without it a reader has to guess the header name and type,
 * or paste a token into every request by hand.
 */
@Configuration
public class OpenApiConfig {

    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI diagnosticsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("EVE Healthcare Diagnostics API")
                        .version("1.0.0")
                        .description("""
                                Book diagnostic tests at a centre and pay through a simulated gateway.

                                **Getting a token**

                                1. `POST /auth/signup` or `POST /auth/login` and copy `accessToken`.
                                2. Click **Authorize** above and paste the token. Swagger UI then sends
                                   `Authorization: Bearer <token>` for you.

                                A seeded admin account is available for the ADMIN-only endpoints:
                                `admin@evehealth.com` / `Admin@123`.

                                **Amounts and times**

                                * Money is always a decimal with two places, e.g. `350.00`.
                                * Times are ISO-8601 in UTC, e.g. `2026-11-01T09:30:00Z`. An offset is
                                  required; a local time without one is rejected with 400.
                                * Bookings always take their price from the centre's catalogue, so a
                                  booking request has no `amount` field.
                                """)
                        .contact(new Contact().name("EVE Healthcare Internship Project"))
                        .license(new License().name("Internal use only")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste the accessToken returned by /auth/login.")));
    }
}
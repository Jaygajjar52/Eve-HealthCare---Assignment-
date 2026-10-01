package com.eve.diagnostics.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Typed view of the "eve" block in application.yml.
 *
 * <p>Everything here comes from an environment variable with a development
 * default, so the same jar runs locally, in Docker and in CI unchanged. The
 * defaults are only ever safe because they are development values.
 */
@Component
@ConfigurationProperties(prefix = "eve")
@Getter
@Setter
public class AppProperties {

    private Jwt jwt = new Jwt();
    private Payment payment = new Payment();
    private Webhook webhook = new Webhook();

    @Getter
    @Setter
    public static class Jwt {
        private String secret;
        private long expirationMs;
    }

    @Getter
    @Setter
    public static class Payment {
        /** Probability that the mock gateway approves a payment, 0.0 to 1.0. */
        private double successRate;
    }

    @Getter
    @Setter
    public static class Webhook {
        private String secret;
    }
}
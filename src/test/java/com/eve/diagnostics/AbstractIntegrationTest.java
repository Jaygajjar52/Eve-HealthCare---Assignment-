package com.eve.diagnostics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for the integration tests.
 *
 * <p><b>Which database these run on.</b> The preferred one is a real PostgreSQL
 * 16 container from Testcontainers, because that is the database this service is
 * deployed against: it is the only way to be confident the migrations, the
 * UNIQUE constraints and the optimistic locking behave as they will in
 * production.
 *
 * <p>When no Docker daemon is reachable the tests fall back to an in-memory H2
 * in PostgreSQL compatibility mode, so {@code mvn test} still works on a machine
 * without Docker. The fallback is honest about its limits: H2 is not PostgreSQL,
 * and the tests then run against {@code db/migration-h2} - the same migrations
 * with only the type names H2 needs substituted (TIMESTAMPTZ is spelled out) and
 * the V3 expression index dropped, because H2 cannot represent it. Table
 * definitions, CHECK constraints, foreign keys and UNIQUE keys are the real ones,
 * which is what the tests actually assert on. Behaviour that only PostgreSQL has
 * - its exact constraint-violation messages, isolation, how a UNIQUE violation
 * surfaces during a concurrent race - is only genuinely covered by the
 * Testcontainers path.
 *
 * <p>Which database was chosen is logged at startup, so a run never leaves you
 * guessing what it proved.
 */
public abstract class AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AbstractIntegrationTest.class);

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16-alpine");

    /** Started once and shared by every test class, so the suite pays for one container. */
    private static final PostgreSQLContainer<?> POSTGRES;

    private static final boolean USE_REAL_POSTGRES = isDockerAvailable();

    static {
        if (USE_REAL_POSTGRES) {
            POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                    .withDatabaseName("evediagnostics_test")
                    .withUsername("test")
                    .withPassword("test");
            POSTGRES.start();
            log.info("Integration tests running against a real PostgreSQL container on port {}",
                    POSTGRES.getFirstMappedPort());
        } else {
            POSTGRES = null;
            log.warn("No Docker daemon found. Falling back to in-memory H2 in PostgreSQL mode. "
                    + "Start Docker to run the integration tests against real PostgreSQL.");
        }
    }

    /**
     * Probes for a usable Docker daemon rather than assuming one exists, because
     * the client can be installed with no engine running - which is exactly the
     * situation this fallback exists for.
     */
    private static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException ex) {
            // A broken or partial Docker install must not stop the tests running.
            return false;
        }
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        if (USE_REAL_POSTGRES) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            // The real migrations are the only ones that apply.
            registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        } else {
            registry.add("spring.datasource.url",
                    () -> "jdbc:h2:mem:eve;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "");
            registry.add("spring.flyway.locations", () -> "classpath:db/migration-h2");
        }

        registry.add("eve.jwt.secret", () -> "integration-test-jwt-secret-at-least-32-bytes");
        registry.add("eve.webhook.secret", () -> "integration-test-webhook-secret");
        // Deterministic, so no test depends on the dice.
        registry.add("eve.payment.success-rate", () -> "1.0");
    }
}
package com.eve.diagnostics.service;

import com.eve.diagnostics.exception.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A deliberately small in-memory rate limiter for POST /auth/login.
 *
 * <p>Its job is to make password guessing slow enough to be noticed: 5 attempts
 * a minute per IP means a 10,000,000-entry password list would take over two
 * years, and any real attacker trips the limit within seconds.
 *
 * <p><b>Its limits, which matter as much as what it does.</b> It is a
 * ConcurrentHashMap in one JVM, so it resets when the application restarts, does
 * nothing at all across replicas, and grows one entry per distinct IP seen.
 * Entries are cleaned up when the map is large enough to be worth it, but a
 * flood from many addresses still costs memory. It is a speed bump for a demo,
 * not a security control you would rely on in production, where the same limit
 * belongs in Bucket4j backed by Redis at the gateway. See the README.
 *
 * <p>The clock is injected so the window can be tested without sleeping.
 */
@Component
public class LoginRateLimiter {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Map<String, Window> windowsByClient = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxAttempts;
    private final Duration window;

    // Named explicitly because the class has two constructors and Spring would
    // otherwise refuse to guess which one to call.
    @Autowired
    public LoginRateLimiter(@Value("${eve.rate-limit.login.max-attempts:5}") int maxAttempts,
                            @Value("${eve.rate-limit.login.window-seconds:60}") long windowSeconds) {
        this(maxAttempts, Duration.ofSeconds(windowSeconds), Clock.systemUTC());
    }

    /** Lets a test control the clock so the window can be tested without sleeping. */
    LoginRateLimiter(int maxAttempts, Duration window, Clock clock) {
        this.maxAttempts = maxAttempts;
        this.window = window;
        this.clock = clock;
    }

    /**
     * Records an attempt and rejects the request if this client has gone over.
     *
     * @throws TooManyRequestsException when the limit is exceeded, which the
     *         advice turns into a 429.
     */
    public void checkAllowed(String clientKey) {
        Window windowForClient = windowsByClient.computeIfAbsent(clientKey, key -> new Window());
        boolean allowed = windowForClient.tryAcquire(clock.instant(), maxAttempts, window);

        if (windowsByClient.size() > CLEANUP_THRESHOLD) {
            removeExpiredWindows();
        }

        if (!allowed) {
            throw new TooManyRequestsException(
                    "Too many login attempts. Try again in a minute.");
        }
    }

    /** Visible for tests. */
    int trackedClientCount() {
        return windowsByClient.size();
    }

    /**
     * Drops windows that have expired. Cheap and only runs once the map is big,
     * because doing it on every request would cost more than it saves.
     */
    private void removeExpiredWindows() {
        Instant now = clock.instant();
        windowsByClient.values().removeIf(windowForClient -> windowForClient.hasExpired(now, window));
    }

    /**
     * One client's counters. A plain object with synchronized methods is enough
     * here; there is no need for atomics or a distributed counter.
     */
    private static final class Window {

        private int attempts;
        private Instant startedAt = Instant.EPOCH;

        synchronized boolean tryAcquire(Instant now, int maxAttempts, Duration window) {
            if (hasExpired(now, window)) {
                startedAt = now;
                attempts = 0;
            }
            attempts++;
            return attempts <= maxAttempts;
        }

        synchronized boolean hasExpired(Instant now, Duration window) {
            return !startedAt.equals(Instant.EPOCH) && now.isAfter(startedAt.plus(window));
        }
    }
}
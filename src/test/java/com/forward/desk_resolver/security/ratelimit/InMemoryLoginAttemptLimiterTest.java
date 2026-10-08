package com.forward.desk_resolver.security.ratelimit;

import com.forward.desk_resolver.security.SecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Login throttling.
 *
 * <p>Driven through the class's own {@code Clock} seam rather than by sleeping, so window and block
 * expiry are exercised in milliseconds and the tests are deterministic.
 */
class InMemoryLoginAttemptLimiterTest {

    private static final String EMAIL = "user@example.test";
    private static final String IP = "203.0.113.10";

    /** A clock the test moves by hand. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private static SecurityProperties properties(boolean enabled,
                                                 int accountFailures,
                                                 int addressFailures,
                                                 Duration window,
                                                 Duration block,
                                                 int maxKeys) {
        return new SecurityProperties(null, null, null,
                new SecurityProperties.RateLimit(
                        enabled, accountFailures, addressFailures, window, block, maxKeys, false),
                true);
    }

    private static SecurityProperties defaults() {
        return properties(true, 3, 5, Duration.ofMinutes(15), Duration.ofMinutes(15), 1_000);
    }

    @Test
    @DisplayName("attempts below the threshold are allowed")
    void belowThresholdIsAllowed() {
        MovableClock clock = new MovableClock();
        var limiter = new InMemoryLoginAttemptLimiter(defaults(), clock);

        limiter.recordFailure(EMAIL, IP);
        limiter.recordFailure(EMAIL, IP);

        assertThatCode(() -> limiter.checkAllowed(EMAIL, IP)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the account threshold blocks and reports a retry delay")
    void accountThresholdBlocks() {
        MovableClock clock = new MovableClock();
        var limiter = new InMemoryLoginAttemptLimiter(defaults(), clock);

        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(EMAIL, IP);
        }

        assertThatThrownBy(() -> limiter.checkAllowed(EMAIL, IP))
                .isInstanceOf(TooManyLoginAttemptsException.class)
                .extracting(e -> ((TooManyLoginAttemptsException) e).getRetryAfterSeconds())
                .satisfies(seconds -> assertThat((Long) seconds).isBetween(1L, 900L));
    }

    /**
     * The account dimension is keyed on the submitted email, so a block must not follow the caller to a
     * different address - otherwise spreading attempts across hosts would buy a fresh budget against the
     * same password.
     */
    @Test
    @DisplayName("an account block applies from any address")
    void accountBlockIsNotPerAddress() {
        MovableClock clock = new MovableClock();
        var limiter = new InMemoryLoginAttemptLimiter(defaults(), clock);

        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(EMAIL, IP);
        }

        assertThatThrownBy(() -> limiter.checkAllowed(EMAIL, "198.51.100.7"))
                .isInstanceOf(TooManyLoginAttemptsException.class);
    }

    @Test
    @DisplayName("the address threshold blocks even when each account is below its own limit")
    void addressThresholdBlocks() {
        MovableClock clock = new MovableClock();
        var limiter = new InMemoryLoginAttemptLimiter(defaults(), clock);

        // Five distinct accounts, one failure each: no account reaches 3, the address reaches 5.
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("user" + i + "@example.test", IP);
        }

        assertThatThrownBy(() -> limiter.checkAllowed("fresh@example.test", IP))
                .isInstanceOf(TooManyLoginAttemptsException.class);
    }

    /**
     * Counting the submitted string rather than a resolved account is a security property: if only real
     * accounts were throttled, a 429 would prove an account exists and hand back the enumeration oracle
     * the uniform 401 exists to remove.
     */
    @Test
    @DisplayName("email keys are normalised, so case and padding cannot split the budget")
    void emailIsNormalised() {
        MovableClock clock = new MovableClock();
        var limiter = new InMemoryLoginAttemptLimiter(defaults(), clock);

        limiter.recordFailure("User@Example.Test", IP);
        limiter.recordFailure("  user@example.test  ", IP);
        limiter.recordFailure("USER@EXAMPLE.TEST", IP);

        assertThat(limiter.trackedAccountKeys()).isEqualTo(1);
        assertThatThrownBy(() -> limiter.checkAllowed(EMAIL, IP))
                .isInstanceOf(TooManyLoginAttemptsException.class);
    }

    @Test
    @DisplayName("a successful login clears earlier fumbles")
    void successClearsFailures() {
        MovableClock clock = new MovableClock();
        var limiter = new InMemoryLoginAttemptLimiter(defaults(), clock);

        limiter.recordFailure(EMAIL, IP);
        limiter.recordFailure(EMAIL, IP);
        limiter.recordSuccess(EMAIL, IP);

        for (int i = 0; i < 2; i++) {
            limiter.recordFailure(EMAIL, IP);
        }
        assertThatCode(() -> limiter.checkAllowed(EMAIL, IP)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("failures outside the window do not accumulate")
    void windowSlides() {
        MovableClock clock = new MovableClock();
        var limiter = new InMemoryLoginAttemptLimiter(defaults(), clock);

        limiter.recordFailure(EMAIL, IP);
        limiter.recordFailure(EMAIL, IP);
        clock.advance(Duration.ofMinutes(16));
        limiter.recordFailure(EMAIL, IP);

        assertThatCode(() -> limiter.checkAllowed(EMAIL, IP)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a block lifts once it expires")
    void blockExpires() {
        MovableClock clock = new MovableClock();
        var limiter = new InMemoryLoginAttemptLimiter(defaults(), clock);

        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(EMAIL, IP);
        }
        assertThatThrownBy(() -> limiter.checkAllowed(EMAIL, IP))
                .isInstanceOf(TooManyLoginAttemptsException.class);

        clock.advance(Duration.ofMinutes(16));

        assertThatCode(() -> limiter.checkAllowed(EMAIL, IP)).doesNotThrowAnyException();
    }

    /**
     * Regression test for an indefinite lockout.
     *
     * <p>{@code registerFailure} used to reset the counter only when the sliding <em>window</em> had
     * elapsed. That happens to be equivalent while {@code blockDuration >= window}, which the 15m/15m
     * defaults satisfy exactly - so the defect was invisible until someone tuned the thresholds.
     *
     * <p>With a 5-minute block inside a 30-minute window, the first failure after the block expires
     * lands while the old count is still recorded, so {@code failures >= maxFailures} is immediately
     * true again and the account is re-blocked for another 5 minutes. Repeat and the lockout never
     * ends - which is exactly the denial-of-service that {@code SecurityProperties.RateLimit}
     * documents as unacceptable, since anyone knowing a colleague's email could hold them out
     * permanently.
     */
    @Test
    @DisplayName("an expired block resets the counter even when the window has not elapsed")
    void expiredBlockResetsCounterIndependentlyOfWindow() {
        MovableClock clock = new MovableClock();
        var limiter = new InMemoryLoginAttemptLimiter(
                properties(true, 3, 50, Duration.ofMinutes(30), Duration.ofMinutes(5), 1_000),
                clock);

        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(EMAIL, IP);
        }
        assertThatThrownBy(() -> limiter.checkAllowed(EMAIL, IP))
                .isInstanceOf(TooManyLoginAttemptsException.class);

        // Block over, window still open.
        clock.advance(Duration.ofMinutes(6));
        assertThatCode(() -> limiter.checkAllowed(EMAIL, IP)).doesNotThrowAnyException();

        // One more failure must start a fresh budget, not re-trip the old one.
        limiter.recordFailure(EMAIL, IP);
        assertThatCode(() -> limiter.checkAllowed(EMAIL, IP))
                .describedAs("a single failure after an expired block must not re-block the account")
                .doesNotThrowAnyException();

        // And the fresh budget is a full one.
        limiter.recordFailure(EMAIL, IP);
        assertThatCode(() -> limiter.checkAllowed(EMAIL, IP)).doesNotThrowAnyException();
        limiter.recordFailure(EMAIL, IP);
        assertThatThrownBy(() -> limiter.checkAllowed(EMAIL, IP))
                .isInstanceOf(TooManyLoginAttemptsException.class);
    }

    @Test
    @DisplayName("disabling the limiter stops all tracking")
    void disabledLimiterDoesNothing() {
        MovableClock clock = new MovableClock();
        var limiter = new InMemoryLoginAttemptLimiter(
                properties(false, 1, 1, Duration.ofMinutes(15), Duration.ofMinutes(15), 1_000), clock);

        for (int i = 0; i < 20; i++) {
            limiter.recordFailure(EMAIL, IP);
        }

        assertThatCode(() -> limiter.checkAllowed(EMAIL, IP)).doesNotThrowAnyException();
        assertThat(limiter.trackedAccountKeys()).isZero();
    }

    /**
     * Keys are attacker-supplied, so unbounded growth would be a denial-of-service in the component
     * whose job is to prevent one.
     */
    @Test
    @DisplayName("tracked keys stay bounded under a high-cardinality attack")
    void capacityIsEnforced() {
        MovableClock clock = new MovableClock();
        var limiter = new InMemoryLoginAttemptLimiter(
                properties(true, 3, 5, Duration.ofMinutes(15), Duration.ofMinutes(15), 10), clock);

        for (int i = 0; i < 200; i++) {
            limiter.recordFailure("attacker" + i + "@example.test", "198.51.100." + (i % 200));
        }

        assertThat(limiter.trackedAccountKeys()).isLessThanOrEqualTo(10);
        assertThat(limiter.trackedAddressKeys()).isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("clearAll releases every active block")
    void clearAllReleasesBlocks() {
        MovableClock clock = new MovableClock();
        var limiter = new InMemoryLoginAttemptLimiter(defaults(), clock);

        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(EMAIL, IP);
        }
        assertThatThrownBy(() -> limiter.checkAllowed(EMAIL, IP))
                .isInstanceOf(TooManyLoginAttemptsException.class);

        limiter.clearAll();

        assertThatCode(() -> limiter.checkAllowed(EMAIL, IP)).doesNotThrowAnyException();
        assertThat(limiter.trackedAccountKeys()).isZero();
        assertThat(limiter.trackedAddressKeys()).isZero();
    }

    @Test
    @DisplayName("a null email is tracked without blowing up")
    void nullEmailIsTolerated() {
        MovableClock clock = new MovableClock();
        var limiter = new InMemoryLoginAttemptLimiter(defaults(), clock);

        assertThatCode(() -> {
            limiter.recordFailure(null, null);
            limiter.checkAllowed(null, null);
        }).doesNotThrowAnyException();
    }
}

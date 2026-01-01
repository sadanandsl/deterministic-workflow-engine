package com.orchestrator.core.model;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Configuration for retry behavior.
 * Immutable and reusable across task definitions.
 * 
 * Invariants:
 * - maxAttempts >= 1
 * - initialBackoff > 0
 * - maxBackoff >= initialBackoff
 * - backoffMultiplier >= 1.0
 * - jitterFactor in [0.0, 1.0]
 * - retryableErrors and nonRetryableErrors are disjoint
 */
public record RetryPolicy(
    int maxAttempts,
    Duration initialBackoff,
    Duration maxBackoff,
    double backoffMultiplier,
    double jitterFactor,
    Set<String> retryableErrors,
    Set<String> nonRetryableErrors
) {
    /**
     * Default retry policy: 3 attempts, exponential backoff starting at 1s.
     */
    public static RetryPolicy defaultPolicy() {
        return new RetryPolicy(
            3,
            Duration.ofSeconds(1),
            Duration.ofMinutes(5),
            2.0,
            0.1,
            Set.of(),
            Set.of()
        );
    }

    /**
     * No retry policy: single attempt only.
     */
    public static RetryPolicy noRetry() {
        return new RetryPolicy(
            1,
            Duration.ZERO,
            Duration.ZERO,
            1.0,
            0.0,
            Set.of(),
            Set.of()
        );
    }

    /**
     * Aggressive retry policy: many attempts with short backoff.
     */
    public static RetryPolicy aggressive() {
        return new RetryPolicy(
            10,
            Duration.ofMillis(100),
            Duration.ofSeconds(30),
            1.5,
            0.2,
            Set.of(),
            Set.of()
        );
    }

    /**
     * Conservative retry policy: few attempts with long backoff.
     */
    public static RetryPolicy conservative() {
        return new RetryPolicy(
            3,
            Duration.ofSeconds(30),
            Duration.ofMinutes(30),
            3.0,
            0.3,
            Set.of(),
            Set.of()
        );
    }

    /**
     * Compute the backoff duration for a given attempt number.
     * 
     * @param attemptNumber 1-indexed attempt number
     * @return Duration to wait before next attempt
     */
    public Duration computeBackoff(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("Attempt number must be >= 1");
        }
        
        // Base backoff: initialBackoff * (multiplier ^ (attempt - 1))
        double baseBackoffMs = initialBackoff.toMillis() * 
            Math.pow(backoffMultiplier, attemptNumber - 1);
        
        // Cap at max backoff
        double cappedBackoffMs = Math.min(baseBackoffMs, maxBackoff.toMillis());
        
        // Apply jitter: backoff * (1 - jitter + random(0, 2*jitter))
        double jitterRange = cappedBackoffMs * jitterFactor;
        double jitteredBackoffMs = cappedBackoffMs - jitterRange + 
            ThreadLocalRandom.current().nextDouble() * 2 * jitterRange;
        
        return Duration.ofMillis((long) jitteredBackoffMs);
    }

    /**
     * Check if the given error code should trigger a retry.
     * 
     * @param errorCode The error code from the failed task
     * @return true if retry should be attempted
     */
    public boolean shouldRetry(String errorCode) {
        // If non-retryable list has the error, never retry
        if (!nonRetryableErrors.isEmpty() && nonRetryableErrors.contains(errorCode)) {
            return false;
        }
        
        // If retryable list is empty, retry everything not in non-retryable
        if (retryableErrors.isEmpty()) {
            return true;
        }
        
        // If retryable list is specified, only retry those errors
        return retryableErrors.contains(errorCode);
    }

    /**
     * Check if more attempts are available.
     * 
     * @param currentAttempt Current attempt number (1-indexed)
     * @return true if more attempts can be made
     */
    public boolean hasMoreAttempts(int currentAttempt) {
        return currentAttempt < maxAttempts;
    }

    /**
     * Builder for RetryPolicy.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int maxAttempts = 3;
        private Duration initialBackoff = Duration.ofSeconds(1);
        private Duration maxBackoff = Duration.ofMinutes(5);
        private double backoffMultiplier = 2.0;
        private double jitterFactor = 0.1;
        private Set<String> retryableErrors = Set.of();
        private Set<String> nonRetryableErrors = Set.of();

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
            return this;
        }

        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
            return this;
        }

        public Builder backoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
            return this;
        }

        public Builder jitterFactor(double jitterFactor) {
            this.jitterFactor = jitterFactor;
            return this;
        }

        public Builder retryableErrors(Set<String> retryableErrors) {
            this.retryableErrors = retryableErrors;
            return this;
        }

        public Builder nonRetryableErrors(Set<String> nonRetryableErrors) {
            this.nonRetryableErrors = nonRetryableErrors;
            return this;
        }

        public RetryPolicy build() {
            return new RetryPolicy(
                maxAttempts, initialBackoff, maxBackoff,
                backoffMultiplier, jitterFactor,
                retryableErrors, nonRetryableErrors
            );
        }
    }
}

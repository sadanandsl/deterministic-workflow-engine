package com.orchestrator.core.test;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Failure injection utilities for chaos testing.
 * Use these to simulate various failure scenarios in tests.
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * FailureInjector injector = FailureInjector.builder()
 *     .withFailureRate(0.3)  // 30% failure rate
 *     .withDelayRange(Duration.ofMillis(100), Duration.ofMillis(500))
 *     .build();
 *     
 * // In your test
 * injector.maybeThrow(new RuntimeException("Injected failure"));
 * injector.maybeDelay();
 * }</pre>
 */
public class FailureInjector {

    private final double failureRate;
    private final Duration minDelay;
    private final Duration maxDelay;
    private final Random random;
    private final AtomicBoolean enabled;
    private final AtomicInteger failureCount;
    private final AtomicInteger delayCount;

    private FailureInjector(Builder builder) {
        this.failureRate = builder.failureRate;
        this.minDelay = builder.minDelay;
        this.maxDelay = builder.maxDelay;
        this.random = new Random(builder.seed);
        this.enabled = new AtomicBoolean(true);
        this.failureCount = new AtomicInteger(0);
        this.delayCount = new AtomicInteger(0);
    }

    /**
     * Create a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create an injector that always fails.
     */
    public static FailureInjector alwaysFail() {
        return builder().withFailureRate(1.0).build();
    }

    /**
     * Create an injector that never fails.
     */
    public static FailureInjector neverFail() {
        return builder().withFailureRate(0.0).build();
    }

    /**
     * Enable failure injection.
     */
    public void enable() {
        enabled.set(true);
    }

    /**
     * Disable failure injection.
     */
    public void disable() {
        enabled.set(false);
    }

    /**
     * Check if failure injection is enabled.
     */
    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * Maybe throw an exception based on the failure rate.
     */
    public void maybeThrow(RuntimeException exception) {
        if (shouldFail()) {
            failureCount.incrementAndGet();
            throw exception;
        }
    }

    /**
     * Maybe throw an exception from a supplier.
     */
    public <T extends RuntimeException> void maybeThrow(Supplier<T> exceptionSupplier) {
        if (shouldFail()) {
            failureCount.incrementAndGet();
            throw exceptionSupplier.get();
        }
    }

    /**
     * Maybe introduce a delay based on configured range.
     */
    public void maybeDelay() {
        if (shouldFail() && minDelay != null && maxDelay != null) {
            delayCount.incrementAndGet();
            long delayMs = minDelay.toMillis() + 
                random.nextLong(maxDelay.toMillis() - minDelay.toMillis());
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Maybe return a failure result.
     */
    public <T> T maybeReturnNull() {
        if (shouldFail()) {
            failureCount.incrementAndGet();
            return null;
        }
        return null; // This is just for failure injection, actual return handled elsewhere
    }

    /**
     * Execute action or throw exception based on failure rate.
     */
    public <T> T maybeFailOrExecute(Supplier<T> action, RuntimeException failure) {
        if (shouldFail()) {
            failureCount.incrementAndGet();
            throw failure;
        }
        return action.get();
    }

    /**
     * Check if this invocation should fail.
     */
    public boolean shouldFail() {
        return enabled.get() && random.nextDouble() < failureRate;
    }

    /**
     * Get the number of failures injected.
     */
    public int getFailureCount() {
        return failureCount.get();
    }

    /**
     * Get the number of delays injected.
     */
    public int getDelayCount() {
        return delayCount.get();
    }

    /**
     * Reset failure and delay counters.
     */
    public void resetCounters() {
        failureCount.set(0);
        delayCount.set(0);
    }

    /**
     * Builder for FailureInjector.
     */
    public static class Builder {
        private double failureRate = 0.1; // 10% default
        private Duration minDelay = null;
        private Duration maxDelay = null;
        private long seed = System.currentTimeMillis();

        /**
         * Set the failure rate (0.0 to 1.0).
         */
        public Builder withFailureRate(double rate) {
            if (rate < 0.0 || rate > 1.0) {
                throw new IllegalArgumentException("Failure rate must be between 0.0 and 1.0");
            }
            this.failureRate = rate;
            return this;
        }

        /**
         * Set the delay range for simulated latency.
         */
        public Builder withDelayRange(Duration min, Duration max) {
            this.minDelay = min;
            this.maxDelay = max;
            return this;
        }

        /**
         * Set the random seed for reproducible tests.
         */
        public Builder withSeed(long seed) {
            this.seed = seed;
            return this;
        }

        /**
         * Build the FailureInjector.
         */
        public FailureInjector build() {
            return new FailureInjector(this);
        }
    }
}

package com.orchestrator.core.test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Time controller for testing time-dependent behavior.
 * Allows tests to control the flow of time without actual waiting.
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * TimeController time = new TimeController();
 * 
 * Instant start = time.now();
 * time.advance(Duration.ofMinutes(5));
 * Instant after = time.now();
 * 
 * assertThat(Duration.between(start, after)).isEqualTo(Duration.ofMinutes(5));
 * }</pre>
 */
public class TimeController {

    private final AtomicReference<Instant> currentTime;
    private final boolean useRealTime;

    /**
     * Create a time controller starting at the current time.
     */
    public TimeController() {
        this(Instant.now(), false);
    }

    /**
     * Create a time controller starting at a specific time.
     */
    public TimeController(Instant startTime) {
        this(startTime, false);
    }

    /**
     * Create a time controller that optionally uses real time.
     */
    public TimeController(Instant startTime, boolean useRealTime) {
        this.currentTime = new AtomicReference<>(startTime);
        this.useRealTime = useRealTime;
    }

    /**
     * Get the current time.
     */
    public Instant now() {
        if (useRealTime) {
            return Instant.now();
        }
        return currentTime.get();
    }

    /**
     * Advance time by a duration.
     */
    public void advance(Duration duration) {
        if (useRealTime) {
            throw new UnsupportedOperationException("Cannot advance time in real-time mode");
        }
        currentTime.updateAndGet(t -> t.plus(duration));
    }

    /**
     * Advance time by milliseconds.
     */
    public void advanceMillis(long millis) {
        advance(Duration.ofMillis(millis));
    }

    /**
     * Advance time by seconds.
     */
    public void advanceSeconds(long seconds) {
        advance(Duration.ofSeconds(seconds));
    }

    /**
     * Advance time by minutes.
     */
    public void advanceMinutes(long minutes) {
        advance(Duration.ofMinutes(minutes));
    }

    /**
     * Set time to a specific instant.
     */
    public void setTime(Instant newTime) {
        if (useRealTime) {
            throw new UnsupportedOperationException("Cannot set time in real-time mode");
        }
        currentTime.set(newTime);
    }

    /**
     * Reset time to the original start time.
     */
    public void reset(Instant startTime) {
        currentTime.set(startTime);
    }

    /**
     * Check if a duration has elapsed since a given instant.
     */
    public boolean hasElapsed(Instant since, Duration duration) {
        return Duration.between(since, now()).compareTo(duration) >= 0;
    }

    /**
     * Get the duration since an instant.
     */
    public Duration durationSince(Instant since) {
        return Duration.between(since, now());
    }

    /**
     * Check if using real time.
     */
    public boolean isRealTime() {
        return useRealTime;
    }

    /**
     * Create a real-time controller.
     */
    public static TimeController realTime() {
        return new TimeController(Instant.now(), true);
    }

    /**
     * Create a frozen time controller (time doesn't advance automatically).
     */
    public static TimeController frozen() {
        return new TimeController(Instant.now(), false);
    }

    /**
     * Create a frozen time controller at a specific time.
     */
    public static TimeController frozenAt(Instant time) {
        return new TimeController(time, false);
    }
}

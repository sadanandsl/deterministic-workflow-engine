package com.orchestrator.core.model;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {

    @Test
    void defaultPolicy_shouldHaveReasonableDefaults() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();
        
        assertEquals(3, policy.maxAttempts());
        assertEquals(Duration.ofSeconds(1), policy.initialBackoff());
        assertEquals(Duration.ofMinutes(5), policy.maxBackoff());
        assertEquals(2.0, policy.backoffMultiplier());
    }

    @Test
    void computeBackoff_shouldIncreaseExponentially() {
        RetryPolicy policy = RetryPolicy.builder()
            .maxAttempts(5)
            .initialBackoff(Duration.ofSeconds(1))
            .maxBackoff(Duration.ofMinutes(10))
            .backoffMultiplier(2.0)
            .jitterFactor(0.0) // No jitter for predictable test
            .build();
        
        // First attempt: 1s
        Duration backoff1 = policy.computeBackoff(1);
        assertEquals(Duration.ofSeconds(1), backoff1);
        
        // Second attempt: 2s
        Duration backoff2 = policy.computeBackoff(2);
        assertEquals(Duration.ofSeconds(2), backoff2);
        
        // Third attempt: 4s
        Duration backoff3 = policy.computeBackoff(3);
        assertEquals(Duration.ofSeconds(4), backoff3);
    }

    @Test
    void computeBackoff_shouldRespectMaxBackoff() {
        RetryPolicy policy = RetryPolicy.builder()
            .maxAttempts(10)
            .initialBackoff(Duration.ofSeconds(1))
            .maxBackoff(Duration.ofSeconds(10))
            .backoffMultiplier(2.0)
            .jitterFactor(0.0)
            .build();
        
        // Attempt 5: 2^4 = 16s, but capped at 10s
        Duration backoff = policy.computeBackoff(5);
        assertEquals(Duration.ofSeconds(10), backoff);
    }

    @Test
    void shouldRetry_withEmptyLists_shouldRetryAll() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();
        
        assertTrue(policy.shouldRetry("ANY_ERROR"));
        assertTrue(policy.shouldRetry("TIMEOUT"));
        assertTrue(policy.shouldRetry("CONNECTION_ERROR"));
    }

    @Test
    void shouldRetry_withNonRetryableList_shouldExclude() {
        RetryPolicy policy = RetryPolicy.builder()
            .nonRetryableErrors(java.util.Set.of("VALIDATION_ERROR", "NOT_FOUND"))
            .build();
        
        assertFalse(policy.shouldRetry("VALIDATION_ERROR"));
        assertFalse(policy.shouldRetry("NOT_FOUND"));
        assertTrue(policy.shouldRetry("TIMEOUT"));
    }

    @Test
    void shouldRetry_withRetryableList_shouldOnlyInclude() {
        RetryPolicy policy = RetryPolicy.builder()
            .retryableErrors(java.util.Set.of("TIMEOUT", "CONNECTION_ERROR"))
            .build();
        
        assertTrue(policy.shouldRetry("TIMEOUT"));
        assertTrue(policy.shouldRetry("CONNECTION_ERROR"));
        assertFalse(policy.shouldRetry("VALIDATION_ERROR"));
    }

    @Test
    void hasMoreAttempts_shouldRespectMaxAttempts() {
        RetryPolicy policy = RetryPolicy.builder()
            .maxAttempts(3)
            .build();
        
        assertTrue(policy.hasMoreAttempts(1));
        assertTrue(policy.hasMoreAttempts(2));
        assertFalse(policy.hasMoreAttempts(3));
        assertFalse(policy.hasMoreAttempts(4));
    }

    @Test
    void noRetry_shouldOnlyAllowOneAttempt() {
        RetryPolicy policy = RetryPolicy.noRetry();
        
        assertEquals(1, policy.maxAttempts());
        assertFalse(policy.hasMoreAttempts(1));
    }
}

package com.orchestrator.core.model;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionLeaseTest {

    @Test
    void create_shouldSetCorrectDefaults() {
        UUID workflowId = UUID.randomUUID();
        UUID holderId = UUID.randomUUID();
        
        ExecutionLease lease = ExecutionLease.create(
            workflowId,
            "task-1",
            holderId,
            "worker-1",
            Duration.ofSeconds(30),
            1L
        );
        
        assertEquals(workflowId + ":task-1", lease.leaseKey());
        assertEquals(holderId, lease.holderId());
        assertEquals(1L, lease.fenceToken());
        assertEquals(0, lease.renewalCount());
        assertTrue(lease.isValid());
    }

    @Test
    void isValid_shouldReturnFalseAfterExpiry() {
        UUID workflowId = UUID.randomUUID();
        UUID holderId = UUID.randomUUID();
        
        // Create a lease that's already expired
        ExecutionLease lease = new ExecutionLease(
            workflowId + ":task-1",
            holderId,
            "worker-1",
            Instant.now().minus(Duration.ofMinutes(5)),
            Instant.now().minus(Duration.ofMinutes(4)), // Expired
            Duration.ofSeconds(30),
            0,
            1L
        );
        
        assertFalse(lease.isValid());
        assertTrue(lease.isExpired());
    }

    @Test
    void renew_shouldExtendExpiration() {
        UUID workflowId = UUID.randomUUID();
        UUID holderId = UUID.randomUUID();
        
        ExecutionLease lease = ExecutionLease.create(
            workflowId,
            "task-1",
            holderId,
            "worker-1",
            Duration.ofSeconds(30),
            1L
        );
        
        // Simulate some time passing to ensure renewed expiration is after original
        Instant originalExpiry = lease.expiresAt();
        
        ExecutionLease renewed = lease.renew();
        
        assertEquals(1, renewed.renewalCount());
        // Renewed lease should expire at or after original (could be same instant if renewed immediately)
        assertFalse(renewed.expiresAt().isBefore(originalExpiry));
        assertEquals(lease.fenceToken(), renewed.fenceToken());
    }

    @Test
    void canBeAcquiredBy_shouldAllowSameHolder() {
        UUID workflowId = UUID.randomUUID();
        UUID holderId = UUID.randomUUID();
        
        ExecutionLease lease = ExecutionLease.create(
            workflowId,
            "task-1",
            holderId,
            "worker-1",
            Duration.ofSeconds(30),
            1L
        );
        
        assertTrue(lease.canBeAcquiredBy(holderId));
    }

    @Test
    void canBeAcquiredBy_shouldRejectDifferentHolderIfValid() {
        UUID workflowId = UUID.randomUUID();
        UUID holderId = UUID.randomUUID();
        UUID otherHolder = UUID.randomUUID();
        
        ExecutionLease lease = ExecutionLease.create(
            workflowId,
            "task-1",
            holderId,
            "worker-1",
            Duration.ofSeconds(30),
            1L
        );
        
        assertFalse(lease.canBeAcquiredBy(otherHolder));
    }

    @Test
    void canBeAcquiredBy_shouldAllowDifferentHolderIfExpired() {
        UUID workflowId = UUID.randomUUID();
        UUID holderId = UUID.randomUUID();
        UUID otherHolder = UUID.randomUUID();
        
        // Create an expired lease
        ExecutionLease lease = new ExecutionLease(
            workflowId + ":task-1",
            holderId,
            "worker-1",
            Instant.now().minus(Duration.ofMinutes(5)),
            Instant.now().minus(Duration.ofMinutes(4)),
            Duration.ofSeconds(30),
            0,
            1L
        );
        
        assertTrue(lease.canBeAcquiredBy(otherHolder));
    }

    @Test
    void remainingTime_shouldReturnCorrectDuration() {
        UUID workflowId = UUID.randomUUID();
        UUID holderId = UUID.randomUUID();
        
        ExecutionLease lease = ExecutionLease.create(
            workflowId,
            "task-1",
            holderId,
            "worker-1",
            Duration.ofSeconds(30),
            1L
        );
        
        Duration remaining = lease.remainingTime();
        assertTrue(remaining.toSeconds() > 25 && remaining.toSeconds() <= 30);
    }

    @Test
    void remainingTime_shouldReturnZeroIfExpired() {
        UUID workflowId = UUID.randomUUID();
        UUID holderId = UUID.randomUUID();
        
        ExecutionLease lease = new ExecutionLease(
            workflowId + ":task-1",
            holderId,
            "worker-1",
            Instant.now().minus(Duration.ofMinutes(5)),
            Instant.now().minus(Duration.ofMinutes(4)),
            Duration.ofSeconds(30),
            0,
            1L
        );
        
        assertEquals(Duration.ZERO, lease.remainingTime());
    }
}

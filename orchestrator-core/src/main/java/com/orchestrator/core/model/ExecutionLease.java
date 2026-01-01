package com.orchestrator.core.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Distributed lock for task execution.
 * Ensures exactly-once execution semantics.
 * 
 * Primary Key: leaseKey
 * 
 * Invariants:
 * - Only one active lease per leaseKey
 * - fenceToken increases on every acquisition
 * - Lease expires automatically if not renewed
 */
public record ExecutionLease(
    // Primary key: {workflowInstanceId}:{taskId}
    String leaseKey,
    
    // Ownership
    UUID holderId,
    String holderAddress,
    
    // Timing
    Instant acquiredAt,
    Instant expiresAt,
    Duration leaseDuration,
    int renewalCount,
    
    // Fencing
    long fenceToken
) {
    /**
     * Default lease duration: 30 seconds.
     */
    public static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(30);

    /**
     * Create a lease key from workflow instance and task IDs.
     */
    public static String createLeaseKey(UUID workflowInstanceId, String taskId) {
        return workflowInstanceId.toString() + ":" + taskId;
    }

    /**
     * Create a new lease.
     */
    public static ExecutionLease create(
            UUID workflowInstanceId,
            String taskId,
            UUID holderId,
            String holderAddress,
            Duration duration,
            long fenceToken) {
        Instant now = Instant.now();
        return new ExecutionLease(
            createLeaseKey(workflowInstanceId, taskId),
            holderId,
            holderAddress,
            now,
            now.plus(duration),
            duration,
            0,
            fenceToken
        );
    }

    /**
     * Check if the lease is still valid (not expired).
     */
    public boolean isValid() {
        return expiresAt.isAfter(Instant.now());
    }

    /**
     * Check if the lease has expired.
     */
    public boolean isExpired() {
        return !isValid();
    }

    /**
     * Get the remaining time on this lease.
     */
    public Duration remainingTime() {
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * Create a renewed lease with extended expiration.
     */
    public ExecutionLease renew() {
        return new ExecutionLease(
            leaseKey,
            holderId,
            holderAddress,
            acquiredAt,
            Instant.now().plus(leaseDuration),
            leaseDuration,
            renewalCount + 1,
            fenceToken
        );
    }

    /**
     * Check if this lease can be acquired by the given holder.
     * A lease can be acquired if:
     * 1. No current holder (new lease)
     * 2. Current holder is the same (renewal)
     * 3. Current lease is expired (takeover)
     */
    public boolean canBeAcquiredBy(UUID requestingHolder) {
        if (holderId == null) {
            return true;
        }
        if (holderId.equals(requestingHolder)) {
            return true;
        }
        return isExpired();
    }
}

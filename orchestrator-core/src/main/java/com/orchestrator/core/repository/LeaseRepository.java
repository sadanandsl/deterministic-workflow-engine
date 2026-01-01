package com.orchestrator.core.repository;

import com.orchestrator.core.model.ExecutionLease;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for ExecutionLease persistence.
 * Handles distributed locking for exactly-once task execution.
 */
public interface LeaseRepository {

    /**
     * Try to acquire a lease for a task.
     * 
     * @param lease The lease to acquire
     * @return true if lease was acquired, false if already held by another worker
     */
    boolean tryAcquire(ExecutionLease lease);

    /**
     * Renew an existing lease.
     * 
     * @param leaseKey The lease key
     * @param holderId The current holder ID
     * @param newExpiresAt New expiration time
     * @return true if renewal succeeded, false if lease was lost
     */
    boolean renew(String leaseKey, UUID holderId, Instant newExpiresAt);

    /**
     * Release a lease.
     * 
     * @param leaseKey The lease key
     * @param holderId The current holder ID
     * @return true if release succeeded, false if lease was already released or taken
     */
    boolean release(String leaseKey, UUID holderId);

    /**
     * Force release a lease (for recovery).
     * Increments the fence token.
     * 
     * @param leaseKey The lease key
     * @return The new fence token
     */
    long forceRelease(String leaseKey);

    /**
     * Find a lease by key.
     * 
     * @param leaseKey The lease key
     * @return The lease if found
     */
    Optional<ExecutionLease> findByKey(String leaseKey);

    /**
     * Find all leases held by a worker.
     * 
     * @param holderId The worker ID
     * @return All leases held by the worker
     */
    List<ExecutionLease> findByHolder(UUID holderId);

    /**
     * Find all expired leases.
     * 
     * @param now Current time
     * @param limit Maximum number of results
     * @return Expired leases
     */
    List<ExecutionLease> findExpired(Instant now, int limit);

    /**
     * Get the current fence token for a lease.
     * 
     * @param leaseKey The lease key
     * @return Current fence token (0 if lease doesn't exist)
     */
    long getFenceToken(String leaseKey);

    /**
     * Check if a fence token is valid (matches current token).
     * 
     * @param leaseKey The lease key
     * @param fenceToken The fence token to validate
     * @return true if the fence token is current
     */
    boolean validateFenceToken(String leaseKey, long fenceToken);

    /**
     * Delete expired leases older than the given time.
     * 
     * @param expiredBefore Delete leases expired before this time
     * @return Number of deleted leases
     */
    int deleteExpiredBefore(Instant expiredBefore);
}

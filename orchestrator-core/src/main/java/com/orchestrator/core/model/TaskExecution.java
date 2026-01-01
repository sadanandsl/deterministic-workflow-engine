package com.orchestrator.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * A single attempt to execute a task.
 * Multiple executions may exist for the same task (retries).
 * 
 * Primary Key: executionId
 * Unique Constraint: idempotencyKey
 * 
 * Invariants:
 * - idempotencyKey is globally unique
 * - leaseHolder set iff state == RUNNING
 * - output set iff state == COMPLETED
 * - errorMessage set iff state == FAILED
 */
public record TaskExecution(
    // Primary key
    UUID executionId,
    
    // Foreign keys
    UUID workflowInstanceId,
    String taskId,
    
    // Idempotency
    String idempotencyKey,
    int attemptNumber,
    
    // State
    TaskState state,
    
    // Lease (for exactly-once)
    UUID leaseHolder,
    Instant leaseExpiresAt,
    long fenceToken,
    
    // Data
    JsonNode input,
    JsonNode output,
    String errorMessage,
    String errorCode,
    String stackTrace,
    
    // Timing
    Instant scheduledAt,
    Instant startedAt,
    Instant completedAt,
    
    // Tracing
    String traceId,
    String spanId,
    String workerId
) {
    /**
     * Create a new task execution in PENDING state.
     */
    public static TaskExecution create(
            UUID workflowInstanceId,
            String taskId,
            int attemptNumber,
            JsonNode input,
            String traceId) {
        
        String idempotencyKey = workflowInstanceId + ":" + taskId + ":" + attemptNumber;
        
        return new TaskExecution(
            UUID.randomUUID(),
            workflowInstanceId,
            taskId,
            idempotencyKey,
            attemptNumber,
            TaskState.PENDING,
            null,
            null,
            0L,
            input,
            null,
            null,
            null,
            null,
            Instant.now(),
            null,
            null,
            traceId,
            null,
            null
        );
    }

    /**
     * Check if the execution is currently running with a valid lease.
     */
    public boolean hasValidLease() {
        return state == TaskState.RUNNING && 
               leaseHolder != null && 
               leaseExpiresAt != null && 
               leaseExpiresAt.isAfter(Instant.now());
    }

    /**
     * Check if the lease has expired.
     */
    public boolean isLeaseExpired() {
        return leaseExpiresAt != null && leaseExpiresAt.isBefore(Instant.now());
    }

    /**
     * Create a copy with the task started and lease acquired.
     */
    public TaskExecution withLeaseAcquired(UUID workerId, Instant expiresAt, long newFenceToken) {
        return new TaskExecution(
            executionId, workflowInstanceId, taskId, idempotencyKey, attemptNumber,
            TaskState.RUNNING, workerId, expiresAt, newFenceToken,
            input, output, errorMessage, errorCode, stackTrace,
            scheduledAt, Instant.now(), completedAt,
            traceId, spanId, workerId.toString()
        );
    }

    /**
     * Create a copy with the task completed successfully.
     */
    public TaskExecution withCompleted(JsonNode taskOutput) {
        return new TaskExecution(
            executionId, workflowInstanceId, taskId, idempotencyKey, attemptNumber,
            TaskState.COMPLETED, null, null, fenceToken,
            input, taskOutput, null, null, null,
            scheduledAt, startedAt, Instant.now(),
            traceId, spanId, workerId
        );
    }

    /**
     * Create a copy with the task failed.
     */
    public TaskExecution withFailed(String error, String code, String stack) {
        return new TaskExecution(
            executionId, workflowInstanceId, taskId, idempotencyKey, attemptNumber,
            TaskState.FAILED, null, null, fenceToken,
            input, null, error, code, stack,
            scheduledAt, startedAt, Instant.now(),
            traceId, spanId, workerId
        );
    }

    /**
     * Create a copy with the task timed out.
     */
    public TaskExecution withTimedOut() {
        return new TaskExecution(
            executionId, workflowInstanceId, taskId, idempotencyKey, attemptNumber,
            TaskState.TIMED_OUT, null, null, fenceToken,
            input, null, "Task execution timed out", "TIMEOUT", null,
            scheduledAt, startedAt, Instant.now(),
            traceId, spanId, workerId
        );
    }

    /**
     * Create a copy in QUEUED state (ready for worker pickup).
     */
    public TaskExecution withQueued() {
        return new TaskExecution(
            executionId, workflowInstanceId, taskId, idempotencyKey, attemptNumber,
            TaskState.QUEUED, null, null, fenceToken,
            input, output, errorMessage, errorCode, stackTrace,
            scheduledAt, startedAt, completedAt,
            traceId, spanId, workerId
        );
    }
}

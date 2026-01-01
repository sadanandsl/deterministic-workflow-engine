package com.orchestrator.core.repository;

import com.orchestrator.core.model.WorkflowInstance;
import com.orchestrator.core.model.WorkflowState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for WorkflowInstance persistence.
 * Supports optimistic locking via sequence numbers.
 */
public interface WorkflowInstanceRepository {

    /**
     * Save a new workflow instance.
     * 
     * @param instance The workflow instance to save
     * @throws DuplicateInstanceException if instance with same idempotency key exists
     */
    void save(WorkflowInstance instance);

    /**
     * Update an existing workflow instance with optimistic locking.
     * 
     * @param instance The workflow instance to update
     * @throws OptimisticLockException if sequence number doesn't match
     */
    void update(WorkflowInstance instance);

    /**
     * Find a workflow instance by ID.
     * 
     * @param instanceId The instance ID
     * @return The workflow instance if found
     */
    Optional<WorkflowInstance> findById(UUID instanceId);

    /**
     * Find a workflow instance by idempotency key (namespace:name:runId).
     * 
     * @param namespace The workflow namespace
     * @param workflowName The workflow name
     * @param runId The run ID
     * @return The workflow instance if found
     */
    Optional<WorkflowInstance> findByRunId(String namespace, String workflowName, String runId);

    /**
     * Find workflow instances by correlation ID.
     * 
     * @param correlationId The correlation ID
     * @return All matching workflow instances
     */
    List<WorkflowInstance> findByCorrelationId(String correlationId);

    /**
     * Find workflow instances by state.
     * 
     * @param state The workflow state
     * @param limit Maximum number of results
     * @return Workflow instances in the given state
     */
    List<WorkflowInstance> findByState(WorkflowState state, int limit);

    /**
     * Find workflow instances that have exceeded their deadline.
     * 
     * @param now Current time
     * @param limit Maximum number of results
     * @return Workflow instances past their deadline
     */
    List<WorkflowInstance> findExpiredDeadlines(Instant now, int limit);

    /**
     * Find workflow instances needing recovery (stuck in non-terminal state).
     * 
     * @param stuckSince Instances not updated since this time
     * @param limit Maximum number of results
     * @return Potentially stuck workflow instances
     */
    List<WorkflowInstance> findStuckInstances(Instant stuckSince, int limit);

    /**
     * Count workflow instances by state for a workflow type.
     * 
     * @param namespace The workflow namespace
     * @param workflowName The workflow name
     * @return Count per state
     */
    java.util.Map<WorkflowState, Long> countByState(String namespace, String workflowName);

    /**
     * Delete completed workflow instances older than the given time.
     * Used for cleanup/retention.
     * 
     * @param completedBefore Delete instances completed before this time
     * @return Number of deleted instances
     */
    int deleteCompletedBefore(Instant completedBefore);
}

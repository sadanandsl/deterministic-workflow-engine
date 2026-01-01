package com.orchestrator.core.repository;

import com.orchestrator.core.model.TaskExecution;
import com.orchestrator.core.model.TaskState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for TaskExecution persistence.
 */
public interface TaskExecutionRepository {

    /**
     * Save a new task execution.
     * 
     * @param execution The task execution to save
     * @throws DuplicateIdempotencyKeyException if idempotency key already exists
     */
    void save(TaskExecution execution);

    /**
     * Update an existing task execution.
     * 
     * @param execution The task execution to update
     */
    void update(TaskExecution execution);

    /**
     * Find a task execution by ID.
     * 
     * @param executionId The execution ID
     * @return The task execution if found
     */
    Optional<TaskExecution> findById(UUID executionId);

    /**
     * Find a task execution by idempotency key.
     * 
     * @param idempotencyKey The idempotency key
     * @return The task execution if found
     */
    Optional<TaskExecution> findByIdempotencyKey(String idempotencyKey);

    /**
     * Find all executions for a workflow instance.
     * 
     * @param workflowInstanceId The workflow instance ID
     * @return All task executions for the instance
     */
    List<TaskExecution> findByWorkflowInstance(UUID workflowInstanceId);

    /**
     * Find all executions for a specific task within a workflow.
     * 
     * @param workflowInstanceId The workflow instance ID
     * @param taskId The task ID
     * @return All executions for the task, ordered by attempt number
     */
    List<TaskExecution> findByTask(UUID workflowInstanceId, String taskId);

    /**
     * Find the latest execution for a task.
     * 
     * @param workflowInstanceId The workflow instance ID
     * @param taskId The task ID
     * @return The latest task execution if any exists
     */
    Optional<TaskExecution> findLatestByTask(UUID workflowInstanceId, String taskId);

    /**
     * Find task executions by state.
     * 
     * @param state The task state
     * @param limit Maximum number of results
     * @return Task executions in the given state
     */
    List<TaskExecution> findByState(TaskState state, int limit);

    /**
     * Find task executions with expired leases.
     * 
     * @param now Current time
     * @param limit Maximum number of results
     * @return Task executions with expired leases
     */
    List<TaskExecution> findExpiredLeases(Instant now, int limit);

    /**
     * Find queued task executions ready for pickup.
     * 
     * @param activityType The activity type (worker queue)
     * @param limit Maximum number of results
     * @return Queued task executions
     */
    List<TaskExecution> findQueuedByActivityType(String activityType, int limit);

    /**
     * Get the current attempt count for a task.
     * 
     * @param workflowInstanceId The workflow instance ID
     * @param taskId The task ID
     * @return Current attempt count (0 if no executions)
     */
    int getCurrentAttemptCount(UUID workflowInstanceId, String taskId);
}

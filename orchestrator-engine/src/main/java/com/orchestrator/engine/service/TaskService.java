package com.orchestrator.engine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.orchestrator.core.model.TaskExecution;
import com.orchestrator.core.model.TaskState;
import java.util.List;
import java.util.UUID;

/**
 * Service for task execution management.
 * Handles task scheduling, execution, and completion.
 */
public interface TaskService {

    /**
     * Schedule a task for execution.
     * 
     * @param workflowInstanceId The workflow instance ID
     * @param taskId The task ID within the workflow
     * @param input The task input
     * @return The created task execution
     */
    TaskExecution scheduleTask(UUID workflowInstanceId, String taskId, JsonNode input);

    /**
     * Acquire a task for execution (called by workers).
     * 
     * @param executionId The task execution ID
     * @param workerId The worker ID
     * @return The task execution with lease acquired, or empty if already taken
     */
    java.util.Optional<TaskExecution> acquireTask(UUID executionId, UUID workerId);

    /**
     * Poll for available tasks of a specific activity type.
     * 
     * @param activityType The activity type
     * @param workerId The worker ID
     * @param maxTasks Maximum number of tasks to return
     * @return Available task executions
     */
    List<TaskExecution> pollTasks(String activityType, UUID workerId, int maxTasks);

    /**
     * Report task completion.
     * 
     * @param executionId The task execution ID
     * @param workerId The worker ID
     * @param fenceToken The fence token from lease
     * @param output The task output
     */
    void completeTask(UUID executionId, UUID workerId, long fenceToken, JsonNode output);

    /**
     * Report task failure.
     * 
     * @param executionId The task execution ID
     * @param workerId The worker ID
     * @param fenceToken The fence token from lease
     * @param errorCode The error code
     * @param errorMessage The error message
     * @param stackTrace Optional stack trace
     */
    void failTask(UUID executionId, UUID workerId, long fenceToken, 
                  String errorCode, String errorMessage, String stackTrace);

    /**
     * Renew task lease (heartbeat).
     * 
     * @param executionId The task execution ID
     * @param workerId The worker ID
     * @param fenceToken The fence token from lease
     * @return true if renewal succeeded
     */
    boolean renewLease(UUID executionId, UUID workerId, long fenceToken);

    /**
     * Manually retry a failed task.
     * 
     * @param workflowInstanceId The workflow instance ID
     * @param taskId The task ID
     * @param overrideInput Optional input override
     * @return The new task execution
     */
    TaskExecution retryTask(UUID workflowInstanceId, String taskId, JsonNode overrideInput);

    /**
     * Force complete a task (human override).
     * 
     * @param executionId The task execution ID
     * @param output The forced output
     * @param reason The reason for force completion
     * @param operator The operator identity
     */
    void forceCompleteTask(UUID executionId, JsonNode output, String reason, String operator);

    /**
     * Get task execution by ID.
     * 
     * @param executionId The task execution ID
     * @return The task execution
     */
    TaskExecution getTaskExecution(UUID executionId);

    /**
     * Get execution history for a workflow.
     * 
     * @param workflowInstanceId The workflow instance ID
     * @return All task executions
     */
    List<TaskExecution> getExecutionHistory(UUID workflowInstanceId);
}

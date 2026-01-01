package com.orchestrator.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrator.core.model.TaskExecution;

import java.util.UUID;

/**
 * Context provided to activity handlers during execution.
 */
public class ActivityContext {
    
    private final TaskExecution execution;
    private final ObjectMapper objectMapper;
    private final HeartbeatCallback heartbeatCallback;
    
    public ActivityContext(
            TaskExecution execution,
            ObjectMapper objectMapper,
            HeartbeatCallback heartbeatCallback) {
        this.execution = execution;
        this.objectMapper = objectMapper;
        this.heartbeatCallback = heartbeatCallback;
    }
    
    /**
     * Get the task execution details.
     */
    public TaskExecution getExecution() {
        return execution;
    }
    
    /**
     * Get the task input.
     */
    public JsonNode getInput() {
        return execution.input();
    }
    
    /**
     * Get the task input as a specific type.
     */
    public <T> T getInput(Class<T> type) {
        return objectMapper.convertValue(execution.input(), type);
    }
    
    /**
     * Get the workflow instance ID.
     */
    public UUID getWorkflowInstanceId() {
        return execution.workflowInstanceId();
    }
    
    /**
     * Get the task ID.
     */
    public String getTaskId() {
        return execution.taskId();
    }
    
    /**
     * Get the attempt number.
     */
    public int getAttemptNumber() {
        return execution.attemptNumber();
    }
    
    /**
     * Get the idempotency key for this execution.
     * Use this when making external calls to ensure exactly-once semantics.
     */
    public String getIdempotencyKey() {
        return execution.idempotencyKey();
    }
    
    /**
     * Send a heartbeat to renew the lease.
     * Call this periodically for long-running activities.
     * 
     * @return true if heartbeat succeeded, false if lease was lost
     */
    public boolean heartbeat() {
        return heartbeatCallback.sendHeartbeat();
    }
    
    /**
     * Convert a result object to JsonNode.
     */
    public JsonNode toJsonNode(Object result) {
        return objectMapper.valueToTree(result);
    }
    
    /**
     * Callback for heartbeat/lease renewal.
     */
    @FunctionalInterface
    public interface HeartbeatCallback {
        boolean sendHeartbeat();
    }
}

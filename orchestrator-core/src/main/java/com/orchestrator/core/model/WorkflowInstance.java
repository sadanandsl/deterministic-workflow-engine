package com.orchestrator.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A single execution of a WorkflowDefinition.
 * Primary source of truth for workflow state.
 * 
 * Primary Key: instanceId
 * Unique Constraint: (namespace, workflowName, runId) - for idempotency
 * 
 * Invariants:
 * - runId + namespace + workflowName is unique
 * - state transitions follow state machine
 * - sequenceNumber is monotonically increasing
 */
public record WorkflowInstance(
    // Primary key
    UUID instanceId,
    
    // Foreign key to definition
    String namespace,
    String workflowName,
    int workflowVersion,
    
    // Execution identity
    String runId,
    String correlationId,
    
    // State
    WorkflowState state,
    String currentTaskId,
    Set<String> completedTaskIds,
    Set<String> failedTaskIds,
    
    // Data
    JsonNode input,
    JsonNode output,
    Map<String, JsonNode> taskOutputs,
    
    // Error tracking
    String lastError,
    String lastErrorTaskId,
    
    // Timing
    Instant createdAt,
    Instant startedAt,
    Instant completedAt,
    Instant deadline,
    
    // Recovery
    int recoveryAttempts,
    Instant lastRecoveryAt,
    
    // Versioning (optimistic locking)
    long sequenceNumber
) {
    /**
     * Create a new workflow instance in CREATED state.
     */
    public static WorkflowInstance create(
            String namespace,
            String workflowName,
            int workflowVersion,
            String runId,
            String correlationId,
            JsonNode input,
            Instant deadline) {
        return new WorkflowInstance(
            UUID.randomUUID(),
            namespace,
            workflowName,
            workflowVersion,
            runId,
            correlationId,
            WorkflowState.CREATED,
            null,
            Set.of(),
            Set.of(),
            input,
            null,
            Map.of(),
            null,
            null,
            Instant.now(),
            null,
            null,
            deadline,
            0,
            null,
            0L
        );
    }

    /**
     * Generate the idempotency key for this workflow instance.
     */
    public String idempotencyKey() {
        return namespace + ":" + workflowName + ":" + runId;
    }

    /**
     * Check if the workflow is in a terminal state.
     */
    public boolean isTerminal() {
        return state.isTerminal();
    }

    /**
     * Check if the workflow can accept signals.
     */
    public boolean canAcceptSignals() {
        return state == WorkflowState.RUNNING || state == WorkflowState.PAUSED;
    }

    /**
     * Create a copy with updated state.
     */
    public WorkflowInstance withState(WorkflowState newState) {
        return new WorkflowInstance(
            instanceId, namespace, workflowName, workflowVersion,
            runId, correlationId, newState, currentTaskId,
            completedTaskIds, failedTaskIds, input, output, taskOutputs,
            lastError, lastErrorTaskId, createdAt, startedAt, completedAt,
            deadline, recoveryAttempts, lastRecoveryAt, sequenceNumber + 1
        );
    }

    /**
     * Create a copy with task completion recorded.
     */
    public WorkflowInstance withTaskCompleted(String taskId, JsonNode taskOutput) {
        var newCompletedTasks = new java.util.HashSet<>(completedTaskIds);
        newCompletedTasks.add(taskId);
        
        var newTaskOutputs = new java.util.HashMap<>(taskOutputs);
        newTaskOutputs.put(taskId, taskOutput);
        
        return new WorkflowInstance(
            instanceId, namespace, workflowName, workflowVersion,
            runId, correlationId, state, currentTaskId,
            Set.copyOf(newCompletedTasks), failedTaskIds, input, output,
            Map.copyOf(newTaskOutputs), lastError, lastErrorTaskId,
            createdAt, startedAt, completedAt, deadline,
            recoveryAttempts, lastRecoveryAt, sequenceNumber + 1
        );
    }

    /**
     * Create a copy with task failure recorded.
     */
    public WorkflowInstance withTaskFailed(String taskId, String error) {
        var newFailedTasks = new java.util.HashSet<>(failedTaskIds);
        newFailedTasks.add(taskId);
        
        return new WorkflowInstance(
            instanceId, namespace, workflowName, workflowVersion,
            runId, correlationId, state, currentTaskId,
            completedTaskIds, Set.copyOf(newFailedTasks), input, output, taskOutputs,
            error, taskId, createdAt, startedAt, completedAt, deadline,
            recoveryAttempts, lastRecoveryAt, sequenceNumber + 1
        );
    }

    /**
     * Builder for creating modified copies.
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    public static class Builder {
        private UUID instanceId;
        private String namespace;
        private String workflowName;
        private int workflowVersion;
        private String runId;
        private String correlationId;
        private WorkflowState state;
        private String currentTaskId;
        private Set<String> completedTaskIds;
        private Set<String> failedTaskIds;
        private JsonNode input;
        private JsonNode output;
        private Map<String, JsonNode> taskOutputs;
        private String lastError;
        private String lastErrorTaskId;
        private Instant createdAt;
        private Instant startedAt;
        private Instant completedAt;
        private Instant deadline;
        private int recoveryAttempts;
        private Instant lastRecoveryAt;
        private long sequenceNumber;

        public Builder(WorkflowInstance instance) {
            this.instanceId = instance.instanceId();
            this.namespace = instance.namespace();
            this.workflowName = instance.workflowName();
            this.workflowVersion = instance.workflowVersion();
            this.runId = instance.runId();
            this.correlationId = instance.correlationId();
            this.state = instance.state();
            this.currentTaskId = instance.currentTaskId();
            this.completedTaskIds = instance.completedTaskIds();
            this.failedTaskIds = instance.failedTaskIds();
            this.input = instance.input();
            this.output = instance.output();
            this.taskOutputs = instance.taskOutputs();
            this.lastError = instance.lastError();
            this.lastErrorTaskId = instance.lastErrorTaskId();
            this.createdAt = instance.createdAt();
            this.startedAt = instance.startedAt();
            this.completedAt = instance.completedAt();
            this.deadline = instance.deadline();
            this.recoveryAttempts = instance.recoveryAttempts();
            this.lastRecoveryAt = instance.lastRecoveryAt();
            this.sequenceNumber = instance.sequenceNumber();
        }

        public Builder state(WorkflowState state) {
            this.state = state;
            return this;
        }

        public Builder currentTaskId(String currentTaskId) {
            this.currentTaskId = currentTaskId;
            return this;
        }

        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        public Builder completedAt(Instant completedAt) {
            this.completedAt = completedAt;
            return this;
        }

        public Builder output(JsonNode output) {
            this.output = output;
            return this;
        }

        public Builder incrementSequence() {
            this.sequenceNumber++;
            return this;
        }

        public WorkflowInstance build() {
            return new WorkflowInstance(
                instanceId, namespace, workflowName, workflowVersion,
                runId, correlationId, state, currentTaskId,
                completedTaskIds, failedTaskIds, input, output, taskOutputs,
                lastError, lastErrorTaskId, createdAt, startedAt, completedAt,
                deadline, recoveryAttempts, lastRecoveryAt, sequenceNumber
            );
        }
    }
}

package com.orchestrator.core.model;

import java.time.Duration;

/**
 * Definition of a task within a workflow.
 * Describes what to execute, not instance-specific data.
 * 
 * Invariants:
 * - taskId is non-empty and unique within workflow
 * - timeout > 0
 * - compensationTaskId, if set, must reference valid task
 */
public record TaskDefinition(
    // Identity
    String taskId,
    TaskType type,
    String displayName,
    
    // Execution
    String activityType,
    Duration timeout,
    RetryPolicy retryPolicy,
    
    // Compensation
    String compensationTaskId,
    
    // Conditional execution (SpEL/JEXL expression)
    String conditionExpression,
    
    // Input mapping (expression to extract input from workflow state)
    String inputExpression,
    
    // Output mapping (expression to map task output to workflow state)
    String outputExpression,
    
    // For sub-workflow tasks
    String subWorkflowNamespace,
    String subWorkflowName,
    Integer subWorkflowVersion,
    
    // For wait tasks
    String waitSignalName,
    Duration waitTimeout,
    
    // Metadata
    String description
) {
    /**
     * Default timeout if not specified: 5 minutes.
     */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Check if this task has a compensation defined.
     */
    public boolean hasCompensation() {
        return compensationTaskId != null && !compensationTaskId.isEmpty();
    }

    /**
     * Check if this task has a conditional expression.
     */
    public boolean isConditional() {
        return conditionExpression != null && !conditionExpression.isEmpty();
    }

    /**
     * Get the effective timeout (task-specific or default).
     */
    public Duration effectiveTimeout() {
        return timeout != null ? timeout : DEFAULT_TIMEOUT;
    }

    /**
     * Get the effective retry policy (task-specific or null for workflow default).
     */
    public RetryPolicy effectiveRetryPolicy(RetryPolicy workflowDefault) {
        return retryPolicy != null ? retryPolicy : workflowDefault;
    }

    /**
     * Builder for TaskDefinition.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String taskId;
        private TaskType type = TaskType.ACTIVITY;
        private String displayName;
        private String activityType;
        private Duration timeout = DEFAULT_TIMEOUT;
        private RetryPolicy retryPolicy;
        private String compensationTaskId;
        private String conditionExpression;
        private String inputExpression;
        private String outputExpression;
        private String subWorkflowNamespace;
        private String subWorkflowName;
        private Integer subWorkflowVersion;
        private String waitSignalName;
        private Duration waitTimeout;
        private String description;

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder type(TaskType type) {
            this.type = type;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder activityType(String activityType) {
            this.activityType = activityType;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        public Builder compensationTaskId(String compensationTaskId) {
            this.compensationTaskId = compensationTaskId;
            return this;
        }

        public Builder conditionExpression(String conditionExpression) {
            this.conditionExpression = conditionExpression;
            return this;
        }

        public Builder inputExpression(String inputExpression) {
            this.inputExpression = inputExpression;
            return this;
        }

        public Builder outputExpression(String outputExpression) {
            this.outputExpression = outputExpression;
            return this;
        }

        public Builder waitSignalName(String waitSignalName) {
            this.waitSignalName = waitSignalName;
            return this;
        }

        public Builder waitTimeout(Duration waitTimeout) {
            this.waitTimeout = waitTimeout;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public TaskDefinition build() {
            return new TaskDefinition(
                taskId, type, displayName, activityType, timeout, retryPolicy,
                compensationTaskId, conditionExpression, inputExpression, outputExpression,
                subWorkflowNamespace, subWorkflowName, subWorkflowVersion,
                waitSignalName, waitTimeout, description
            );
        }
    }
}

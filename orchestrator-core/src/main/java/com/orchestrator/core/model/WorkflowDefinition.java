package com.orchestrator.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable definition of a workflow type.
 * Versioned to support safe updates without affecting running instances.
 * 
 * Primary Key: {namespace}:{name}:{version}
 * 
 * Invariants:
 * - entryTaskId must exist in tasks
 * - All edge targets must exist in tasks
 * - Graph must be acyclic (DAG)
 * - terminalTaskIds must have no outgoing edges
 */
public record WorkflowDefinition(
    // Identity
    String namespace,
    String name,
    int version,
    
    // Graph structure
    List<TaskDefinition> tasks,
    Map<String, List<String>> edges,
    String entryTaskId,
    Set<String> terminalTaskIds,
    
    // Policies
    RetryPolicy defaultRetryPolicy,
    Duration maxDuration,
    CompensationStrategy compensationStrategy,
    
    // Metadata
    Instant createdAt,
    String createdBy,
    String description,
    Map<String, String> labels
) {
    /**
     * Construct the unique identifier for this workflow definition.
     */
    public String id() {
        return namespace + ":" + name + ":" + version;
    }

    /**
     * Get a task definition by ID.
     */
    public TaskDefinition getTask(String taskId) {
        return tasks.stream()
            .filter(t -> t.taskId().equals(taskId))
            .findFirst()
            .orElse(null);
    }

    /**
     * Get the next tasks to execute after the given task.
     */
    public List<String> getNextTasks(String taskId) {
        return edges.getOrDefault(taskId, List.of());
    }

    /**
     * Check if a task is a terminal task.
     */
    public boolean isTerminalTask(String taskId) {
        return terminalTaskIds.contains(taskId);
    }

    /**
     * Builder for WorkflowDefinition.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String namespace;
        private String name;
        private int version = 1;
        private List<TaskDefinition> tasks = List.of();
        private Map<String, List<String>> edges = Map.of();
        private String entryTaskId;
        private Set<String> terminalTaskIds = Set.of();
        private RetryPolicy defaultRetryPolicy = RetryPolicy.defaultPolicy();
        private Duration maxDuration = Duration.ofHours(24);
        private CompensationStrategy compensationStrategy = CompensationStrategy.BACKWARD;
        private Instant createdAt = Instant.now();
        private String createdBy;
        private String description;
        private Map<String, String> labels = Map.of();

        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder version(int version) {
            this.version = version;
            return this;
        }

        public Builder tasks(List<TaskDefinition> tasks) {
            this.tasks = tasks;
            return this;
        }

        public Builder edges(Map<String, List<String>> edges) {
            this.edges = edges;
            return this;
        }

        public Builder entryTaskId(String entryTaskId) {
            this.entryTaskId = entryTaskId;
            return this;
        }

        public Builder terminalTaskIds(Set<String> terminalTaskIds) {
            this.terminalTaskIds = terminalTaskIds;
            return this;
        }

        public Builder defaultRetryPolicy(RetryPolicy defaultRetryPolicy) {
            this.defaultRetryPolicy = defaultRetryPolicy;
            return this;
        }

        public Builder maxDuration(Duration maxDuration) {
            this.maxDuration = maxDuration;
            return this;
        }

        public Builder compensationStrategy(CompensationStrategy compensationStrategy) {
            this.compensationStrategy = compensationStrategy;
            return this;
        }

        public Builder createdBy(String createdBy) {
            this.createdBy = createdBy;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder labels(Map<String, String> labels) {
            this.labels = labels;
            return this;
        }

        public WorkflowDefinition build() {
            return new WorkflowDefinition(
                namespace, name, version, tasks, edges, entryTaskId,
                terminalTaskIds, defaultRetryPolicy, maxDuration,
                compensationStrategy, createdAt, createdBy, description, labels
            );
        }
    }
}

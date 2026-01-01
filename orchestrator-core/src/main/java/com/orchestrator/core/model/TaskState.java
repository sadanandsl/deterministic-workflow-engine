package com.orchestrator.core.model;

/**
 * Lifecycle states for a task execution.
 */
public enum TaskState {
    /**
     * Task is waiting to be scheduled.
     * Transitions: -> QUEUED
     */
    PENDING,

    /**
     * Task is in the execution queue, waiting for a worker.
     * Transitions: -> RUNNING, TIMED_OUT
     */
    QUEUED,

    /**
     * Task is currently being executed by a worker.
     * Transitions: -> COMPLETED, FAILED, TIMED_OUT
     */
    RUNNING,

    /**
     * Task completed successfully. Terminal state.
     */
    COMPLETED,

    /**
     * Task failed with error. 
     * Transitions: -> RETRYING (if retries available)
     */
    FAILED,

    /**
     * Task exceeded timeout.
     * Transitions: -> RETRYING (if retries available)
     */
    TIMED_OUT,

    /**
     * Task is being retried.
     * Transitions: -> QUEUED
     */
    RETRYING,

    /**
     * Task was cancelled before completion.
     */
    CANCELLED,

    /**
     * Task was skipped (e.g., conditional execution evaluated to false).
     */
    SKIPPED;

    /**
     * Check if this state is terminal (no further transitions).
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == SKIPPED;
    }

    /**
     * Check if this state represents a failure that might be retriable.
     */
    public boolean isRetriable() {
        return this == FAILED || this == TIMED_OUT;
    }

    /**
     * Check if this state means the task is actively running.
     */
    public boolean isActive() {
        return this == QUEUED || this == RUNNING || this == RETRYING;
    }
}

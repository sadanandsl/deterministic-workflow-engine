package com.orchestrator.core.model;

/**
 * Types of tasks that can be defined in a workflow.
 */
public enum TaskType {
    /**
     * Activity task - performs external side effects.
     * Examples: HTTP calls, database writes, message publishing.
     * Must be idempotent for exactly-once semantics.
     */
    ACTIVITY,

    /**
     * Decision task - internal branching logic.
     * No side effects, purely computational.
     * Used for conditional workflow paths.
     */
    DECISION,

    /**
     * Wait task - pauses execution until event or timeout.
     * Examples: wait for signal, wait for timer.
     */
    WAIT,

    /**
     * Compensation task - undo operation for a completed task.
     * Executed during workflow compensation phase.
     */
    COMPENSATION,

    /**
     * Sub-workflow task - invokes another workflow.
     * Creates a child workflow instance.
     */
    SUB_WORKFLOW,

    /**
     * Parallel task - executes multiple tasks concurrently.
     * Completes when all parallel tasks complete.
     */
    PARALLEL,

    /**
     * Human task - requires human approval or input.
     * Workflow pauses until human interaction.
     */
    HUMAN
}

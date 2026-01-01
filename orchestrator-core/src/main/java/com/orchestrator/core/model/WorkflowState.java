package com.orchestrator.core.model;

/**
 * Lifecycle states for a workflow instance.
 * Transitions follow a strict state machine - see documentation.
 */
public enum WorkflowState {
    /**
     * Workflow registered but not yet started.
     * Transitions: -> RUNNING
     */
    CREATED,

    /**
     * Active execution in progress.
     * Transitions: -> COMPLETING, FAILING, PAUSED
     */
    RUNNING,

    /**
     * Execution halted, awaiting signal or human override.
     * Transitions: -> RUNNING, FAILING
     */
    PAUSED,

    /**
     * All tasks completed, finalizing workflow.
     * Transitions: -> COMPLETED
     */
    COMPLETING,

    /**
     * Successfully finished. Terminal state.
     */
    COMPLETED,

    /**
     * Failure detected, evaluating retry/compensation.
     * Transitions: -> RUNNING (retry), COMPENSATING, FAILED
     */
    FAILING,

    /**
     * Exhausted retries, no compensation triggered. Terminal state.
     * Can be manually transitioned: -> COMPENSATING, RUNNING
     */
    FAILED,

    /**
     * Executing compensation tasks.
     * Transitions: -> COMPENSATED, COMPENSATION_FAILED
     */
    COMPENSATING,

    /**
     * All compensations completed successfully. Terminal state.
     */
    COMPENSATED,

    /**
     * Compensation failed. Terminal state, requires human intervention.
     */
    COMPENSATION_FAILED;

    /**
     * Check if this state is terminal (no further transitions possible automatically).
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || 
               this == COMPENSATED || this == COMPENSATION_FAILED;
    }

    /**
     * Check if this state allows task execution.
     */
    public boolean allowsExecution() {
        return this == RUNNING || this == COMPENSATING;
    }

    /**
     * Check if this state can transition to the target state.
     */
    public boolean canTransitionTo(WorkflowState target) {
        return switch (this) {
            case CREATED -> target == RUNNING;
            case RUNNING -> target == COMPLETING || target == FAILING || target == PAUSED;
            case PAUSED -> target == RUNNING || target == FAILING;
            case COMPLETING -> target == COMPLETED;
            case COMPLETED -> false;
            case FAILING -> target == RUNNING || target == COMPENSATING || target == FAILED;
            case FAILED -> target == COMPENSATING || target == RUNNING; // Manual retry
            case COMPENSATING -> target == COMPENSATED || target == COMPENSATION_FAILED;
            case COMPENSATED, COMPENSATION_FAILED -> false;
        };
    }
}

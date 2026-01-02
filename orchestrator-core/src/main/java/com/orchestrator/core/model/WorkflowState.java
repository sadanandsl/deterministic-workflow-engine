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
     * Workflow queued, waiting for resources.
     * Transitions: -> RUNNING, CANCELLED
     */
    PENDING,

    /**
     * Active execution in progress.
     * Transitions: -> COMPLETING, FAILING, PAUSED, CANCELLED, DEAD_LETTER
     */
    RUNNING,

    /**
     * Execution halted, awaiting signal or human override.
     * Transitions: -> RUNNING, FAILING, CANCELLED, DEAD_LETTER
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
    COMPENSATION_FAILED,

    /**
     * Workflow cancelled by user/admin request.
     * Terminal state unless compensation needed.
     */
    CANCELLED,

    /**
     * Workflow moved to dead letter queue for manual intervention.
     * Transitions: -> RUNNING (retry), DISCARDED
     */
    DEAD_LETTER,

    /**
     * Workflow permanently discarded from dead letter queue.
     * Terminal state.
     */
    DISCARDED;

    /**
     * Check if this state is terminal (no further transitions possible automatically).
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || 
               this == COMPENSATED || this == COMPENSATION_FAILED ||
               this == CANCELLED || this == DISCARDED;
    }

    /**
     * Check if this state allows task execution.
     */
    public boolean allowsExecution() {
        return this == RUNNING || this == COMPENSATING;
    }

    /**
     * Check if this state requires manual intervention.
     */
    public boolean requiresIntervention() {
        return this == DEAD_LETTER || this == COMPENSATION_FAILED || this == FAILED;
    }

    /**
     * Check if this state can transition to the target state.
     */
    public boolean canTransitionTo(WorkflowState target) {
        return switch (this) {
            case CREATED -> target == RUNNING || target == PENDING;
            case PENDING -> target == RUNNING || target == CANCELLED;
            case RUNNING -> target == COMPLETING || target == FAILING || target == PAUSED || 
                           target == CANCELLED || target == DEAD_LETTER;
            case PAUSED -> target == RUNNING || target == FAILING || target == CANCELLED || 
                          target == DEAD_LETTER;
            case COMPLETING -> target == COMPLETED;
            case COMPLETED -> false;
            case FAILING -> target == RUNNING || target == COMPENSATING || target == FAILED;
            case FAILED -> target == COMPENSATING || target == RUNNING || target == DEAD_LETTER;
            case COMPENSATING -> target == COMPENSATED || target == COMPENSATION_FAILED;
            case COMPENSATED, COMPENSATION_FAILED -> false;
            case CANCELLED -> false;
            case DEAD_LETTER -> target == RUNNING || target == DISCARDED;
            case DISCARDED -> false;
        };
    }
}

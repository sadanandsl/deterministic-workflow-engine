package com.orchestrator.core.model;

/**
 * Strategy for executing compensation tasks when a workflow fails.
 */
public enum CompensationStrategy {
    /**
     * Execute compensations in reverse order of original task completion.
     * Most common pattern - LIFO (Last In, First Out).
     * 
     * Example: If tasks completed A -> B -> C, compensate C -> B -> A
     */
    BACKWARD,

    /**
     * Execute compensations in the same order as original execution.
     * Used when compensation order doesn't matter or forward cleanup is needed.
     */
    FORWARD,

    /**
     * Execute all compensations in parallel.
     * Use only when compensations are independent.
     */
    PARALLEL,

    /**
     * No automatic compensation - workflow goes to FAILED state.
     * Manual intervention required.
     */
    NONE,

    /**
     * Custom compensation order defined in workflow.
     * Compensation graph is explicitly specified.
     */
    CUSTOM
}

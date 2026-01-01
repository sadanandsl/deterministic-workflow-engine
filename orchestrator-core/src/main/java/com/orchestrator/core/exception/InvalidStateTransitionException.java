package com.orchestrator.core.exception;

import com.orchestrator.core.model.WorkflowState;

/**
 * Thrown when an invalid state transition is attempted.
 */
public class InvalidStateTransitionException extends OrchestratorException {
    
    public static final String ERROR_CODE = "INVALID_STATE_TRANSITION";
    
    public InvalidStateTransitionException(WorkflowState currentState, WorkflowState targetState) {
        super(ERROR_CODE, String.format(
            "Cannot transition from %s to %s",
            currentState, targetState
        ));
    }
    
    public InvalidStateTransitionException(String entityType, String currentState, String targetState) {
        super(ERROR_CODE, String.format(
            "Cannot transition %s from %s to %s",
            entityType, currentState, targetState
        ));
    }
}

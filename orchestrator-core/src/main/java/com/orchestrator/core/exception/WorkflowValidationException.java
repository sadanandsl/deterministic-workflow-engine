package com.orchestrator.core.exception;

/**
 * Thrown when workflow definition validation fails.
 */
public class WorkflowValidationException extends OrchestratorException {
    
    public static final String ERROR_CODE = "WORKFLOW_VALIDATION_FAILED";
    
    public WorkflowValidationException(String message) {
        super(ERROR_CODE, message);
    }
    
    public WorkflowValidationException(String field, String reason) {
        super(ERROR_CODE, String.format("Invalid workflow definition: %s - %s", field, reason));
    }
}

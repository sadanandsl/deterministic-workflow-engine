package com.orchestrator.core.exception;

/**
 * Thrown when a workflow or task is not found.
 */
public class NotFoundException extends OrchestratorException {
    
    public static final String ERROR_CODE = "NOT_FOUND";
    
    public NotFoundException(String entityType, String entityId) {
        super(ERROR_CODE, String.format(
            "%s not found: %s",
            entityType, entityId
        ));
    }
}

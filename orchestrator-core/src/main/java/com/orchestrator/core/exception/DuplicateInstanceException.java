package com.orchestrator.core.exception;

/**
 * Thrown when a duplicate workflow instance is created with the same idempotency key.
 */
public class DuplicateInstanceException extends OrchestratorException {
    
    public static final String ERROR_CODE = "DUPLICATE_INSTANCE";
    
    private final String existingInstanceId;
    
    public DuplicateInstanceException(String idempotencyKey, String existingInstanceId) {
        super(ERROR_CODE, String.format(
            "Workflow instance with idempotency key '%s' already exists: %s",
            idempotencyKey, existingInstanceId
        ));
        this.existingInstanceId = existingInstanceId;
    }
    
    public String getExistingInstanceId() {
        return existingInstanceId;
    }
}

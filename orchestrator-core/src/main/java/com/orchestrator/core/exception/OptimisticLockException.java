package com.orchestrator.core.exception;

/**
 * Thrown when an optimistic lock conflict occurs during update.
 */
public class OptimisticLockException extends OrchestratorException {
    
    public static final String ERROR_CODE = "OPTIMISTIC_LOCK_CONFLICT";
    
    public OptimisticLockException(String entityType, String entityId, long expectedVersion, long actualVersion) {
        super(ERROR_CODE, String.format(
            "Optimistic lock conflict on %s[%s]: expected version %d, actual version %d",
            entityType, entityId, expectedVersion, actualVersion
        ));
    }
}

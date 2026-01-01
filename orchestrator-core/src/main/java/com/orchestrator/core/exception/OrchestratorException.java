package com.orchestrator.core.exception;

/**
 * Base exception for all orchestrator errors.
 */
public class OrchestratorException extends RuntimeException {
    
    private final String errorCode;
    
    public OrchestratorException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
    
    public OrchestratorException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
    
    public String getErrorCode() {
        return errorCode;
    }
}

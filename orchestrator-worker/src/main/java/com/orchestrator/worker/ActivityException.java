package com.orchestrator.worker;

/**
 * Exception thrown by activity handlers on failure.
 */
public class ActivityException extends Exception {
    
    private final String errorCode;
    private final boolean retryable;
    
    public ActivityException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = true;
    }
    
    public ActivityException(String errorCode, String message, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }
    
    public ActivityException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = true;
    }
    
    public ActivityException(String errorCode, String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }
    
    public String getErrorCode() {
        return errorCode;
    }
    
    public boolean isRetryable() {
        return retryable;
    }
    
    /**
     * Create a non-retryable exception (permanent failure).
     */
    public static ActivityException permanent(String errorCode, String message) {
        return new ActivityException(errorCode, message, false);
    }
    
    /**
     * Create a retryable exception (transient failure).
     */
    public static ActivityException transient_(String errorCode, String message) {
        return new ActivityException(errorCode, message, true);
    }
}

package com.orchestrator.core.exception;

/**
 * Thrown when a lease cannot be acquired.
 */
public class LeaseAcquisitionException extends OrchestratorException {
    
    public static final String ERROR_CODE = "LEASE_ACQUISITION_FAILED";
    
    public LeaseAcquisitionException(String leaseKey, String currentHolder) {
        super(ERROR_CODE, String.format(
            "Failed to acquire lease '%s': currently held by %s",
            leaseKey, currentHolder
        ));
    }
    
    public LeaseAcquisitionException(String leaseKey) {
        super(ERROR_CODE, String.format(
            "Failed to acquire lease '%s'",
            leaseKey
        ));
    }
}

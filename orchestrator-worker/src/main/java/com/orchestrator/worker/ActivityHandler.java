package com.orchestrator.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.orchestrator.core.model.TaskExecution;

/**
 * Interface for task activity implementations.
 * Workers implement this interface to handle specific activity types.
 */
@FunctionalInterface
public interface ActivityHandler {
    
    /**
     * Execute the activity.
     * 
     * @param context Execution context providing input and utilities
     * @return The activity output
     * @throws ActivityException if the activity fails
     */
    JsonNode execute(ActivityContext context) throws ActivityException;
}

package com.orchestrator.engine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.orchestrator.core.model.WorkflowDefinition;
import com.orchestrator.core.model.WorkflowInstance;
import com.orchestrator.core.model.WorkflowState;
import java.util.List;
import java.util.UUID;

/**
 * Core service for workflow orchestration.
 * Manages workflow lifecycle and state transitions.
 */
public interface WorkflowService {

    /**
     * Register a new workflow definition.
     * 
     * @param definition The workflow definition
     * @return The registered definition with version assigned
     */
    WorkflowDefinition registerWorkflow(WorkflowDefinition definition);

    /**
     * Start a new workflow instance.
     * 
     * @param request The start request
     * @return The created workflow instance
     */
    WorkflowInstance startWorkflow(StartWorkflowRequest request);

    /**
     * Get workflow instance by ID.
     * 
     * @param instanceId The instance ID
     * @return The workflow instance
     */
    WorkflowInstance getWorkflow(UUID instanceId);

    /**
     * Signal a running workflow.
     * 
     * @param instanceId The instance ID
     * @param signalName The signal name
     * @param payload The signal payload
     */
    void signalWorkflow(UUID instanceId, String signalName, JsonNode payload);

    /**
     * Pause a running workflow.
     * 
     * @param instanceId The instance ID
     * @param reason The pause reason
     */
    void pauseWorkflow(UUID instanceId, String reason);

    /**
     * Resume a paused workflow.
     * 
     * @param instanceId The instance ID
     */
    void resumeWorkflow(UUID instanceId);

    /**
     * Cancel a workflow.
     * 
     * @param instanceId The instance ID
     * @param reason The cancellation reason
     */
    void cancelWorkflow(UUID instanceId, String reason);

    /**
     * Trigger compensation for a failed workflow.
     * 
     * @param instanceId The instance ID
     * @param fromTaskId Optional: start compensation from specific task
     */
    void triggerCompensation(UUID instanceId, String fromTaskId);

    /**
     * Query workflows by criteria.
     * 
     * @param query The query criteria
     * @return Matching workflow instances
     */
    List<WorkflowInstance> queryWorkflows(WorkflowQuery query);

    /**
     * Request to start a workflow.
     */
    record StartWorkflowRequest(
        String namespace,
        String workflowName,
        Integer version,
        String runId,
        String correlationId,
        JsonNode input,
        String idempotencyKey
    ) {}

    /**
     * Query criteria for workflows.
     */
    record WorkflowQuery(
        String namespace,
        String workflowName,
        WorkflowState state,
        String correlationId,
        int limit,
        int offset
    ) {}
}

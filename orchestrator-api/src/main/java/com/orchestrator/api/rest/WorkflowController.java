package com.orchestrator.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.orchestrator.core.model.WorkflowInstance;
import com.orchestrator.core.model.WorkflowState;
import com.orchestrator.engine.service.WorkflowService;
import com.orchestrator.engine.service.WorkflowService.StartWorkflowRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for workflow management.
 */
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /**
     * Start a new workflow instance.
     */
    @PostMapping("/{namespace}/{workflowName}/start")
    public ResponseEntity<WorkflowInstanceResponse> startWorkflow(
            @PathVariable String namespace,
            @PathVariable String workflowName,
            @RequestBody StartWorkflowRequestDto request) {
        
        WorkflowInstance instance = workflowService.startWorkflow(new StartWorkflowRequest(
            namespace,
            workflowName,
            request.version(),
            request.runId(),
            request.correlationId(),
            request.input(),
            request.idempotencyKey()
        ));
        
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(WorkflowInstanceResponse.from(instance));
    }

    /**
     * Get workflow instance by ID.
     */
    @GetMapping("/{instanceId}")
    public ResponseEntity<WorkflowInstanceResponse> getWorkflow(
            @PathVariable UUID instanceId) {
        
        WorkflowInstance instance = workflowService.getWorkflow(instanceId);
        return ResponseEntity.ok(WorkflowInstanceResponse.from(instance));
    }

    /**
     * Signal a workflow.
     */
    @PostMapping("/{instanceId}/signal")
    public ResponseEntity<Map<String, Object>> signalWorkflow(
            @PathVariable UUID instanceId,
            @RequestBody SignalRequest request) {
        
        workflowService.signalWorkflow(instanceId, request.signalName(), request.payload());
        
        return ResponseEntity.ok(Map.of(
            "accepted", true,
            "signalName", request.signalName()
        ));
    }

    /**
     * Pause a workflow.
     */
    @PostMapping("/{instanceId}/pause")
    public ResponseEntity<WorkflowInstanceResponse> pauseWorkflow(
            @PathVariable UUID instanceId,
            @RequestBody(required = false) PauseRequest request) {
        
        String reason = request != null ? request.reason() : "Manual pause";
        workflowService.pauseWorkflow(instanceId, reason);
        
        WorkflowInstance instance = workflowService.getWorkflow(instanceId);
        return ResponseEntity.ok(WorkflowInstanceResponse.from(instance));
    }

    /**
     * Resume a paused workflow.
     */
    @PostMapping("/{instanceId}/resume")
    public ResponseEntity<WorkflowInstanceResponse> resumeWorkflow(
            @PathVariable UUID instanceId) {
        
        workflowService.resumeWorkflow(instanceId);
        
        WorkflowInstance instance = workflowService.getWorkflow(instanceId);
        return ResponseEntity.ok(WorkflowInstanceResponse.from(instance));
    }

    /**
     * Cancel a workflow.
     */
    @PostMapping("/{instanceId}/cancel")
    public ResponseEntity<WorkflowInstanceResponse> cancelWorkflow(
            @PathVariable UUID instanceId,
            @RequestBody(required = false) CancelRequest request) {
        
        String reason = request != null ? request.reason() : "Manual cancellation";
        workflowService.cancelWorkflow(instanceId, reason);
        
        WorkflowInstance instance = workflowService.getWorkflow(instanceId);
        return ResponseEntity.ok(WorkflowInstanceResponse.from(instance));
    }

    /**
     * Trigger compensation.
     */
    @PostMapping("/{instanceId}/compensate")
    public ResponseEntity<WorkflowInstanceResponse> triggerCompensation(
            @PathVariable UUID instanceId,
            @RequestBody(required = false) CompensateRequest request) {
        
        String fromTaskId = request != null ? request.fromTaskId() : null;
        workflowService.triggerCompensation(instanceId, fromTaskId);
        
        WorkflowInstance instance = workflowService.getWorkflow(instanceId);
        return ResponseEntity.ok(WorkflowInstanceResponse.from(instance));
    }

    /**
     * Query workflows.
     */
    @GetMapping
    public ResponseEntity<List<WorkflowInstanceResponse>> queryWorkflows(
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String workflowName,
            @RequestParam(required = false) WorkflowState state,
            @RequestParam(required = false) String correlationId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        
        List<WorkflowInstance> instances = workflowService.queryWorkflows(
            new WorkflowService.WorkflowQuery(namespace, workflowName, state, correlationId, limit, offset)
        );
        
        List<WorkflowInstanceResponse> responses = instances.stream()
            .map(WorkflowInstanceResponse::from)
            .toList();
        
        return ResponseEntity.ok(responses);
    }

    // ========== DTOs ==========

    public record StartWorkflowRequestDto(
        String runId,
        String correlationId,
        JsonNode input,
        Integer version,
        String idempotencyKey
    ) {}

    public record SignalRequest(
        String signalName,
        JsonNode payload,
        String idempotencyKey
    ) {}

    public record PauseRequest(String reason) {}

    public record CancelRequest(String reason) {}

    public record CompensateRequest(String fromTaskId, String reason) {}

    public record WorkflowInstanceResponse(
        UUID instanceId,
        String namespace,
        String workflowName,
        int workflowVersion,
        String runId,
        String correlationId,
        WorkflowState state,
        String currentTaskId,
        List<String> completedTasks,
        List<String> failedTasks,
        JsonNode input,
        JsonNode output,
        Map<String, JsonNode> taskOutputs,
        String lastError,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        long sequenceNumber
    ) {
        public static WorkflowInstanceResponse from(WorkflowInstance instance) {
            return new WorkflowInstanceResponse(
                instance.instanceId(),
                instance.namespace(),
                instance.workflowName(),
                instance.workflowVersion(),
                instance.runId(),
                instance.correlationId(),
                instance.state(),
                instance.currentTaskId(),
                List.copyOf(instance.completedTaskIds()),
                List.copyOf(instance.failedTaskIds()),
                instance.input(),
                instance.output(),
                instance.taskOutputs(),
                instance.lastError(),
                instance.createdAt(),
                instance.startedAt(),
                instance.completedAt(),
                instance.sequenceNumber()
            );
        }
    }
}

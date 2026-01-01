package com.orchestrator.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.orchestrator.core.model.TaskExecution;
import com.orchestrator.core.model.TaskState;
import com.orchestrator.engine.service.TaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for task management.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * Get task execution by ID.
     */
    @GetMapping("/{executionId}")
    public ResponseEntity<TaskExecutionResponse> getTaskExecution(
            @PathVariable UUID executionId) {
        
        TaskExecution execution = taskService.getTaskExecution(executionId);
        return ResponseEntity.ok(TaskExecutionResponse.from(execution));
    }

    /**
     * Get execution history for a workflow.
     */
    @GetMapping("/workflow/{workflowInstanceId}")
    public ResponseEntity<List<TaskExecutionResponse>> getExecutionHistory(
            @PathVariable UUID workflowInstanceId) {
        
        List<TaskExecution> executions = taskService.getExecutionHistory(workflowInstanceId);
        List<TaskExecutionResponse> responses = executions.stream()
            .map(TaskExecutionResponse::from)
            .toList();
        
        return ResponseEntity.ok(responses);
    }

    /**
     * Retry a failed task.
     */
    @PostMapping("/{workflowInstanceId}/{taskId}/retry")
    public ResponseEntity<TaskExecutionResponse> retryTask(
            @PathVariable UUID workflowInstanceId,
            @PathVariable String taskId,
            @RequestBody(required = false) RetryRequest request) {
        
        JsonNode overrideInput = request != null ? request.overrideInput() : null;
        TaskExecution execution = taskService.retryTask(workflowInstanceId, taskId, overrideInput);
        
        return ResponseEntity.ok(TaskExecutionResponse.from(execution));
    }

    /**
     * Force complete a task (human override).
     */
    @PostMapping("/{executionId}/force-complete")
    public ResponseEntity<TaskExecutionResponse> forceCompleteTask(
            @PathVariable UUID executionId,
            @RequestBody ForceCompleteRequest request) {
        
        taskService.forceCompleteTask(
            executionId, 
            request.output(), 
            request.reason(),
            request.operator()
        );
        
        TaskExecution execution = taskService.getTaskExecution(executionId);
        return ResponseEntity.ok(TaskExecutionResponse.from(execution));
    }

    // ========== Worker APIs ==========

    /**
     * Poll for available tasks (worker API).
     */
    @PostMapping("/poll")
    public ResponseEntity<List<TaskExecutionResponse>> pollTasks(
            @RequestBody PollRequest request) {
        
        List<TaskExecution> executions = taskService.pollTasks(
            request.activityType(),
            request.workerId(),
            request.maxTasks()
        );
        
        List<TaskExecutionResponse> responses = executions.stream()
            .map(TaskExecutionResponse::from)
            .toList();
        
        return ResponseEntity.ok(responses);
    }

    /**
     * Complete a task (worker API).
     */
    @PostMapping("/{executionId}/complete")
    public ResponseEntity<Map<String, Object>> completeTask(
            @PathVariable UUID executionId,
            @RequestBody CompleteTaskRequest request) {
        
        taskService.completeTask(
            executionId,
            request.workerId(),
            request.fenceToken(),
            request.output()
        );
        
        return ResponseEntity.ok(Map.of("completed", true));
    }

    /**
     * Report task failure (worker API).
     */
    @PostMapping("/{executionId}/fail")
    public ResponseEntity<Map<String, Object>> failTask(
            @PathVariable UUID executionId,
            @RequestBody FailTaskRequest request) {
        
        taskService.failTask(
            executionId,
            request.workerId(),
            request.fenceToken(),
            request.errorCode(),
            request.errorMessage(),
            request.stackTrace()
        );
        
        return ResponseEntity.ok(Map.of("recorded", true));
    }

    /**
     * Heartbeat / lease renewal (worker API).
     */
    @PostMapping("/{executionId}/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeat(
            @PathVariable UUID executionId,
            @RequestBody HeartbeatRequest request) {
        
        boolean renewed = taskService.renewLease(
            executionId,
            request.workerId(),
            request.fenceToken()
        );
        
        return ResponseEntity.ok(Map.of("renewed", renewed));
    }

    // ========== DTOs ==========

    public record RetryRequest(JsonNode overrideInput, String reason) {}

    public record ForceCompleteRequest(
        JsonNode output,
        String reason,
        String operator
    ) {}

    public record PollRequest(
        String activityType,
        UUID workerId,
        int maxTasks
    ) {}

    public record CompleteTaskRequest(
        UUID workerId,
        long fenceToken,
        JsonNode output
    ) {}

    public record FailTaskRequest(
        UUID workerId,
        long fenceToken,
        String errorCode,
        String errorMessage,
        String stackTrace
    ) {}

    public record HeartbeatRequest(
        UUID workerId,
        long fenceToken
    ) {}

    public record TaskExecutionResponse(
        UUID executionId,
        UUID workflowInstanceId,
        String taskId,
        String idempotencyKey,
        int attemptNumber,
        TaskState state,
        UUID leaseHolder,
        Instant leaseExpiresAt,
        long fenceToken,
        JsonNode input,
        JsonNode output,
        String errorMessage,
        String errorCode,
        Instant scheduledAt,
        Instant startedAt,
        Instant completedAt,
        String workerId
    ) {
        public static TaskExecutionResponse from(TaskExecution execution) {
            return new TaskExecutionResponse(
                execution.executionId(),
                execution.workflowInstanceId(),
                execution.taskId(),
                execution.idempotencyKey(),
                execution.attemptNumber(),
                execution.state(),
                execution.leaseHolder(),
                execution.leaseExpiresAt(),
                execution.fenceToken(),
                execution.input(),
                execution.output(),
                execution.errorMessage(),
                execution.errorCode(),
                execution.scheduledAt(),
                execution.startedAt(),
                execution.completedAt(),
                execution.workerId()
            );
        }
    }
}

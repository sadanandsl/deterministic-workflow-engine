package com.orchestrator.engine.coordinator;

import com.fasterxml.jackson.databind.JsonNode;
import com.orchestrator.core.model.*;
import com.orchestrator.core.repository.*;
import com.orchestrator.core.exception.*;
import com.orchestrator.engine.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Coordinator for task execution lifecycle.
 * Manages task acquisition, completion, failure, and retry.
 */
public class TaskCoordinator implements TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskCoordinator.class);
    
    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(30);

    private final WorkflowDefinitionRepository definitionRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final TaskExecutionRepository taskExecutionRepository;
    private final EventRepository eventRepository;
    private final LeaseRepository leaseRepository;
    private final WorkflowCoordinator.TaskQueuePublisher taskQueuePublisher;

    public TaskCoordinator(
            WorkflowDefinitionRepository definitionRepository,
            WorkflowInstanceRepository instanceRepository,
            TaskExecutionRepository taskExecutionRepository,
            EventRepository eventRepository,
            LeaseRepository leaseRepository,
            WorkflowCoordinator.TaskQueuePublisher taskQueuePublisher) {
        this.definitionRepository = definitionRepository;
        this.instanceRepository = instanceRepository;
        this.taskExecutionRepository = taskExecutionRepository;
        this.eventRepository = eventRepository;
        this.leaseRepository = leaseRepository;
        this.taskQueuePublisher = taskQueuePublisher;
    }

    @Override
    public TaskExecution scheduleTask(UUID workflowInstanceId, String taskId, JsonNode input) {
        WorkflowInstance instance = instanceRepository.findById(workflowInstanceId)
            .orElseThrow(() -> new NotFoundException("WorkflowInstance", workflowInstanceId.toString()));
        
        int attemptCount = taskExecutionRepository.getCurrentAttemptCount(workflowInstanceId, taskId);
        int nextAttempt = attemptCount + 1;
        
        TaskExecution execution = TaskExecution.create(
            workflowInstanceId,
            taskId,
            nextAttempt,
            input,
            UUID.randomUUID().toString()
        );
        
        taskExecutionRepository.save(execution);
        execution = execution.withQueued();
        taskExecutionRepository.update(execution);
        
        // Get task definition for activity type
        WorkflowDefinition definition = definitionRepository.find(
            instance.namespace(), instance.workflowName(), instance.workflowVersion())
            .orElseThrow(() -> new NotFoundException("WorkflowDefinition", instance.workflowName()));
        
        TaskDefinition taskDef = definition.getTask(taskId);
        taskQueuePublisher.publish(taskDef.activityType(), execution);
        
        log.info("Scheduled task {} for workflow {}, attempt {}", taskId, workflowInstanceId, nextAttempt);
        return execution;
    }

    @Override
    public Optional<TaskExecution> acquireTask(UUID executionId, UUID workerId) {
        TaskExecution execution = taskExecutionRepository.findById(executionId)
            .orElseThrow(() -> new NotFoundException("TaskExecution", executionId.toString()));
        
        if (execution.state() != TaskState.QUEUED) {
            log.debug("Task {} not in QUEUED state: {}", executionId, execution.state());
            return Optional.empty();
        }
        
        // Try to acquire lease
        String leaseKey = ExecutionLease.createLeaseKey(execution.workflowInstanceId(), execution.taskId());
        long fenceToken = leaseRepository.getFenceToken(leaseKey) + 1;
        
        ExecutionLease lease = ExecutionLease.create(
            execution.workflowInstanceId(),
            execution.taskId(),
            workerId,
            "worker-" + workerId, // TODO: Get actual worker address
            DEFAULT_LEASE_DURATION,
            fenceToken
        );
        
        if (!leaseRepository.tryAcquire(lease)) {
            log.debug("Failed to acquire lease for task {}", executionId);
            return Optional.empty();
        }
        
        // Update task execution
        execution = execution.withLeaseAcquired(workerId, lease.expiresAt(), fenceToken);
        taskExecutionRepository.update(execution);
        
        recordEvent(execution.workflowInstanceId(), EventType.TASK_STARTED,
            Map.of("taskId", execution.taskId(), "executionId", executionId, 
                   "workerId", workerId, "fenceToken", fenceToken),
            "task-started:" + execution.idempotencyKey());
        
        log.info("Task {} acquired by worker {}", executionId, workerId);
        return Optional.of(execution);
    }

    @Override
    public List<TaskExecution> pollTasks(String activityType, UUID workerId, int maxTasks) {
        List<TaskExecution> queued = taskExecutionRepository.findQueuedByActivityType(activityType, maxTasks);
        List<TaskExecution> acquired = new ArrayList<>();
        
        for (TaskExecution execution : queued) {
            Optional<TaskExecution> task = acquireTask(execution.executionId(), workerId);
            task.ifPresent(acquired::add);
            
            if (acquired.size() >= maxTasks) {
                break;
            }
        }
        
        return acquired;
    }

    @Override
    public void completeTask(UUID executionId, UUID workerId, long fenceToken, JsonNode output) {
        TaskExecution execution = taskExecutionRepository.findById(executionId)
            .orElseThrow(() -> new NotFoundException("TaskExecution", executionId.toString()));
        
        // Validate fence token
        String leaseKey = ExecutionLease.createLeaseKey(execution.workflowInstanceId(), execution.taskId());
        if (!leaseRepository.validateFenceToken(leaseKey, fenceToken)) {
            log.warn("Stale fence token for task {}: expected current, got {}", executionId, fenceToken);
            throw new LeaseAcquisitionException(leaseKey, "fence token mismatch");
        }
        
        // Update task execution
        execution = execution.withCompleted(output);
        taskExecutionRepository.update(execution);
        
        // Release lease
        leaseRepository.release(leaseKey, workerId);
        
        recordEvent(execution.workflowInstanceId(), EventType.TASK_COMPLETED,
            Map.of("taskId", execution.taskId(), "executionId", executionId, 
                   "output", output != null ? output : "null"),
            "task-completed:" + execution.idempotencyKey());
        
        log.info("Task {} completed by worker {}", executionId, workerId);
        
        // Trigger workflow progression
        onTaskCompleted(execution, output);
    }

    @Override
    public void failTask(UUID executionId, UUID workerId, long fenceToken,
                         String errorCode, String errorMessage, String stackTrace) {
        TaskExecution execution = taskExecutionRepository.findById(executionId)
            .orElseThrow(() -> new NotFoundException("TaskExecution", executionId.toString()));
        
        // Validate fence token
        String leaseKey = ExecutionLease.createLeaseKey(execution.workflowInstanceId(), execution.taskId());
        if (!leaseRepository.validateFenceToken(leaseKey, fenceToken)) {
            log.warn("Stale fence token for task {}", executionId);
            throw new LeaseAcquisitionException(leaseKey, "fence token mismatch");
        }
        
        // Update task execution
        execution = execution.withFailed(errorMessage, errorCode, stackTrace);
        taskExecutionRepository.update(execution);
        
        // Release lease
        leaseRepository.release(leaseKey, workerId);
        
        recordEvent(execution.workflowInstanceId(), EventType.TASK_FAILED,
            Map.of("taskId", execution.taskId(), "executionId", executionId,
                   "errorCode", errorCode, "errorMessage", errorMessage),
            "task-failed:" + execution.idempotencyKey());
        
        log.info("Task {} failed: {} - {}", executionId, errorCode, errorMessage);
        
        // Handle retry or failure
        onTaskFailed(execution, errorCode);
    }

    @Override
    public boolean renewLease(UUID executionId, UUID workerId, long fenceToken) {
        TaskExecution execution = taskExecutionRepository.findById(executionId)
            .orElseThrow(() -> new NotFoundException("TaskExecution", executionId.toString()));
        
        String leaseKey = ExecutionLease.createLeaseKey(execution.workflowInstanceId(), execution.taskId());
        
        if (!leaseRepository.validateFenceToken(leaseKey, fenceToken)) {
            log.warn("Cannot renew lease for task {}: fence token mismatch", executionId);
            return false;
        }
        
        Instant newExpiry = Instant.now().plus(DEFAULT_LEASE_DURATION);
        boolean renewed = leaseRepository.renew(leaseKey, workerId, newExpiry);
        
        if (renewed) {
            // Update task execution lease expiry
            TaskExecution updated = new TaskExecution(
                execution.executionId(),
                execution.workflowInstanceId(),
                execution.taskId(),
                execution.idempotencyKey(),
                execution.attemptNumber(),
                execution.state(),
                execution.leaseHolder(),
                newExpiry,
                execution.fenceToken(),
                execution.input(),
                execution.output(),
                execution.errorMessage(),
                execution.errorCode(),
                execution.stackTrace(),
                execution.scheduledAt(),
                execution.startedAt(),
                execution.completedAt(),
                execution.traceId(),
                execution.spanId(),
                execution.workerId()
            );
            taskExecutionRepository.update(updated);
            
            recordEvent(execution.workflowInstanceId(), EventType.LEASE_RENEWED,
                Map.of("taskId", execution.taskId(), "executionId", executionId),
                "lease-renewed:" + executionId + ":" + System.currentTimeMillis());
        }
        
        return renewed;
    }

    @Override
    public TaskExecution retryTask(UUID workflowInstanceId, String taskId, JsonNode overrideInput) {
        TaskExecution latest = taskExecutionRepository.findLatestByTask(workflowInstanceId, taskId)
            .orElseThrow(() -> new NotFoundException("TaskExecution", workflowInstanceId + ":" + taskId));
        
        if (!latest.state().isRetriable() && latest.state() != TaskState.COMPLETED) {
            throw new InvalidStateTransitionException("TaskExecution", 
                latest.state().name(), "RETRYING");
        }
        
        JsonNode input = overrideInput != null ? overrideInput : latest.input();
        return scheduleTask(workflowInstanceId, taskId, input);
    }

    @Override
    public void forceCompleteTask(UUID executionId, JsonNode output, String reason, String operator) {
        TaskExecution execution = taskExecutionRepository.findById(executionId)
            .orElseThrow(() -> new NotFoundException("TaskExecution", executionId.toString()));
        
        // Force complete regardless of current state
        execution = execution.withCompleted(output);
        taskExecutionRepository.update(execution);
        
        // Force release any lease
        String leaseKey = ExecutionLease.createLeaseKey(execution.workflowInstanceId(), execution.taskId());
        leaseRepository.forceRelease(leaseKey);
        
        recordEvent(execution.workflowInstanceId(), EventType.HUMAN_OVERRIDE_APPLIED,
            Map.of("taskId", execution.taskId(), "executionId", executionId,
                   "reason", reason, "operator", operator, "output", output != null ? output : "null"),
            "force-complete:" + executionId);
        
        log.warn("Task {} force completed by {}: {}", executionId, operator, reason);
        
        // Trigger workflow progression
        onTaskCompleted(execution, output);
    }

    @Override
    public TaskExecution getTaskExecution(UUID executionId) {
        return taskExecutionRepository.findById(executionId)
            .orElseThrow(() -> new NotFoundException("TaskExecution", executionId.toString()));
    }

    @Override
    public List<TaskExecution> getExecutionHistory(UUID workflowInstanceId) {
        return taskExecutionRepository.findByWorkflowInstance(workflowInstanceId);
    }

    // ========== Internal Methods ==========

    private void onTaskCompleted(TaskExecution execution, JsonNode output) {
        WorkflowInstance originalInstance = instanceRepository.findById(execution.workflowInstanceId())
            .orElseThrow(() -> new NotFoundException("WorkflowInstance", execution.workflowInstanceId().toString()));
        
        // Update workflow instance with completed task
        WorkflowInstance instance = originalInstance.withTaskCompleted(execution.taskId(), output);
        instanceRepository.update(instance);
        
        // Get workflow definition
        WorkflowDefinition definition = definitionRepository.find(
            instance.namespace(), instance.workflowName(), instance.workflowVersion())
            .orElseThrow(() -> new NotFoundException("WorkflowDefinition", instance.workflowName()));
        
        // Check if this is a terminal task
        if (definition.isTerminalTask(execution.taskId())) {
            completeWorkflow(instance, output);
            return;
        }
        
        // Schedule next tasks
        List<String> nextTasks = definition.getNextTasks(execution.taskId());
        for (String nextTaskId : nextTasks) {
            scheduleTask(instance.instanceId(), nextTaskId, instance.input());
        }
    }

    private void onTaskFailed(TaskExecution execution, String errorCode) {
        WorkflowInstance instance = instanceRepository.findById(execution.workflowInstanceId())
            .orElseThrow(() -> new NotFoundException("WorkflowInstance", execution.workflowInstanceId().toString()));
        
        // Get workflow and task definition
        WorkflowDefinition definition = definitionRepository.find(
            instance.namespace(), instance.workflowName(), instance.workflowVersion())
            .orElseThrow(() -> new NotFoundException("WorkflowDefinition", instance.workflowName()));
        
        TaskDefinition taskDef = definition.getTask(execution.taskId());
        RetryPolicy retryPolicy = taskDef.effectiveRetryPolicy(definition.defaultRetryPolicy());
        
        // Check if retry is possible
        if (retryPolicy.shouldRetry(errorCode) && retryPolicy.hasMoreAttempts(execution.attemptNumber())) {
            Duration backoff = retryPolicy.computeBackoff(execution.attemptNumber());
            log.info("Scheduling retry for task {} in {}", execution.taskId(), backoff);
            
            recordEvent(instance.instanceId(), EventType.TASK_RETRYING,
                Map.of("taskId", execution.taskId(), "attemptNumber", execution.attemptNumber() + 1,
                       "backoff", backoff.toString()),
                "task-retrying:" + execution.idempotencyKey() + ":" + execution.attemptNumber());
            
            // TODO: Schedule with delay
            scheduleTask(instance.instanceId(), execution.taskId(), execution.input());
        } else {
            // Retry exhausted - fail workflow
            log.warn("Task {} exhausted retries, failing workflow {}", execution.taskId(), instance.instanceId());
            failWorkflow(instance, execution.taskId(), execution.errorMessage());
        }
    }

    private void completeWorkflow(WorkflowInstance instance, JsonNode output) {
        WorkflowInstance updated = instance.toBuilder()
            .state(WorkflowState.COMPLETED)
            .output(output)
            .completedAt(Instant.now())
            .incrementSequence()
            .build();
        
        instanceRepository.update(updated);
        
        recordEvent(instance.instanceId(), EventType.WORKFLOW_COMPLETED,
            Map.of("output", output != null ? output : "null"),
            "workflow-completed:" + instance.instanceId());
        
        log.info("Workflow {} completed", instance.instanceId());
    }

    private void failWorkflow(WorkflowInstance instance, String failedTaskId, String error) {
        WorkflowInstance updated = instance.withTaskFailed(failedTaskId, error)
            .toBuilder()
            .state(WorkflowState.FAILED)
            .completedAt(Instant.now())
            .incrementSequence()
            .build();
        
        instanceRepository.update(updated);
        
        recordEvent(instance.instanceId(), EventType.WORKFLOW_FAILED,
            Map.of("failedTaskId", failedTaskId, "error", error),
            "workflow-failed:" + instance.instanceId());
        
        log.warn("Workflow {} failed at task {}: {}", instance.instanceId(), failedTaskId, error);
    }

    private void recordEvent(UUID workflowInstanceId, EventType type, Map<String, Object> payload, String idempotencyKey) {
        long sequenceNumber = eventRepository.getNextSequenceNumber(workflowInstanceId);
        
        Event event = Event.create(
            workflowInstanceId,
            sequenceNumber,
            type,
            toJsonNode(payload),
            idempotencyKey,
            null,
            Event.ACTOR_SYSTEM,
            "task-coordinator"
        );
        
        eventRepository.append(event);
    }

    private JsonNode toJsonNode(Map<String, Object> map) {
        // TODO: Use ObjectMapper
        return null;
    }
}

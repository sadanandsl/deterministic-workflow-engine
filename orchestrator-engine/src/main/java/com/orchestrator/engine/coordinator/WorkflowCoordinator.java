package com.orchestrator.engine.coordinator;

import com.fasterxml.jackson.databind.JsonNode;
import com.orchestrator.core.model.*;
import com.orchestrator.core.repository.*;
import com.orchestrator.core.exception.*;
import com.orchestrator.engine.service.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Core workflow coordinator responsible for orchestrating workflow execution.
 * Manages state transitions, task scheduling, and failure handling.
 * 
 * This is the central control plane component.
 */
public class WorkflowCoordinator implements WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowCoordinator.class);

    private final WorkflowDefinitionRepository definitionRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final TaskExecutionRepository taskExecutionRepository;
    private final EventRepository eventRepository;
    private final LeaseRepository leaseRepository;
    private final TaskQueuePublisher taskQueuePublisher;

    public WorkflowCoordinator(
            WorkflowDefinitionRepository definitionRepository,
            WorkflowInstanceRepository instanceRepository,
            TaskExecutionRepository taskExecutionRepository,
            EventRepository eventRepository,
            LeaseRepository leaseRepository,
            TaskQueuePublisher taskQueuePublisher) {
        this.definitionRepository = definitionRepository;
        this.instanceRepository = instanceRepository;
        this.taskExecutionRepository = taskExecutionRepository;
        this.eventRepository = eventRepository;
        this.leaseRepository = leaseRepository;
        this.taskQueuePublisher = taskQueuePublisher;
    }

    @Override
    public WorkflowDefinition registerWorkflow(WorkflowDefinition definition) {
        log.info("Registering workflow: {}:{}", definition.namespace(), definition.name());
        
        // Validate workflow definition
        validateWorkflowDefinition(definition);
        
        // Assign next version
        int nextVersion = definitionRepository.getNextVersion(definition.namespace(), definition.name());
        
        WorkflowDefinition versioned = new WorkflowDefinition(
            definition.namespace(),
            definition.name(),
            nextVersion,
            definition.tasks(),
            definition.edges(),
            definition.entryTaskId(),
            definition.terminalTaskIds(),
            definition.defaultRetryPolicy(),
            definition.maxDuration(),
            definition.compensationStrategy(),
            Instant.now(),
            definition.createdBy(),
            definition.description(),
            definition.labels()
        );
        
        definitionRepository.save(versioned);
        
        log.info("Registered workflow: {}:{}:{}", versioned.namespace(), versioned.name(), versioned.version());
        return versioned;
    }

    @Override
    public WorkflowInstance startWorkflow(StartWorkflowRequest request) {
        log.info("Starting workflow: {}:{} with runId={}", 
            request.namespace(), request.workflowName(), request.runId());
        
        // Check for existing instance with same idempotency key
        String runId = request.runId() != null ? request.runId() : UUID.randomUUID().toString();
        Optional<WorkflowInstance> existing = instanceRepository.findByRunId(
            request.namespace(), request.workflowName(), runId);
        
        if (existing.isPresent()) {
            log.info("Found existing instance with runId={}: {}", runId, existing.get().instanceId());
            return existing.get();
        }
        
        // Find workflow definition
        WorkflowDefinition definition;
        if (request.version() != null) {
            definition = definitionRepository.find(
                request.namespace(), request.workflowName(), request.version())
                .orElseThrow(() -> new NotFoundException("WorkflowDefinition", 
                    request.namespace() + ":" + request.workflowName() + ":" + request.version()));
        } else {
            definition = definitionRepository.findLatest(request.namespace(), request.workflowName())
                .orElseThrow(() -> new NotFoundException("WorkflowDefinition", 
                    request.namespace() + ":" + request.workflowName()));
        }
        
        // Calculate deadline
        Instant deadline = definition.maxDuration() != null 
            ? Instant.now().plus(definition.maxDuration()) 
            : null;
        
        // Create workflow instance
        WorkflowInstance instance = WorkflowInstance.create(
            request.namespace(),
            request.workflowName(),
            definition.version(),
            runId,
            request.correlationId(),
            request.input(),
            deadline
        );
        
        // Save instance
        instanceRepository.save(instance);
        
        // Record workflow created event
        recordEvent(instance.instanceId(), EventType.WORKFLOW_CREATED, 
            Map.of("input", request.input()), "workflow-created:" + instance.instanceId());
        
        // Transition to RUNNING and schedule entry task
        instance = transitionState(instance, WorkflowState.RUNNING);
        
        recordEvent(instance.instanceId(), EventType.WORKFLOW_STARTED, 
            Map.of(), "workflow-started:" + instance.instanceId());
        
        // Schedule entry task
        scheduleTask(instance, definition.entryTaskId(), definition);
        
        log.info("Started workflow instance: {}", instance.instanceId());
        return instance;
    }

    @Override
    public WorkflowInstance getWorkflow(UUID instanceId) {
        return instanceRepository.findById(instanceId)
            .orElseThrow(() -> new NotFoundException("WorkflowInstance", instanceId.toString()));
    }

    @Override
    public void signalWorkflow(UUID instanceId, String signalName, JsonNode payload) {
        log.info("Signaling workflow {}: {}", instanceId, signalName);
        
        WorkflowInstance instance = getWorkflow(instanceId);
        
        if (!instance.canAcceptSignals()) {
            throw new InvalidStateTransitionException("Workflow", 
                instance.state().name(), "cannot accept signals");
        }
        
        recordEvent(instanceId, EventType.SIGNAL_RECEIVED, 
            Map.of("signalName", signalName, "payload", payload),
            "signal:" + instanceId + ":" + signalName + ":" + System.currentTimeMillis());
        
        // TODO: Wake up any WAIT tasks listening for this signal
    }

    @Override
    public void pauseWorkflow(UUID instanceId, String reason) {
        log.info("Pausing workflow {}: {}", instanceId, reason);
        
        WorkflowInstance instance = getWorkflow(instanceId);
        
        if (instance.state() != WorkflowState.RUNNING) {
            throw new InvalidStateTransitionException(instance.state(), WorkflowState.PAUSED);
        }
        
        transitionState(instance, WorkflowState.PAUSED);
        
        recordEvent(instanceId, EventType.WORKFLOW_PAUSED, 
            Map.of("reason", reason), "paused:" + instanceId);
    }

    @Override
    public void resumeWorkflow(UUID instanceId) {
        log.info("Resuming workflow {}", instanceId);
        
        WorkflowInstance instance = getWorkflow(instanceId);
        
        if (instance.state() != WorkflowState.PAUSED) {
            throw new InvalidStateTransitionException(instance.state(), WorkflowState.RUNNING);
        }
        
        transitionState(instance, WorkflowState.RUNNING);
        
        recordEvent(instanceId, EventType.WORKFLOW_RESUMED, 
            Map.of(), "resumed:" + instanceId);
        
        // TODO: Resume any pending tasks
    }

    @Override
    public void cancelWorkflow(UUID instanceId, String reason) {
        log.info("Cancelling workflow {}: {}", instanceId, reason);
        
        WorkflowInstance instance = getWorkflow(instanceId);
        
        if (instance.isTerminal()) {
            throw new InvalidStateTransitionException(instance.state(), WorkflowState.FAILED);
        }
        
        transitionState(instance, WorkflowState.FAILED);
        
        recordEvent(instanceId, EventType.WORKFLOW_CANCELLED, 
            Map.of("reason", reason), "cancelled:" + instanceId);
        
        // TODO: Cancel any running tasks
    }

    @Override
    public void triggerCompensation(UUID instanceId, String fromTaskId) {
        log.info("Triggering compensation for workflow {} from task {}", instanceId, fromTaskId);
        
        WorkflowInstance instance = getWorkflow(instanceId);
        
        if (instance.state() != WorkflowState.FAILED) {
            throw new InvalidStateTransitionException(instance.state(), WorkflowState.COMPENSATING);
        }
        
        transitionState(instance, WorkflowState.COMPENSATING);
        
        recordEvent(instanceId, EventType.COMPENSATION_TRIGGERED, 
            Map.of("fromTaskId", fromTaskId != null ? fromTaskId : "all"),
            "compensation-triggered:" + instanceId);
        
        // TODO: Schedule compensation tasks
    }

    @Override
    public List<WorkflowInstance> queryWorkflows(WorkflowQuery query) {
        // TODO: Implement query logic
        return List.of();
    }

    // ========== Internal Methods ==========

    private void validateWorkflowDefinition(WorkflowDefinition definition) {
        if (definition.namespace() == null || definition.namespace().isBlank()) {
            throw new WorkflowValidationException("namespace", "cannot be empty");
        }
        if (definition.name() == null || definition.name().isBlank()) {
            throw new WorkflowValidationException("name", "cannot be empty");
        }
        if (definition.tasks() == null || definition.tasks().isEmpty()) {
            throw new WorkflowValidationException("tasks", "cannot be empty");
        }
        if (definition.entryTaskId() == null) {
            throw new WorkflowValidationException("entryTaskId", "cannot be null");
        }
        
        // Validate entry task exists
        if (definition.getTask(definition.entryTaskId()) == null) {
            throw new WorkflowValidationException("entryTaskId", 
                "references non-existent task: " + definition.entryTaskId());
        }
        
        // Validate all edge targets exist
        for (var entry : definition.edges().entrySet()) {
            for (String target : entry.getValue()) {
                if (definition.getTask(target) == null) {
                    throw new WorkflowValidationException("edges", 
                        "edge from " + entry.getKey() + " references non-existent task: " + target);
                }
            }
        }
        
        // TODO: Validate graph is acyclic (DAG)
    }

    private WorkflowInstance transitionState(WorkflowInstance instance, WorkflowState newState) {
        if (!instance.state().canTransitionTo(newState)) {
            throw new InvalidStateTransitionException(instance.state(), newState);
        }
        
        WorkflowInstance updated = instance.toBuilder()
            .state(newState)
            .startedAt(newState == WorkflowState.RUNNING && instance.startedAt() == null 
                ? Instant.now() : instance.startedAt())
            .completedAt(newState.isTerminal() ? Instant.now() : null)
            .incrementSequence()
            .build();
        
        instanceRepository.update(updated);
        
        return updated;
    }

    private void scheduleTask(WorkflowInstance instance, String taskId, WorkflowDefinition definition) {
        TaskDefinition taskDef = definition.getTask(taskId);
        if (taskDef == null) {
            throw new NotFoundException("TaskDefinition", taskId);
        }
        
        // Get current attempt count
        int attemptCount = taskExecutionRepository.getCurrentAttemptCount(instance.instanceId(), taskId);
        int nextAttempt = attemptCount + 1;
        
        // Create task execution
        TaskExecution execution = TaskExecution.create(
            instance.instanceId(),
            taskId,
            nextAttempt,
            instance.input(), // TODO: Apply input expression
            UUID.randomUUID().toString() // trace ID
        );
        
        // Save and queue
        taskExecutionRepository.save(execution);
        execution = execution.withQueued();
        taskExecutionRepository.update(execution);
        
        // Publish to task queue
        taskQueuePublisher.publish(taskDef.activityType(), execution);
        
        recordEvent(instance.instanceId(), EventType.TASK_SCHEDULED,
            Map.of("taskId", taskId, "executionId", execution.executionId(), "attemptNumber", nextAttempt),
            "task-scheduled:" + execution.idempotencyKey());
        
        log.debug("Scheduled task {} for workflow {}, attempt {}", taskId, instance.instanceId(), nextAttempt);
    }

    private void recordEvent(UUID workflowInstanceId, EventType type, Map<String, Object> payload, String idempotencyKey) {
        long sequenceNumber = eventRepository.getNextSequenceNumber(workflowInstanceId);
        
        Event event = Event.create(
            workflowInstanceId,
            sequenceNumber,
            type,
            toJsonNode(payload),
            idempotencyKey,
            null, // traceId
            Event.ACTOR_SYSTEM,
            "coordinator"
        );
        
        eventRepository.append(event);
    }

    private JsonNode toJsonNode(Map<String, Object> map) {
        // TODO: Use ObjectMapper
        return null;
    }

    /**
     * Interface for publishing tasks to the queue.
     */
    public interface TaskQueuePublisher {
        void publish(String activityType, TaskExecution execution);
    }
}

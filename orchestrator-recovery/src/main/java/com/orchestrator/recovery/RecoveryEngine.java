package com.orchestrator.recovery;

import com.orchestrator.core.model.*;
import com.orchestrator.core.repository.*;
import com.orchestrator.engine.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Recovery Engine responsible for detecting and recovering from failures.
 * 
 * Responsibilities:
 * - Detect expired leases and requeue tasks
 * - Detect timed out tasks
 * - Detect stuck workflows
 * - Handle deadline violations
 */
public class RecoveryEngine {

    private static final Logger log = LoggerFactory.getLogger(RecoveryEngine.class);

    private static final Duration LEASE_CHECK_INTERVAL = Duration.ofSeconds(5);
    private static final Duration TIMEOUT_CHECK_INTERVAL = Duration.ofSeconds(10);
    private static final Duration STUCK_WORKFLOW_THRESHOLD = Duration.ofMinutes(30);
    private static final int BATCH_SIZE = 100;

    private final LeaseRepository leaseRepository;
    private final TaskExecutionRepository taskExecutionRepository;
    private final WorkflowInstanceRepository workflowInstanceRepository;
    private final EventRepository eventRepository;
    private final TaskService taskService;
    
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public RecoveryEngine(
            LeaseRepository leaseRepository,
            TaskExecutionRepository taskExecutionRepository,
            WorkflowInstanceRepository workflowInstanceRepository,
            EventRepository eventRepository,
            TaskService taskService) {
        this.leaseRepository = leaseRepository;
        this.taskExecutionRepository = taskExecutionRepository;
        this.workflowInstanceRepository = workflowInstanceRepository;
        this.eventRepository = eventRepository;
        this.taskService = taskService;
        this.scheduler = Executors.newScheduledThreadPool(3);
    }

    /**
     * Start the recovery engine.
     */
    public void start() {
        if (running) {
            log.warn("Recovery engine already running");
            return;
        }
        
        running = true;
        log.info("Starting recovery engine");
        
        // Schedule lease recovery
        scheduler.scheduleWithFixedDelay(
            this::recoverExpiredLeases,
            LEASE_CHECK_INTERVAL.toSeconds(),
            LEASE_CHECK_INTERVAL.toSeconds(),
            TimeUnit.SECONDS
        );
        
        // Schedule timeout detection
        scheduler.scheduleWithFixedDelay(
            this::detectTimedOutTasks,
            TIMEOUT_CHECK_INTERVAL.toSeconds(),
            TIMEOUT_CHECK_INTERVAL.toSeconds(),
            TimeUnit.SECONDS
        );
        
        // Schedule stuck workflow detection
        scheduler.scheduleWithFixedDelay(
            this::detectStuckWorkflows,
            60, 60, TimeUnit.SECONDS
        );
        
        // Schedule deadline violation detection
        scheduler.scheduleWithFixedDelay(
            this::detectDeadlineViolations,
            30, 30, TimeUnit.SECONDS
        );
        
        log.info("Recovery engine started");
    }

    /**
     * Stop the recovery engine.
     */
    public void stop() {
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Recovery engine stopped");
    }

    /**
     * Recover tasks with expired leases.
     * Workers may have crashed without releasing their leases.
     */
    private void recoverExpiredLeases() {
        if (!running) return;
        
        try {
            Instant now = Instant.now();
            List<ExecutionLease> expiredLeases = leaseRepository.findExpired(now, BATCH_SIZE);
            
            if (expiredLeases.isEmpty()) {
                return;
            }
            
            log.info("Found {} expired leases", expiredLeases.size());
            
            for (ExecutionLease lease : expiredLeases) {
                try {
                    recoverExpiredLease(lease);
                } catch (Exception e) {
                    log.error("Failed to recover lease: {}", lease.leaseKey(), e);
                }
            }
        } catch (Exception e) {
            log.error("Error in lease recovery", e);
        }
    }

    private void recoverExpiredLease(ExecutionLease lease) {
        log.info("Recovering expired lease: {} (held by {})", lease.leaseKey(), lease.holderId());
        
        // Force release the lease (increments fence token)
        long newFenceToken = leaseRepository.forceRelease(lease.leaseKey());
        
        // Parse lease key to get workflow instance and task IDs
        String[] parts = lease.leaseKey().split(":");
        if (parts.length != 2) {
            log.error("Invalid lease key format: {}", lease.leaseKey());
            return;
        }
        
        java.util.UUID workflowInstanceId = java.util.UUID.fromString(parts[0]);
        String taskId = parts[1];
        
        // Find the task execution
        TaskExecution execution = taskExecutionRepository.findLatestByTask(workflowInstanceId, taskId)
            .orElse(null);
        
        if (execution == null) {
            log.warn("No task execution found for lease: {}", lease.leaseKey());
            return;
        }
        
        // Mark task as timed out
        TaskExecution timedOut = execution.withTimedOut();
        taskExecutionRepository.update(timedOut);
        
        // Record event
        recordEvent(workflowInstanceId, EventType.LEASE_EXPIRED,
            "lease-expired:" + lease.leaseKey() + ":" + now());
        
        // Retry the task
        log.info("Requeuing task {} after lease expiry", taskId);
        taskService.retryTask(workflowInstanceId, taskId, execution.input());
    }

    /**
     * Detect tasks that have exceeded their timeout.
     */
    private void detectTimedOutTasks() {
        if (!running) return;
        
        try {
            List<TaskExecution> expiredLeases = taskExecutionRepository.findExpiredLeases(Instant.now(), BATCH_SIZE);
            
            for (TaskExecution execution : expiredLeases) {
                if (execution.state() == TaskState.RUNNING) {
                    log.warn("Task {} has expired lease, marking as timed out", execution.executionId());
                    
                    // Force release lease
                    String leaseKey = ExecutionLease.createLeaseKey(
                        execution.workflowInstanceId(), execution.taskId());
                    leaseRepository.forceRelease(leaseKey);
                    
                    // Update task state
                    TaskExecution timedOut = execution.withTimedOut();
                    taskExecutionRepository.update(timedOut);
                    
                    recordEvent(execution.workflowInstanceId(), EventType.TASK_TIMED_OUT,
                        "task-timeout:" + execution.idempotencyKey());
                }
            }
        } catch (Exception e) {
            log.error("Error in timeout detection", e);
        }
    }

    /**
     * Detect workflows that appear to be stuck (not progressing).
     */
    private void detectStuckWorkflows() {
        if (!running) return;
        
        try {
            Instant stuckThreshold = Instant.now().minus(STUCK_WORKFLOW_THRESHOLD);
            List<WorkflowInstance> stuckInstances = workflowInstanceRepository
                .findStuckInstances(stuckThreshold, BATCH_SIZE);
            
            for (WorkflowInstance instance : stuckInstances) {
                log.warn("Workflow {} appears stuck (state={}, last update={})",
                    instance.instanceId(), instance.state(), stuckThreshold);
                
                recordEvent(instance.instanceId(), EventType.RECOVERY_STARTED,
                    "stuck-recovery:" + instance.instanceId() + ":" + now());
                
                // TODO: Attempt recovery based on workflow state
                // - Check for orphaned tasks
                // - Check for missing events
                // - Re-evaluate workflow state
            }
        } catch (Exception e) {
            log.error("Error in stuck workflow detection", e);
        }
    }

    /**
     * Detect workflows that have exceeded their deadline.
     */
    private void detectDeadlineViolations() {
        if (!running) return;
        
        try {
            List<WorkflowInstance> expired = workflowInstanceRepository
                .findExpiredDeadlines(Instant.now(), BATCH_SIZE);
            
            for (WorkflowInstance instance : expired) {
                if (!instance.isTerminal()) {
                    log.warn("Workflow {} exceeded deadline", instance.instanceId());
                    
                    recordEvent(instance.instanceId(), EventType.WORKFLOW_FAILED,
                        "deadline-exceeded:" + instance.instanceId());
                    
                    // Mark workflow as failed due to deadline
                    WorkflowInstance failed = instance.toBuilder()
                        .state(WorkflowState.FAILED)
                        .completedAt(Instant.now())
                        .incrementSequence()
                        .build();
                    
                    workflowInstanceRepository.update(failed);
                }
            }
        } catch (Exception e) {
            log.error("Error in deadline violation detection", e);
        }
    }

    private void recordEvent(java.util.UUID workflowInstanceId, EventType type, String idempotencyKey) {
        long sequenceNumber = eventRepository.getNextSequenceNumber(workflowInstanceId);
        
        Event event = Event.create(
            workflowInstanceId,
            sequenceNumber,
            type,
            null,
            idempotencyKey,
            null,
            Event.ACTOR_RECOVERY,
            "recovery-engine"
        );
        
        try {
            eventRepository.append(event);
        } catch (Exception e) {
            log.warn("Failed to record recovery event: {}", idempotencyKey, e);
        }
    }

    private long now() {
        return System.currentTimeMillis();
    }
}

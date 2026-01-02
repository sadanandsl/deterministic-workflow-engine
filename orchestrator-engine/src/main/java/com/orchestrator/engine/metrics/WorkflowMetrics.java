package com.orchestrator.engine.metrics;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;

/**
 * Prometheus metrics for the workflow orchestrator.
 * Exposes key operational metrics for monitoring and alerting.
 * 
 * Metrics exposed:
 * - Workflow counts by state
 * - Task latency histograms
 * - Retry counts and rates
 * - Failure rates
 * - Lease acquisition metrics
 */
@Component
public class WorkflowMetrics implements MeterBinder {

    // Metric names
    public static final String WORKFLOW_COUNT = "orchestrator.workflows";
    public static final String WORKFLOW_STARTED = "orchestrator.workflows.started";
    public static final String WORKFLOW_COMPLETED = "orchestrator.workflows.completed";
    public static final String WORKFLOW_FAILED = "orchestrator.workflows.failed";
    
    public static final String TASK_DURATION = "orchestrator.task.duration";
    public static final String TASK_COUNT = "orchestrator.tasks";
    public static final String TASK_RETRIES = "orchestrator.task.retries";
    public static final String TASK_FAILURES = "orchestrator.task.failures";
    
    public static final String LEASE_ACQUISITIONS = "orchestrator.lease.acquisitions";
    public static final String LEASE_RENEWALS = "orchestrator.lease.renewals";
    public static final String LEASE_EXPIRATIONS = "orchestrator.lease.expirations";
    
    public static final String QUEUE_SIZE = "orchestrator.queue.size";
    public static final String RECOVERY_ATTEMPTS = "orchestrator.recovery.attempts";

    // Saga and compensation metrics
    public static final String SAGA_COMPENSATIONS = "orchestrator.saga.compensations";
    public static final String COMPENSATION_DURATION = "orchestrator.compensation.duration";
    
    // Dead letter metrics
    public static final String DEAD_LETTER_TOTAL = "orchestrator.dead_letter.total";
    public static final String DEAD_LETTER_RETRIED = "orchestrator.dead_letter.retried";
    public static final String DEAD_LETTER_DISCARDED = "orchestrator.dead_letter.discarded";

    private MeterRegistry registry;
    
    // Gauges for workflow states
    private final ConcurrentHashMap<String, AtomicInteger> workflowStateGauges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> taskStateGauges = new ConcurrentHashMap<>();

    @Override
    public void bindTo(MeterRegistry registry) {
        this.registry = registry;
        
        // Register workflow state gauges
        for (String state : new String[]{"PENDING", "RUNNING", "PAUSED", "COMPLETED", "FAILED", "CANCELLED"}) {
            AtomicInteger gauge = new AtomicInteger(0);
            workflowStateGauges.put(state, gauge);
            Gauge.builder(WORKFLOW_COUNT, gauge, AtomicInteger::get)
                .tag("state", state)
                .description("Number of workflows in " + state + " state")
                .register(registry);
        }
        
        // Register task state gauges
        for (String state : new String[]{"SCHEDULED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED"}) {
            AtomicInteger gauge = new AtomicInteger(0);
            taskStateGauges.put(state, gauge);
            Gauge.builder(TASK_COUNT, gauge, AtomicInteger::get)
                .tag("state", state)
                .description("Number of tasks in " + state + " state")
                .register(registry);
        }
    }

    // ========== Workflow Metrics ==========

    public void workflowStarted(String namespace, String workflowName) {
        Counter.builder(WORKFLOW_STARTED)
            .tag("namespace", namespace)
            .tag("workflow", workflowName)
            .description("Total workflows started")
            .register(registry)
            .increment();
        
        incrementWorkflowState("RUNNING");
        decrementWorkflowState("PENDING");
    }

    public void workflowCompleted(String namespace, String workflowName, long durationMs) {
        Counter.builder(WORKFLOW_COMPLETED)
            .tag("namespace", namespace)
            .tag("workflow", workflowName)
            .description("Total workflows completed successfully")
            .register(registry)
            .increment();
        
        Timer.builder("orchestrator.workflow.duration")
            .tag("namespace", namespace)
            .tag("workflow", workflowName)
            .tag("outcome", "success")
            .description("Workflow execution duration")
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
        
        incrementWorkflowState("COMPLETED");
        decrementWorkflowState("RUNNING");
    }

    public void workflowFailed(String namespace, String workflowName, String errorType) {
        Counter.builder(WORKFLOW_FAILED)
            .tag("namespace", namespace)
            .tag("workflow", workflowName)
            .tag("error_type", errorType)
            .description("Total workflows failed")
            .register(registry)
            .increment();
        
        incrementWorkflowState("FAILED");
        decrementWorkflowState("RUNNING");
    }

    public void workflowCancelled(String namespace, String workflowName) {
        Counter.builder("orchestrator.workflows.cancelled")
            .tag("namespace", namespace)
            .tag("workflow", workflowName)
            .description("Total workflows cancelled")
            .register(registry)
            .increment();
        
        incrementWorkflowState("CANCELLED");
        decrementWorkflowState("RUNNING");
    }

    public void workflowPaused(String namespace, String workflowName) {
        Counter.builder("orchestrator.workflows.paused")
            .tag("namespace", namespace)
            .tag("workflow", workflowName)
            .description("Total workflows paused")
            .register(registry)
            .increment();
        
        incrementWorkflowState("PAUSED");
        decrementWorkflowState("RUNNING");
    }

    public void workflowResumed(String namespace, String workflowName) {
        Counter.builder("orchestrator.workflows.resumed")
            .tag("namespace", namespace)
            .tag("workflow", workflowName)
            .description("Total workflows resumed")
            .register(registry)
            .increment();
        
        incrementWorkflowState("RUNNING");
        decrementWorkflowState("PAUSED");
    }

    // ========== Task Metrics ==========

    public void taskStarted(String namespace, String workflowName, String taskName) {
        Counter.builder("orchestrator.tasks.started")
            .tag("namespace", namespace)
            .tag("workflow", workflowName)
            .tag("task", taskName)
            .description("Total tasks started")
            .register(registry)
            .increment();
        
        incrementTaskState("RUNNING");
        decrementTaskState("SCHEDULED");
    }

    public void taskCompleted(String namespace, String workflowName, String taskName, long durationMs) {
        Counter.builder("orchestrator.tasks.completed")
            .tag("namespace", namespace)
            .tag("workflow", workflowName)
            .tag("task", taskName)
            .description("Total tasks completed successfully")
            .register(registry)
            .increment();
        
        Timer.builder(TASK_DURATION)
            .tag("namespace", namespace)
            .tag("workflow", workflowName)
            .tag("task", taskName)
            .tag("outcome", "success")
            .description("Task execution duration")
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
        
        incrementTaskState("COMPLETED");
        decrementTaskState("RUNNING");
    }

    public void taskFailed(String namespace, String workflowName, String taskName, 
                           String errorType, boolean willRetry) {
        Counter.builder(TASK_FAILURES)
            .tag("namespace", namespace)
            .tag("workflow", workflowName)
            .tag("task", taskName)
            .tag("error_type", errorType)
            .tag("will_retry", String.valueOf(willRetry))
            .description("Total task failures")
            .register(registry)
            .increment();
        
        if (!willRetry) {
            incrementTaskState("FAILED");
            decrementTaskState("RUNNING");
        }
    }

    public void taskRetried(String namespace, String workflowName, String taskName, int attemptNumber) {
        Counter.builder(TASK_RETRIES)
            .tag("namespace", namespace)
            .tag("workflow", workflowName)
            .tag("task", taskName)
            .tag("attempt", String.valueOf(attemptNumber))
            .description("Total task retry attempts")
            .register(registry)
            .increment();
    }

    public void taskTimedOut(String namespace, String workflowName, String taskName) {
        Counter.builder("orchestrator.task.timeouts")
            .tag("namespace", namespace)
            .tag("workflow", workflowName)
            .tag("task", taskName)
            .description("Total task timeouts")
            .register(registry)
            .increment();
    }

    // ========== Lease Metrics ==========

    public void leaseAcquired(String leaseKey, boolean success) {
        Counter.builder(LEASE_ACQUISITIONS)
            .tag("success", String.valueOf(success))
            .description("Lease acquisition attempts")
            .register(registry)
            .increment();
    }

    public void leaseRenewed(String leaseKey, boolean success) {
        Counter.builder(LEASE_RENEWALS)
            .tag("success", String.valueOf(success))
            .description("Lease renewal attempts")
            .register(registry)
            .increment();
    }

    public void leaseExpired(String leaseKey) {
        Counter.builder(LEASE_EXPIRATIONS)
            .description("Total lease expirations")
            .register(registry)
            .increment();
    }

    // ========== Queue Metrics ==========

    public void recordQueueSize(String queueName, int size) {
        Gauge.builder(QUEUE_SIZE, size, Number::doubleValue)
            .tag("queue", queueName)
            .description("Current queue size")
            .register(registry);
    }

    // ========== Recovery Metrics ==========

    public void recoveryAttempted(String namespace, String workflowName, boolean success) {
        Counter.builder(RECOVERY_ATTEMPTS)
            .tag("namespace", namespace)
            .tag("workflow", workflowName)
            .tag("success", String.valueOf(success))
            .description("Workflow recovery attempts")
            .register(registry)
            .increment();
    }

    // ========== Saga/Compensation Metrics ==========

    /**
     * Record a saga compensation execution.
     */
    public void compensationStarted(String namespace, String workflowName, String compensationTaskName) {
        Counter.builder(SAGA_COMPENSATIONS)
            .tag("namespace", namespace)
            .tag("workflow", workflowName)
            .tag("task", compensationTaskName)
            .tag("outcome", "started")
            .description("Total saga compensations started")
            .register(registry)
            .increment();
    }

    /**
     * Record successful saga compensation.
     */
    public void compensationCompleted(String namespace, String workflowName, 
                                       String compensationTaskName, long durationMs) {
        Counter.builder(SAGA_COMPENSATIONS)
            .tag("namespace", namespace)
            .tag("workflow", workflowName)
            .tag("task", compensationTaskName)
            .tag("outcome", "completed")
            .description("Total saga compensations completed")
            .register(registry)
            .increment();
        
        Timer.builder(COMPENSATION_DURATION)
            .tag("namespace", namespace)
            .tag("workflow", workflowName)
            .tag("task", compensationTaskName)
            .description("Compensation execution duration")
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
    }

    /**
     * Record failed saga compensation.
     */
    public void compensationFailed(String namespace, String workflowName, 
                                    String compensationTaskName, String errorType) {
        Counter.builder(SAGA_COMPENSATIONS)
            .tag("namespace", namespace)
            .tag("workflow", workflowName)
            .tag("task", compensationTaskName)
            .tag("outcome", "failed")
            .tag("error_type", errorType)
            .description("Total saga compensations failed")
            .register(registry)
            .increment();
    }

    // ========== Dead Letter Metrics ==========

    /**
     * Record a workflow moved to dead letter queue.
     */
    public void recordDeadLetter(String namespace, String workflowName, String reason) {
        Counter.builder(DEAD_LETTER_TOTAL)
            .tag("namespace", namespace)
            .tag("workflow", workflowName)
            .tag("reason", sanitizeReason(reason))
            .description("Total workflows moved to dead letter queue")
            .register(registry)
            .increment();
    }

    /**
     * Record a workflow retried from dead letter queue.
     */
    public void recordDeadLetterRetried(String namespace, String workflowName) {
        Counter.builder(DEAD_LETTER_RETRIED)
            .tag("namespace", namespace)
            .tag("workflow", workflowName)
            .description("Total workflows retried from dead letter queue")
            .register(registry)
            .increment();
    }

    /**
     * Record a workflow discarded from dead letter queue.
     */
    public void recordDeadLetterDiscarded(String namespace, String workflowName) {
        Counter.builder(DEAD_LETTER_DISCARDED)
            .tag("namespace", namespace)
            .tag("workflow", workflowName)
            .description("Total workflows discarded from dead letter queue")
            .register(registry)
            .increment();
    }

    /**
     * Sanitize reason string for use as a metric tag.
     */
    private String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unspecified";
        }
        // Truncate and normalize for metric label
        String sanitized = reason.toLowerCase()
            .replaceAll("[^a-z0-9_]", "_")
            .replaceAll("_+", "_");
        return sanitized.length() > 50 ? sanitized.substring(0, 50) : sanitized;
    }

    // ========== Helper Methods ==========

    private void incrementWorkflowState(String state) {
        AtomicInteger gauge = workflowStateGauges.get(state);
        if (gauge != null) {
            gauge.incrementAndGet();
        }
    }

    private void decrementWorkflowState(String state) {
        AtomicInteger gauge = workflowStateGauges.get(state);
        if (gauge != null) {
            gauge.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    private void incrementTaskState(String state) {
        AtomicInteger gauge = taskStateGauges.get(state);
        if (gauge != null) {
            gauge.incrementAndGet();
        }
    }

    private void decrementTaskState(String state) {
        AtomicInteger gauge = taskStateGauges.get(state);
        if (gauge != null) {
            gauge.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    /**
     * Update gauge values from database state (for accuracy after restart).
     */
    public void syncWorkflowStateGauge(String state, int count) {
        AtomicInteger gauge = workflowStateGauges.get(state);
        if (gauge != null) {
            gauge.set(count);
        }
    }

    public void syncTaskStateGauge(String state, int count) {
        AtomicInteger gauge = taskStateGauges.get(state);
        if (gauge != null) {
            gauge.set(count);
        }
    }
}

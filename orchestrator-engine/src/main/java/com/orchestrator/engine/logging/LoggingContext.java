package com.orchestrator.engine.logging;

import org.slf4j.MDC;
import java.util.UUID;

/**
 * MDC (Mapped Diagnostic Context) helper for structured logging.
 * Ensures all logs include relevant correlation IDs for tracing.
 *
 * Usage:
 * <pre>
 * try (var ctx = LoggingContext.forWorkflow(workflowId, taskId)) {
 *     log.info("Processing task"); // Automatically includes workflowId, taskId
 * }
 * </pre>
 *
 * Log output with MDC:
 * 2024-01-15 10:30:45.123 [worker-1] INFO  c.o.e.TaskCoordinator - Processing task
 *   workflowId=abc-123 taskId=process-payment attempt=1 correlationId=order-456
 */
public final class LoggingContext implements AutoCloseable {

    public static final String WORKFLOW_ID = "workflowId";
    public static final String TASK_ID = "taskId";
    public static final String TASK_EXECUTION_ID = "taskExecutionId";
    public static final String ATTEMPT = "attempt";
    public static final String CORRELATION_ID = "correlationId";
    public static final String NAMESPACE = "namespace";
    public static final String WORKER_ID = "workerId";
    public static final String TRACE_ID = "traceId";

    private LoggingContext() {
        // Private constructor - use static factory methods
    }

    /**
     * Create a logging context for workflow-level operations.
     */
    public static LoggingContext forWorkflow(UUID workflowId) {
        return forWorkflow(workflowId, null, null);
    }

    /**
     * Create a logging context for workflow-level operations with correlation.
     */
    public static LoggingContext forWorkflow(UUID workflowId, String correlationId) {
        return forWorkflow(workflowId, correlationId, null);
    }

    /**
     * Create a logging context for workflow-level operations.
     */
    public static LoggingContext forWorkflow(UUID workflowId, String correlationId, String namespace) {
        LoggingContext ctx = new LoggingContext();
        if (workflowId != null) {
            MDC.put(WORKFLOW_ID, workflowId.toString());
        }
        if (correlationId != null) {
            MDC.put(CORRELATION_ID, correlationId);
        }
        if (namespace != null) {
            MDC.put(NAMESPACE, namespace);
        }
        ensureTraceId();
        return ctx;
    }

    /**
     * Create a logging context for task-level operations.
     */
    public static LoggingContext forTask(UUID workflowId, String taskId, int attempt) {
        return forTask(workflowId, taskId, null, attempt);
    }

    /**
     * Create a logging context for task-level operations.
     */
    public static LoggingContext forTask(UUID workflowId, String taskId, UUID taskExecutionId, int attempt) {
        LoggingContext ctx = new LoggingContext();
        if (workflowId != null) {
            MDC.put(WORKFLOW_ID, workflowId.toString());
        }
        if (taskId != null) {
            MDC.put(TASK_ID, taskId);
        }
        if (taskExecutionId != null) {
            MDC.put(TASK_EXECUTION_ID, taskExecutionId.toString());
        }
        MDC.put(ATTEMPT, String.valueOf(attempt));
        ensureTraceId();
        return ctx;
    }

    /**
     * Create a logging context for worker operations.
     */
    public static LoggingContext forWorker(String workerId) {
        LoggingContext ctx = new LoggingContext();
        if (workerId != null) {
            MDC.put(WORKER_ID, workerId);
        }
        ensureTraceId();
        return ctx;
    }

    /**
     * Add correlation ID to current context.
     */
    public static void setCorrelationId(String correlationId) {
        if (correlationId != null) {
            MDC.put(CORRELATION_ID, correlationId);
        }
    }

    /**
     * Add namespace to current context.
     */
    public static void setNamespace(String namespace) {
        if (namespace != null) {
            MDC.put(NAMESPACE, namespace);
        }
    }

    /**
     * Get current workflow ID from context.
     */
    public static String getWorkflowId() {
        return MDC.get(WORKFLOW_ID);
    }

    /**
     * Get current task ID from context.
     */
    public static String getTaskId() {
        return MDC.get(TASK_ID);
    }

    /**
     * Get current trace ID from context.
     */
    public static String getTraceId() {
        return MDC.get(TRACE_ID);
    }

    /**
     * Ensure a trace ID exists in the context.
     */
    private static void ensureTraceId() {
        if (MDC.get(TRACE_ID) == null) {
            MDC.put(TRACE_ID, UUID.randomUUID().toString().substring(0, 8));
        }
    }

    @Override
    public void close() {
        MDC.remove(WORKFLOW_ID);
        MDC.remove(TASK_ID);
        MDC.remove(TASK_EXECUTION_ID);
        MDC.remove(ATTEMPT);
        MDC.remove(CORRELATION_ID);
        MDC.remove(NAMESPACE);
        MDC.remove(WORKER_ID);
        // Keep TRACE_ID for request-scoped tracing
    }

    /**
     * Clear all MDC context. Call at the end of a request or worker loop.
     */
    public static void clearAll() {
        MDC.clear();
    }
}

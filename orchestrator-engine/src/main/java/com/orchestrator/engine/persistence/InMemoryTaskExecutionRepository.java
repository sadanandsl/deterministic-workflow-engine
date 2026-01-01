package com.orchestrator.engine.persistence;

import com.orchestrator.core.model.TaskExecution;
import com.orchestrator.core.model.TaskState;
import com.orchestrator.core.repository.TaskExecutionRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of TaskExecutionRepository.
 * For demonstration and testing purposes.
 */
@Repository
public class InMemoryTaskExecutionRepository implements TaskExecutionRepository {
    
    private final Map<UUID, TaskExecution> executions = new ConcurrentHashMap<>();
    private final Map<String, TaskExecution> byIdempotencyKey = new ConcurrentHashMap<>();
    
    @Override
    public void save(TaskExecution execution) {
        executions.put(execution.executionId(), execution);
        byIdempotencyKey.put(execution.idempotencyKey(), execution);
    }
    
    @Override
    public void update(TaskExecution execution) {
        executions.put(execution.executionId(), execution);
        byIdempotencyKey.put(execution.idempotencyKey(), execution);
    }
    
    @Override
    public Optional<TaskExecution> findById(UUID executionId) {
        return Optional.ofNullable(executions.get(executionId));
    }
    
    @Override
    public Optional<TaskExecution> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(byIdempotencyKey.get(idempotencyKey));
    }
    
    @Override
    public List<TaskExecution> findByWorkflowInstance(UUID workflowInstanceId) {
        return executions.values().stream()
            .filter(e -> e.workflowInstanceId().equals(workflowInstanceId))
            .sorted(Comparator.comparing(TaskExecution::scheduledAt))
            .collect(Collectors.toList());
    }
    
    @Override
    public List<TaskExecution> findByTask(UUID workflowInstanceId, String taskId) {
        return executions.values().stream()
            .filter(e -> e.workflowInstanceId().equals(workflowInstanceId) && e.taskId().equals(taskId))
            .sorted(Comparator.comparing(TaskExecution::attemptNumber))
            .collect(Collectors.toList());
    }
    
    @Override
    public Optional<TaskExecution> findLatestByTask(UUID workflowInstanceId, String taskId) {
        return executions.values().stream()
            .filter(e -> e.workflowInstanceId().equals(workflowInstanceId) && e.taskId().equals(taskId))
            .max(Comparator.comparing(TaskExecution::attemptNumber));
    }
    
    @Override
    public List<TaskExecution> findByState(TaskState state, int limit) {
        return executions.values().stream()
            .filter(e -> e.state() == state)
            .sorted(Comparator.comparing(TaskExecution::scheduledAt))
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    @Override
    public List<TaskExecution> findExpiredLeases(Instant now, int limit) {
        return executions.values().stream()
            .filter(e -> e.state() == TaskState.RUNNING)
            .filter(e -> e.leaseExpiresAt() != null && e.leaseExpiresAt().isBefore(now))
            .sorted(Comparator.comparing(TaskExecution::leaseExpiresAt))
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    @Override
    public List<TaskExecution> findQueuedByActivityType(String activityType, int limit) {
        return executions.values().stream()
            .filter(e -> e.state() == TaskState.QUEUED)
            .sorted(Comparator.comparing(TaskExecution::scheduledAt))
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    @Override
    public int getCurrentAttemptCount(UUID workflowInstanceId, String taskId) {
        return (int) executions.values().stream()
            .filter(e -> e.workflowInstanceId().equals(workflowInstanceId) && e.taskId().equals(taskId))
            .count();
    }
}

package com.orchestrator.engine.persistence;

import com.orchestrator.core.model.WorkflowInstance;
import com.orchestrator.core.model.WorkflowState;
import com.orchestrator.core.repository.WorkflowInstanceRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of WorkflowInstanceRepository.
 * For demonstration and testing purposes.
 */
@Repository
public class InMemoryWorkflowInstanceRepository implements WorkflowInstanceRepository {
    
    private final Map<UUID, WorkflowInstance> instances = new ConcurrentHashMap<>();
    
    @Override
    public void save(WorkflowInstance instance) {
        instances.put(instance.instanceId(), instance);
    }
    
    @Override
    public void update(WorkflowInstance instance) {
        instances.put(instance.instanceId(), instance);
    }
    
    @Override
    public Optional<WorkflowInstance> findById(UUID instanceId) {
        return Optional.ofNullable(instances.get(instanceId));
    }
    
    @Override
    public Optional<WorkflowInstance> findByRunId(String namespace, String workflowName, String runId) {
        return instances.values().stream()
            .filter(i -> i.namespace().equals(namespace) 
                      && i.workflowName().equals(workflowName) 
                      && i.runId().equals(runId))
            .findFirst();
    }
    
    @Override
    public List<WorkflowInstance> findByCorrelationId(String correlationId) {
        return instances.values().stream()
            .filter(i -> correlationId.equals(i.correlationId()))
            .collect(Collectors.toList());
    }
    
    @Override
    public List<WorkflowInstance> findByState(WorkflowState state, int limit) {
        return instances.values().stream()
            .filter(i -> i.state() == state)
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    @Override
    public List<WorkflowInstance> findExpiredDeadlines(Instant now, int limit) {
        return instances.values().stream()
            .filter(i -> i.deadline() != null && i.deadline().isBefore(now))
            .filter(i -> !i.state().isTerminal())
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    @Override
    public List<WorkflowInstance> findStuckInstances(Instant stuckSince, int limit) {
        return instances.values().stream()
            .filter(i -> i.state() == WorkflowState.RUNNING)
            .filter(i -> i.lastRecoveryAt() == null || i.lastRecoveryAt().isBefore(stuckSince))
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    @Override
    public Map<WorkflowState, Long> countByState(String namespace, String workflowName) {
        return instances.values().stream()
            .filter(i -> i.namespace().equals(namespace) && i.workflowName().equals(workflowName))
            .collect(Collectors.groupingBy(WorkflowInstance::state, Collectors.counting()));
    }
    
    @Override
    public int deleteCompletedBefore(Instant completedBefore) {
        List<UUID> toDelete = instances.values().stream()
            .filter(i -> i.state().isTerminal())
            .filter(i -> i.completedAt() != null && i.completedAt().isBefore(completedBefore))
            .map(WorkflowInstance::instanceId)
            .collect(Collectors.toList());
        
        toDelete.forEach(instances::remove);
        return toDelete.size();
    }
}

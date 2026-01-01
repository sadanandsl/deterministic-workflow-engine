# orchestrator-recovery

Failure detection and automatic recovery engine for the Workflow Orchestrator.

## Overview

The Recovery Engine runs continuously to detect and recover from various failure modes:

- **Expired leases** - Workers that crashed without releasing their lease
- **Timed out tasks** - Tasks that exceeded their timeout
- **Stuck workflows** - Workflows that stopped progressing
- **Deadline violations** - Workflows that exceeded their deadline

## How It Works

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Recovery Engine                                  │
│                                                                         │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐         │
│  │ Lease Recovery  │  │Timeout Detection│  │ Stuck Workflow  │         │
│  │   (every 5s)    │  │   (every 10s)   │  │   (every 60s)   │         │
│  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘         │
│           │                    │                    │                   │
│           ▼                    ▼                    ▼                   │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                     State Store (Postgres)                       │   │
│  │  • Find expired leases                                           │   │
│  │  • Find timed-out tasks                                          │   │
│  │  • Find stuck workflows                                          │   │
│  │  • Find deadline violations                                      │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│           │                    │                    │                   │
│           ▼                    ▼                    ▼                   │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐         │
│  │ Force-release   │  │  Mark as       │  │ Log warning,    │         │
│  │ lease, bump     │  │  timed out,    │  │ trigger recovery│         │
│  │ fence token,    │  │  requeue task  │  │ evaluation      │         │
│  │ requeue task    │  │                │  │                 │         │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘         │
└─────────────────────────────────────────────────────────────────────────┘
```

## Recovery Scenarios

### 1. Expired Lease Recovery

When a worker crashes, its lease eventually expires:

```
Timeline:
T0: Worker A acquires lease (expires at T0 + 30s)
T1: Worker A crashes
T2: Lease expires (T0 + 30s)
T3: Recovery Engine detects expired lease
    → Force-release lease
    → Increment fence token (invalidates zombie worker)
    → Requeue task for retry
T4: Worker B acquires new lease
```

```java
private void recoverExpiredLeases() {
    List<ExecutionLease> expired = leaseRepository.findExpired(Instant.now());
    
    for (ExecutionLease lease : expired) {
        // Increment fence token to invalidate old lease holder
        long newFenceToken = leaseRepository.forceRelease(lease.leaseKey());
        
        // Mark task as timed out
        TaskExecution execution = findExecution(lease);
        taskExecutionRepository.update(execution.withTimedOut());
        
        // Requeue for retry
        taskService.retryTask(
            execution.workflowInstanceId(),
            execution.taskId(),
            execution.input()
        );
        
        log.info("Recovered expired lease: {} (new fence token: {})", 
            lease.leaseKey(), newFenceToken);
    }
}
```

### 2. Task Timeout Detection

Tasks that exceed their configured timeout:

```java
private void detectTimedOutTasks() {
    List<TaskExecution> timedOut = taskExecutionRepository
        .findTimedOutTasks(Instant.now());
    
    for (TaskExecution execution : timedOut) {
        // Force release the lease
        leaseRepository.forceRelease(
            ExecutionLease.createLeaseKey(
                execution.workflowInstanceId(),
                execution.taskId()
            )
        );
        
        // Mark as timed out
        taskExecutionRepository.update(execution.withTimedOut());
        
        // Record event
        recordEvent(execution.workflowInstanceId(), 
            EventType.TASK_TIMED_OUT);
    }
}
```

### 3. Stuck Workflow Detection

Workflows that haven't progressed within a threshold (default: 30 minutes):

```java
private void detectStuckWorkflows() {
    Instant threshold = Instant.now().minus(STUCK_WORKFLOW_THRESHOLD);
    
    List<WorkflowInstance> stuck = workflowInstanceRepository
        .findStuckInstances(threshold);
    
    for (WorkflowInstance instance : stuck) {
        log.warn("Workflow {} appears stuck (state={}, last activity={})",
            instance.instanceId(), instance.state(), threshold);
        
        // Record for alerting/monitoring
        recordEvent(instance.instanceId(), EventType.RECOVERY_STARTED);
        
        // Attempt recovery based on state
        attemptRecovery(instance);
    }
}
```

### 4. Deadline Violation

Workflows that exceeded their configured deadline:

```java
private void detectDeadlineViolations() {
    List<WorkflowInstance> expired = workflowInstanceRepository
        .findExpiredDeadlines(Instant.now());
    
    for (WorkflowInstance instance : expired) {
        if (!instance.isTerminal()) {
            log.warn("Workflow {} exceeded deadline", instance.instanceId());
            
            // Mark as failed
            WorkflowInstance failed = instance.toBuilder()
                .state(WorkflowState.FAILED)
                .completedAt(Instant.now())
                .build();
            
            workflowInstanceRepository.update(failed);
            
            recordEvent(instance.instanceId(), 
                EventType.WORKFLOW_FAILED, 
                "Deadline exceeded");
        }
    }
}
```

## Configuration

```yaml
orchestrator:
  recovery:
    enabled: true
    lease-check-interval: 5s        # How often to check for expired leases
    timeout-check-interval: 10s     # How often to check for timed out tasks
    stuck-workflow-check-interval: 60s
    stuck-workflow-threshold: 30m   # When to consider a workflow stuck
    deadline-check-interval: 30s
    batch-size: 100                 # Max items to process per cycle
```

## Metrics

The Recovery Engine exposes metrics for monitoring:

| Metric | Description |
|--------|-------------|
| `recovery.leases.expired` | Counter of expired leases recovered |
| `recovery.tasks.timedout` | Counter of timed out tasks |
| `recovery.workflows.stuck` | Gauge of currently stuck workflows |
| `recovery.workflows.deadline.exceeded` | Counter of deadline violations |

## Usage

The Recovery Engine is typically started automatically with the orchestrator:

```java
@Bean
public RecoveryEngine recoveryEngine(
        LeaseRepository leaseRepository,
        TaskExecutionRepository taskExecutionRepository,
        WorkflowInstanceRepository workflowInstanceRepository,
        EventRepository eventRepository,
        TaskService taskService) {
    
    RecoveryEngine engine = new RecoveryEngine(
        leaseRepository,
        taskExecutionRepository,
        workflowInstanceRepository,
        eventRepository,
        taskService
    );
    
    engine.start();
    return engine;
}
```

## Dependencies

```xml
<dependency>
    <groupId>com.orchestrator</groupId>
    <artifactId>orchestrator-recovery</artifactId>
</dependency>
```

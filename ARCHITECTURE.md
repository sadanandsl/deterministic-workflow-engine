# Architecture

This document describes the internal architecture of the Workflow Orchestrator, including the state machine, lease model, failure recovery, idempotency guarantees, and saga compensation.

## Table of Contents

- [System Overview](#system-overview)
- [Workflow State Machine](#workflow-state-machine)
- [Task State Machine](#task-state-machine)
- [Lease & Fencing Model](#lease--fencing-model)
- [Failure Recovery](#failure-recovery)
- [Idempotency](#idempotency)
- [Saga Compensation](#saga-compensation)
- [Event Sourcing](#event-sourcing)
- [Database Schema](#database-schema)

---

## System Overview

The orchestrator follows a **control plane / data plane** separation:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              CONTROL PLANE                                   │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                        WorkflowCoordinator                           │    │
│  │                                                                      │    │
│  │  • Receives workflow start requests                                  │    │
│  │  • Manages workflow state transitions                                │    │
│  │  • Schedules tasks based on DAG edges                                │    │
│  │  • Handles signals and external events                               │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                    │                                         │
│                                    ▼                                         │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                         TaskCoordinator                              │    │
│  │                                                                      │    │
│  │  • Manages task lifecycle (schedule → acquire → complete/fail)       │    │
│  │  • Handles lease acquisition and validation                          │    │
│  │  • Processes task completions with fence token checks                │    │
│  │  • Triggers retries on failure                                       │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                    │                                         │
│                                    ▼                                         │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                        RecoveryEngine                                │    │
│  │                                                                      │    │
│  │  • Monitors expired leases (crashed workers)                         │    │
│  │  • Detects stuck workflows                                           │    │
│  │  • Handles deadline violations                                       │    │
│  │  • Automatically requeues failed tasks                               │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                          State Store                                 │    │
│  │                                                                      │    │
│  │  PostgreSQL:                                                         │    │
│  │  • workflow_instances - Current workflow state                       │    │
│  │  • task_executions - Task attempts and results                       │    │
│  │  • execution_leases - Distributed locks                              │    │
│  │  • events - Append-only audit log                                    │    │
│  │  • workflow_definitions - Workflow DAG definitions                   │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────┘
                                     │
                          Task Queue (Kafka/Poll)
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              DATA PLANE                                      │
│                                                                              │
│  ┌───────────────────┐  ┌───────────────────┐  ┌───────────────────┐        │
│  │     Worker 1      │  │     Worker 2      │  │     Worker N      │        │
│  │                   │  │                   │  │                   │        │
│  │  1. Poll for task │  │  1. Poll for task │  │  1. Poll for task │        │
│  │  2. Acquire lease │  │  2. Acquire lease │  │  2. Acquire lease │        │
│  │  3. Execute       │  │  3. Execute       │  │  3. Execute       │        │
│  │  4. Heartbeat     │  │  4. Heartbeat     │  │  4. Heartbeat     │        │
│  │  5. Complete/Fail │  │  5. Complete/Fail │  │  5. Complete/Fail │        │
│  └───────────────────┘  └───────────────────┘  └───────────────────┘        │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Workflow State Machine

Workflows follow a strict state machine that ensures predictable behavior:

```
                            ┌─────────────────┐
                            │     CREATED     │
                            │  (Initial)      │
                            └────────┬────────┘
                                     │ start()
                                     ▼
                            ┌─────────────────┐
               ┌───────────►│     RUNNING     │◄──────────────┐
               │            │  (Executing)    │               │
               │            └────────┬────────┘               │
               │                     │                        │
               │        ┌────────────┼────────────┐           │
               │        ▼            ▼            ▼           │
               │   ┌─────────┐  ┌─────────┐  ┌─────────┐      │
               │   │ PAUSED  │  │COMPLET- │  │ FAILING │      │
               │   │         │  │  ING    │  │         │      │
               │   └────┬────┘  └────┬────┘  └────┬────┘      │
               │        │            │            │           │
               │ resume()│            │            │           │
               └────────┘            │            │           │
                                     ▼            │           │
                            ┌─────────────────┐   │           │
                            │   COMPLETED     │   │           │
                            │   (Terminal)    │   │           │
                            └─────────────────┘   │           │
                                                  │           │
                    ┌─────────────────────────────┘           │
                    │                                         │
                    ▼                                         │
        ┌───────────────────┐     ┌───────────────────┐       │
        │      FAILED       │────►│   COMPENSATING    │       │
        │    (Terminal*)    │     │                   │       │
        └───────────────────┘     └─────────┬─────────┘       │
                    │                       │                 │
                    │ retry()               │                 │
                    └───────────────────────┼─────────────────┘
                                            │
                              ┌─────────────┴─────────────┐
                              ▼                           ▼
                   ┌─────────────────┐         ┌─────────────────┐
                   │   COMPENSATED   │         │  COMPENSATION   │
                   │    (Terminal)   │         │     FAILED      │
                   └─────────────────┘         │   (Terminal)    │
                                               └─────────────────┘
```

### State Descriptions

| State | Description | Allowed Transitions |
|-------|-------------|---------------------|
| `CREATED` | Workflow registered, not yet started | → RUNNING |
| `RUNNING` | Actively executing tasks | → COMPLETING, FAILING, PAUSED |
| `PAUSED` | Execution halted, awaiting signal | → RUNNING, FAILING |
| `COMPLETING` | All tasks done, finalizing | → COMPLETED |
| `COMPLETED` | Successfully finished (terminal) | None |
| `FAILING` | Failure detected, evaluating retry/compensation | → RUNNING, COMPENSATING, FAILED |
| `FAILED` | Exhausted retries (terminal*) | → COMPENSATING, RUNNING (manual) |
| `COMPENSATING` | Executing compensation tasks | → COMPENSATED, COMPENSATION_FAILED |
| `COMPENSATED` | All compensations succeeded (terminal) | None |
| `COMPENSATION_FAILED` | Compensation failed (terminal) | None |

*FAILED is terminal for automatic transitions, but can be manually retried or compensated.

### State Transition Rules

```java
public boolean canTransitionTo(WorkflowState target) {
    return switch (this) {
        case CREATED -> target == RUNNING;
        case RUNNING -> target == COMPLETING || target == FAILING || target == PAUSED;
        case PAUSED -> target == RUNNING || target == FAILING;
        case COMPLETING -> target == COMPLETED;
        case COMPLETED -> false;
        case FAILING -> target == RUNNING || target == COMPENSATING || target == FAILED;
        case FAILED -> target == COMPENSATING || target == RUNNING;
        case COMPENSATING -> target == COMPENSATED || target == COMPENSATION_FAILED;
        case COMPENSATED, COMPENSATION_FAILED -> false;
    };
}
```

---

## Task State Machine

Tasks have their own lifecycle within a workflow:

```
                        ┌─────────────┐
                        │   PENDING   │
                        │  (Created)  │
                        └──────┬──────┘
                               │ schedule()
                               ▼
                        ┌─────────────┐
                        │   QUEUED    │◄────────────────┐
                        │  (Waiting)  │                 │
                        └──────┬──────┘                 │
                               │ acquire()              │
                               ▼                        │
                        ┌─────────────┐                 │
                        │   RUNNING   │                 │
                        │  (Leased)   │                 │
                        └──────┬──────┘                 │
                               │                        │
               ┌───────────────┼───────────────┐        │
               ▼               ▼               ▼        │ retry
        ┌───────────┐   ┌───────────┐   ┌───────────┐   │
        │ COMPLETED │   │  FAILED   │───┘   │ TIMED_OUT │
        │ (Terminal)│   │(Retryable)│       │(Retryable)│
        └───────────┘   └───────────┘       └───────────┘
```

### Task Execution Record

Each task execution attempt is recorded with:

```sql
CREATE TABLE task_executions (
    execution_id UUID PRIMARY KEY,
    workflow_instance_id UUID NOT NULL,
    task_id VARCHAR(255) NOT NULL,
    
    -- Idempotency: {workflow_id}:{task_id}:{attempt}
    idempotency_key VARCHAR(512) NOT NULL UNIQUE,
    attempt_number INTEGER NOT NULL DEFAULT 1,
    
    -- State
    state VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    
    -- Lease (for exactly-once)
    lease_holder UUID,
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    fence_token BIGINT NOT NULL DEFAULT 0,
    
    -- Results
    input_json JSONB,
    output_json JSONB,
    error_message TEXT
);
```

---

## Lease & Fencing Model

The lease model ensures exactly-once task execution even with network partitions and worker failures.

### How Leases Work

```
Timeline:
─────────────────────────────────────────────────────────────────────────►

T0: Worker A polls for task
    ┌─────────────────────────────────────────────────────────────────┐
    │ SELECT * FROM task_queue WHERE visible_at <= NOW()              │
    │ RETURNING execution_id = 'abc-123'                              │
    └─────────────────────────────────────────────────────────────────┘

T1: Worker A acquires lease (fence_token = 1)
    ┌─────────────────────────────────────────────────────────────────┐
    │ INSERT INTO execution_leases (lease_key, holder_id, fence_token)│
    │ VALUES ('workflow-1:task-1', 'worker-A', 1)                     │
    │ ON CONFLICT DO NOTHING                                          │
    │ -- Returns true if inserted, false if lease exists              │
    └─────────────────────────────────────────────────────────────────┘

T2: Worker A starts executing (lease expires at T1 + 30s)
    ┌──────────────────────────────────────┐
    │  Worker A: process_payment(order)    │
    └──────────────────────────────────────┘

T3: Worker A crashes (no heartbeat)
    💀

T4: Lease expires (T1 + 30s)
    ┌─────────────────────────────────────────────────────────────────┐
    │ -- Recovery Engine detects expired lease                        │
    │ UPDATE execution_leases                                         │
    │ SET fence_token = fence_token + 1,  -- Now fence_token = 2      │
    │     holder_id = NULL,                                           │
    │     expires_at = NULL                                           │
    │ WHERE lease_key = 'workflow-1:task-1'                           │
    │   AND expires_at < NOW()                                        │
    └─────────────────────────────────────────────────────────────────┘

T5: Worker B acquires lease (fence_token = 2)
    ┌─────────────────────────────────────────────────────────────────┐
    │ UPDATE execution_leases                                         │
    │ SET holder_id = 'worker-B', expires_at = NOW() + 30s            │
    │ WHERE lease_key = 'workflow-1:task-1' AND holder_id IS NULL     │
    └─────────────────────────────────────────────────────────────────┘

T6: Worker B completes task with fence_token = 2 ✓
    ┌─────────────────────────────────────────────────────────────────┐
    │ UPDATE task_executions                                          │
    │ SET state = 'COMPLETED', output_json = '...'                    │
    │ WHERE execution_id = 'abc-123'                                  │
    │   AND fence_token = 2  -- Validates current token               │
    └─────────────────────────────────────────────────────────────────┘

T7: Zombie Worker A wakes up, tries to complete with fence_token = 1 ✗
    ┌─────────────────────────────────────────────────────────────────┐
    │ UPDATE task_executions                                          │
    │ SET state = 'COMPLETED', output_json = '...'                    │
    │ WHERE execution_id = 'abc-123'                                  │
    │   AND fence_token = 1  -- FAILS! Token is now 2                 │
    │ -- Returns 0 rows affected, completion rejected                 │
    └─────────────────────────────────────────────────────────────────┘
```

### Lease Data Model

```java
public record ExecutionLease(
    String leaseKey,           // {workflowInstanceId}:{taskId}
    UUID holderId,             // Worker holding the lease
    String holderAddress,      // For debugging
    Instant acquiredAt,
    Instant expiresAt,         // Lease expiration time
    Duration leaseDuration,    // Default: 30 seconds
    int renewalCount,          // Number of heartbeat renewals
    long fenceToken            // Monotonically increasing
) {}
```

### Heartbeat Renewal

Workers must send heartbeats to keep their lease:

```java
// In ActivityContext
public boolean heartbeat() {
    return heartbeatCallback.sendHeartbeat();
}

// Worker usage
worker.registerActivity("long-running-task", context -> {
    for (int i = 0; i < 100; i++) {
        processChunk(i);
        
        // Renew lease every few chunks
        if (i % 10 == 0) {
            if (!context.heartbeat()) {
                throw new LeaseExpiredException("Lost lease mid-execution");
            }
        }
    }
    return result;
});
```

---

## Failure Recovery

The Recovery Engine runs continuously to detect and recover from various failure modes:

### 1. Expired Lease Recovery

```java
private void recoverExpiredLeases() {
    List<ExecutionLease> expiredLeases = leaseRepository.findExpired(Instant.now());
    
    for (ExecutionLease lease : expiredLeases) {
        // Increment fence token to invalidate old lease holder
        long newFenceToken = leaseRepository.forceRelease(lease.leaseKey());
        
        // Mark task as timed out
        TaskExecution execution = findExecution(lease);
        taskExecutionRepository.update(execution.withTimedOut());
        
        // Requeue for retry
        taskService.retryTask(execution.workflowInstanceId(), 
                              execution.taskId(), 
                              execution.input());
    }
}
```

### 2. Stuck Workflow Detection

```java
private void detectStuckWorkflows() {
    Instant threshold = Instant.now().minus(STUCK_WORKFLOW_THRESHOLD); // 30 min
    
    List<WorkflowInstance> stuckInstances = 
        workflowInstanceRepository.findStuckInstances(threshold);
    
    for (WorkflowInstance instance : stuckInstances) {
        log.warn("Workflow {} appears stuck", instance.instanceId());
        recordEvent(instance.instanceId(), EventType.RECOVERY_STARTED);
        
        // Check for orphaned tasks, trigger recovery
    }
}
```

### 3. Deadline Violation

```java
private void detectDeadlineViolations() {
    List<WorkflowInstance> expired = 
        workflowInstanceRepository.findExpiredDeadlines(Instant.now());
    
    for (WorkflowInstance instance : expired) {
        if (!instance.isTerminal()) {
            log.warn("Workflow {} exceeded deadline", instance.instanceId());
            
            // Mark as failed
            WorkflowInstance failed = instance.toBuilder()
                .state(WorkflowState.FAILED)
                .completedAt(Instant.now())
                .build();
            
            workflowInstanceRepository.update(failed);
        }
    }
}
```

### Recovery Schedule

| Check | Interval | Description |
|-------|----------|-------------|
| Lease expiry | 5s | Find tasks with expired leases, requeue |
| Task timeout | 10s | Find running tasks past their timeout |
| Stuck workflows | 60s | Find workflows not progressing |
| Deadline violation | 30s | Find workflows past their deadline |

---

## Idempotency

Idempotency ensures that duplicate executions don't cause harm.

### Three Layers of Idempotency

```
┌─────────────────────────────────────────────────────────────────────────┐
│ Layer 1: Workflow Instance Idempotency                                  │
│                                                                         │
│ UNIQUE (namespace, workflow_name, run_id)                               │
│                                                                         │
│ Starting the same workflow twice returns the existing instance:         │
│                                                                         │
│   POST /workflows/orders/order-fulfillment/start                        │
│   { "runId": "order-123" }  ──► Returns existing instance if exists     │
└─────────────────────────────────────────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ Layer 2: Task Execution Idempotency                                     │
│                                                                         │
│ UNIQUE (idempotency_key)                                                │
│ Format: {workflow_instance_id}:{task_id}:{attempt_number}               │
│                                                                         │
│ Example: "abc-123:process-payment:1"                                    │
│                                                                         │
│ If task is retried, attempt_number increments → new idempotency_key     │
└─────────────────────────────────────────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ Layer 3: External Call Idempotency                                      │
│                                                                         │
│ Workers pass idempotency_key to external services:                      │
│                                                                         │
│   POST /payment/charge                                                  │
│   {                                                                     │
│     "idempotency_key": "abc-123:process-payment:1",                     │
│     "amount": 99.99,                                                    │
│     "customer_id": "cust-456"                                           │
│   }                                                                     │
│                                                                         │
│ External service stores result keyed by idempotency_key.                │
│ Second call with same key returns stored result without re-processing.  │
└─────────────────────────────────────────────────────────────────────────┘
```

### Worker Best Practice

```java
worker.registerActivity("payment.process", context -> {
    String idempotencyKey = context.getIdempotencyKey();
    
    PaymentRequest request = context.getInput(PaymentRequest.class);
    
    // Pass idempotency key to payment provider
    PaymentResult result = paymentClient.charge(
        request.amount(),
        request.customerId(),
        idempotencyKey  // Provider uses this for deduplication
    );
    
    return context.toJsonNode(result);
});
```

---

## Saga Compensation

Sagas handle distributed transactions by defining compensating actions for each step.

### Workflow with Compensation

```java
// Define tasks with compensation
TaskDefinition reserveInventory = TaskDefinition.builder()
    .taskId("reserve_inventory")
    .type(TaskType.ACTIVITY)
    .activityType("inventory.reserve")
    .compensationTaskId("release_inventory")  // ← Compensation task
    .build();

TaskDefinition processPayment = TaskDefinition.builder()
    .taskId("process_payment")
    .type(TaskType.ACTIVITY)
    .activityType("payment.charge")
    .compensationTaskId("refund_payment")  // ← Compensation task
    .build();

TaskDefinition shipOrder = TaskDefinition.builder()
    .taskId("ship_order")
    .type(TaskType.ACTIVITY)
    .activityType("shipping.create")
    .build();

// Define compensation tasks
TaskDefinition releaseInventory = TaskDefinition.builder()
    .taskId("release_inventory")
    .type(TaskType.COMPENSATION)
    .activityType("inventory.release")
    .build();

TaskDefinition refundPayment = TaskDefinition.builder()
    .taskId("refund_payment")
    .type(TaskType.COMPENSATION)
    .activityType("payment.refund")
    .build();
```

### Compensation Flow

```
Normal Execution:
─────────────────────────────────────────────────────────────────────────►
  reserve_inventory ──► process_payment ──► ship_order ──► COMPLETED
         │                     │                │
         ▼                     ▼                ▼
    (inventory)           (payment)         (shipped)

If ship_order fails:
─────────────────────────────────────────────────────────────────────────►
  reserve_inventory ──► process_payment ──► ship_order ✗ FAILED
                                                │
                                                ▼
◄────────────────────────────────────────────────────────────────────────
                        COMPENSATING
                              │
         refund_payment ◄─────┘
              │
              ▼
         release_inventory
              │
              ▼
         COMPENSATED
```

### Compensation Execution Order

Compensation runs in **reverse order** of completed tasks:

```java
public void triggerCompensation(UUID instanceId, String fromTaskId) {
    WorkflowInstance instance = getWorkflow(instanceId);
    
    // Get completed tasks in execution order
    List<String> completedTasks = getCompletedTasksInOrder(instance);
    
    // Reverse for compensation
    Collections.reverse(completedTasks);
    
    // Schedule compensation tasks
    for (String taskId : completedTasks) {
        TaskDefinition task = getTaskDefinition(taskId);
        if (task.compensationTaskId() != null) {
            scheduleCompensationTask(instance, task.compensationTaskId());
        }
    }
    
    transitionState(instance, WorkflowState.COMPENSATING);
}
```

### Compensation Task Implementation

```java
// Regular task: Reserve inventory
worker.registerActivity("inventory.reserve", context -> {
    Order order = context.getInput(Order.class);
    
    ReservationResult result = inventoryService.reserve(
        order.items(),
        context.getIdempotencyKey()
    );
    
    return context.toJsonNode(result);
});

// Compensation task: Release inventory
worker.registerActivity("inventory.release", context -> {
    // Input contains the output from the original task
    ReservationResult originalResult = context.getInput(ReservationResult.class);
    
    inventoryService.release(
        originalResult.reservationId(),
        context.getIdempotencyKey()
    );
    
    return context.toJsonNode(Map.of("released", true));
});
```

---

## Event Sourcing

All state changes are recorded as immutable events:

### Event Structure

```java
public record Event(
    UUID eventId,
    UUID workflowInstanceId,
    long sequenceNumber,        // Ordering within workflow
    EventType eventType,
    Instant timestamp,
    JsonNode payload,
    UUID causedByEventId,       // Causality chain
    String idempotencyKey,      // Prevents duplicate events
    String traceId,
    String spanId,
    String actorType,           // SYSTEM, WORKER, USER, RECOVERY
    String actorId
) {}
```

### Event Types

| Category | Event Types |
|----------|-------------|
| Workflow | `WORKFLOW_CREATED`, `WORKFLOW_STARTED`, `WORKFLOW_COMPLETED`, `WORKFLOW_FAILED`, `WORKFLOW_PAUSED`, `WORKFLOW_RESUMED` |
| Task | `TASK_SCHEDULED`, `TASK_STARTED`, `TASK_COMPLETED`, `TASK_FAILED`, `TASK_RETRYING`, `TASK_TIMED_OUT` |
| Recovery | `LEASE_EXPIRED`, `RECOVERY_STARTED`, `DEADLINE_EXCEEDED` |
| Compensation | `COMPENSATION_TRIGGERED`, `COMPENSATION_COMPLETED`, `COMPENSATION_FAILED` |

### Event Replay

Events can be replayed to reconstruct workflow state:

```java
public WorkflowInstance reconstructFromEvents(UUID instanceId) {
    List<Event> events = eventRepository.findByWorkflowInstance(instanceId);
    
    WorkflowInstance instance = null;
    
    for (Event event : events) {
        instance = switch (event.eventType()) {
            case WORKFLOW_CREATED -> applyWorkflowCreated(event);
            case WORKFLOW_STARTED -> applyWorkflowStarted(instance, event);
            case TASK_COMPLETED -> applyTaskCompleted(instance, event);
            // ... handle all event types
            default -> instance;
        };
    }
    
    return instance;
}
```

---

## Database Schema

### Core Tables

```sql
-- Workflow definitions (immutable, versioned)
CREATE TABLE workflow_definitions (
    namespace VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    version INTEGER NOT NULL,
    tasks_json JSONB NOT NULL,
    PRIMARY KEY (namespace, name, version)
);

-- Workflow instances (mutable state)
CREATE TABLE workflow_instances (
    instance_id UUID PRIMARY KEY,
    namespace VARCHAR(255) NOT NULL,
    workflow_name VARCHAR(255) NOT NULL,
    run_id VARCHAR(512) NOT NULL,
    state VARCHAR(50) NOT NULL,
    sequence_number BIGINT NOT NULL DEFAULT 0,  -- Optimistic locking
    CONSTRAINT uk_idempotency UNIQUE (namespace, workflow_name, run_id)
);

-- Task executions (one per attempt)
CREATE TABLE task_executions (
    execution_id UUID PRIMARY KEY,
    workflow_instance_id UUID REFERENCES workflow_instances,
    idempotency_key VARCHAR(512) NOT NULL UNIQUE,
    fence_token BIGINT NOT NULL DEFAULT 0
);

-- Distributed locks
CREATE TABLE execution_leases (
    lease_key VARCHAR(512) PRIMARY KEY,
    holder_id UUID,
    expires_at TIMESTAMP WITH TIME ZONE,
    fence_token BIGINT NOT NULL DEFAULT 1
);

-- Append-only event log
CREATE TABLE events (
    event_id UUID PRIMARY KEY,
    workflow_instance_id UUID REFERENCES workflow_instances,
    sequence_number BIGINT NOT NULL,
    idempotency_key VARCHAR(512) NOT NULL UNIQUE,
    CONSTRAINT uk_sequence UNIQUE (workflow_instance_id, sequence_number)
);
```

### Key Indexes

```sql
-- For lease recovery
CREATE INDEX idx_lease_expires ON execution_leases(expires_at);

-- For task polling
CREATE INDEX idx_task_queue_poll ON task_queue(activity_type, visible_at, priority DESC);

-- For event replay
CREATE INDEX idx_event_sequence ON events(workflow_instance_id, sequence_number);

-- For stuck workflow detection  
CREATE INDEX idx_workflow_state ON workflow_instances(state) WHERE state = 'RUNNING';
```

---

## Further Reading

- [README.md](README.md) - Quick start and overview
- [CONTRIBUTING.md](CONTRIBUTING.md) - How to contribute
- [orchestrator-examples/README.md](orchestrator-examples/README.md) - Example workflows

# orchestrator-core

Core domain models, interfaces, and repository contracts for the Workflow Orchestrator.

## Overview

This module contains the foundational types that define the workflow orchestration domain. It has no external dependencies on persistence or frameworks, making it portable and testable.

## Package Structure

```
com.orchestrator.core/
├── model/          # Domain models (immutable records)
├── repository/     # Repository interfaces (ports)
└── exception/      # Domain exceptions
```

## Key Domain Models

### WorkflowInstance

Represents a single execution of a workflow definition:

```java
public record WorkflowInstance(
    UUID instanceId,
    String namespace,
    String workflowName,
    int workflowVersion,
    String runId,              // For idempotency
    String correlationId,      // For tracing
    WorkflowState state,
    String currentTaskId,
    Set<String> completedTaskIds,
    Set<String> failedTaskIds,
    JsonNode input,
    JsonNode output,
    // ... timestamps, recovery info
) {}
```

### WorkflowState

State machine for workflow lifecycle:

```
CREATED → RUNNING → COMPLETING → COMPLETED
              ↓           
           PAUSED ←→ RUNNING
              ↓
           FAILING → COMPENSATING → COMPENSATED
              ↓            ↓
           FAILED    COMPENSATION_FAILED
```

### TaskExecution

A single attempt to execute a task:

```java
public record TaskExecution(
    UUID executionId,
    UUID workflowInstanceId,
    String taskId,
    String idempotencyKey,     // {workflowId}:{taskId}:{attempt}
    int attemptNumber,
    TaskState state,
    UUID leaseHolder,          // Worker holding the lease
    Instant leaseExpiresAt,
    long fenceToken,           // For zombie detection
    JsonNode input,
    JsonNode output,
    // ... error info, timing
) {}
```

### ExecutionLease

Distributed lock for exactly-once execution:

```java
public record ExecutionLease(
    String leaseKey,           // {workflowId}:{taskId}
    UUID holderId,
    String holderAddress,
    Instant acquiredAt,
    Instant expiresAt,
    Duration leaseDuration,
    int renewalCount,
    long fenceToken            // Monotonically increasing
) {}
```

### RetryPolicy

Configuration for retry behavior:

```java
public record RetryPolicy(
    int maxAttempts,
    Duration initialBackoff,
    Duration maxBackoff,
    double backoffMultiplier,
    double jitterFactor,
    Set<String> retryableErrors,
    Set<String> nonRetryableErrors
) {}
```

## Repository Interfaces

The module defines repository interfaces (ports) that are implemented in `orchestrator-engine`:

| Interface | Purpose |
|-----------|---------|
| `WorkflowDefinitionRepository` | Store/retrieve workflow DAG definitions |
| `WorkflowInstanceRepository` | Manage workflow instance state |
| `TaskExecutionRepository` | Track task execution attempts |
| `LeaseRepository` | Distributed lock management |
| `EventRepository` | Append-only event log |

## Exceptions

| Exception | When Thrown |
|-----------|-------------|
| `NotFoundException` | Entity not found |
| `InvalidStateTransitionException` | Illegal state transition |
| `DuplicateKeyException` | Idempotency violation |
| `LeaseExpiredException` | Worker lost its lease |
| `FenceTokenMismatchException` | Stale worker completion |

## Usage

This module is a dependency of all other modules:

```xml
<dependency>
    <groupId>com.orchestrator</groupId>
    <artifactId>orchestrator-core</artifactId>
</dependency>
```

## Testing

```bash
mvn test -pl orchestrator-core
```
